package swiss.sib.rdfoverlap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Merges the sorted run files of an extraction in the background, so that merging overlaps with parsing.
 *
 * <p>The runs form a tree with a fixed fan-in. Level 0 holds runs. Whenever {@code fanIn} files wait at one level,
 * exactly those {@code fanIn} files are merged into one file at the next level. Every merge has the same shape, and the
 * number of files open in one merge is fixed.
 *
 * <p>There are two kinds of tree:
 * <ul>
 *   <li>One tree per input file. Its runs are merged while the file is still being parsed. If the file fails, its tree
 *       is deleted, and no other file's data has been merged into anything that is kept.</li>
 *   <li>One shared tree. When a file succeeds, its tree's files move to the shared tree, at the same levels, and the
 *       file is recorded as complete.</li>
 * </ul>
 *
 * <p>When extraction finishes, the shared tree is merged into one file, which is the dataset's sorted IRI file.
 *
 * <p>The shared tree and the completed files are the resumable state. They are saved to a {@link ManifestStore} each
 * time they change. A merge's output is saved before its inputs are deleted, so the saved state never names a file
 * that is missing.
 *
 * <p>One coordinator thread owns all the state. It takes events in order: the caller's runs, commits, discards and
 * finish, and the completion of each merge. Merges run on a thread pool, and each touches only its own inputs and
 * output. The number of merges in flight is capped by {@link #MAX_OPEN_FILES}.
 */
final class RunPool {

    static final int DEFAULT_FAN_IN = 16;

    /**
     * The hard limit on files open at once, for the whole extraction. A merge opens {@code fanIn} inputs and one output,
     * and {@link #RESERVED_FILES} stay free for the inputs being read and for everything else the process holds.
     */
    static final int MAX_OPEN_FILES = 768;
    static final int RESERVED_FILES = 8;

    /** The most merges that can run at once without exceeding {@link #MAX_OPEN_FILES}. */
    static int maxConcurrentMerges(int fanIn) {
        return Math.max(1, (MAX_OPEN_FILES - RESERVED_FILES) / (fanIn + 1));
    }

    /** Merge threads by default: one fewer than the available cores, and one on a single-core machine. */
    static int defaultThreads() {
        int cores = Runtime.getRuntime().availableProcessors();
        return cores > 1 ? cores - 1 : 1;
    }

    /** A tree of files, by level, and the merges of it that are in flight. Used only by the coordinator. */
    private static final class Tree {
        final List<List<Path>> levels = new ArrayList<>();
        /** Files of this tree that are inputs of a merge in flight. They stay in their level until it is recorded. */
        final Set<Path> busy = new HashSet<>();
        int inflight;
        boolean committed;
        boolean discarded;
        /** The record of the file behind this tree, held until its runs are in the shared tree. */
        ManifestStore.CompletedFile completion;

        List<Path> level(int level) {
            while (levels.size() <= level) {
                levels.add(new ArrayList<>());
            }
            return levels.get(level);
        }

        int files() {
            int count = 0;
            for (List<Path> level : levels) {
                count += level.size();
            }
            return count;
        }
    }

    private record Run(int file, Path path) {
    }

    private record Commit(int file, ManifestStore.CompletedFile completion) {
    }

    private record Discard(int file) {
    }

    private record Finish() {
    }

    private record Abort() {
    }

    private record MergeDone(Tree tree, int level, Path output, List<Path> inputs) {
    }

    private record MergeFailed(Throwable cause) {
    }

    private final Path runsDir;
    private final Path output;
    private final int fanIn;
    private final int rowGroupRows;
    private final int threads;
    private final ManifestStore store;
    private final BlockingQueue<Object> events = new LinkedBlockingQueue<>();
    private final CompletableFuture<Long> result = new CompletableFuture<>();
    private final Thread coordinator;

    /** Files read completely, in the order they were committed. Used only by the coordinator. */
    private final List<ManifestStore.CompletedFile> completed = new ArrayList<>();
    /** Trees of files that have not yet committed or been discarded. Used only by the coordinator. */
    private final java.util.Map<Integer, Tree> files = new java.util.HashMap<>();
    private final Tree shared = new Tree();
    private int inflight;
    private boolean finishing;
    private boolean aborting;

    RunPool(Path runsDir, Path output, int fanIn, int rowGroupRows) {
        this(runsDir, output, fanIn, rowGroupRows, defaultThreads());
    }

    RunPool(Path runsDir, Path output, int fanIn, int rowGroupRows, int threads) {
        this(runsDir, output, fanIn, rowGroupRows, threads, null, ManifestStore.Manifest.empty());
    }

    /**
     * A pool that saves its state to {@code store}, starting from {@code initial}. A null store keeps no state.
     */
    RunPool(Path runsDir, Path output, int fanIn, int rowGroupRows, int threads, ManifestStore store,
            ManifestStore.Manifest initial) {
        if (fanIn < 2) {
            throw new IllegalArgumentException("fanIn must be at least 2: " + fanIn);
        }
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be at least 1: " + threads);
        }
        this.runsDir = runsDir;
        this.output = output;
        this.fanIn = fanIn;
        this.rowGroupRows = rowGroupRows;
        this.threads = Math.min(threads, maxConcurrentMerges(fanIn));
        this.store = store;
        this.completed.addAll(initial.completed());
        for (int level = 0; level < initial.sharedLevels().size(); level++) {
            for (String name : initial.sharedLevels().get(level)) {
                shared.level(level).add(runsDir.resolve(name));
            }
        }
        this.coordinator = new Thread(this::coordinate, "iri-run-coordinator");
        this.coordinator.setDaemon(true);
        this.coordinator.start();
    }

    /** The number of merges that can run at once, after the cap for {@link #MAX_OPEN_FILES}. */
    int threads() {
        return threads;
    }

    /** Hands a run written for an input file to the pool. */
    void run(int file, Path path) throws IOException {
        submit(new Run(file, path));
    }

    /**
     * Says that an input file parsed successfully, so its files join the shared tree. The completion record is saved once
     * those files are in the shared tree.
     */
    void commit(int file, ManifestStore.CompletedFile completion) throws IOException {
        submit(new Commit(file, completion));
    }

    /** Says that an input file failed, so its files are deleted once any merge of them has finished. */
    void discard(int file) throws IOException {
        submit(new Discard(file));
    }

    /**
     * Merges the shared tree into the output, and returns the number of distinct IRIs in it.
     *
     * @throws IOException if a merge failed
     */
    long finish() throws IOException {
        submit(new Finish());
        try {
            return result.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while merging runs", e);
        } catch (ExecutionException e) {
            throw failure(e.getCause());
        }
    }

    /**
     * Stops the pool, and waits until its merges in flight have finished. The state is kept, so the extraction can be
     * resumed. Used when extraction has failed.
     */
    void abort() {
        events.add(new Abort());
        try {
            coordinator.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void submit(Object event) throws IOException {
        if (result.isCompletedExceptionally()) {
            throw failure(cause());
        }
        events.add(event);
    }

    private Throwable cause() {
        try {
            result.join();
            return new IOException("merge stopped");
        } catch (CompletionException e) {
            return e.getCause();
        }
    }

    private static IOException failure(Throwable cause) {
        return cause instanceof IOException io ? io : new IOException("merging runs failed", cause);
    }

    private void coordinate() {
        ExecutorService merges = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "iri-run-merge");
            thread.setDaemon(true);
            return thread;
        });
        try {
            while (true) {
                Object event = events.take();
                if (event instanceof Run run) {
                    Tree tree = files.computeIfAbsent(run.file(), k -> new Tree());
                    tree.level(0).add(run.path());
                    schedule(merges);
                } else if (event instanceof Commit commit) {
                    Tree tree = files.remove(commit.file());
                    if (tree == null) {
                        tree = new Tree();
                    }
                    tree.committed = true;
                    tree.completion = commit.completion();
                    settle(tree);
                    schedule(merges);
                } else if (event instanceof Discard discard) {
                    Tree tree = files.remove(discard.file());
                    if (tree != null) {
                        tree.discarded = true;
                        settle(tree);
                    }
                } else if (event instanceof MergeDone done) {
                    inflight--;
                    Tree tree = done.tree();
                    tree.inflight--;
                    tree.busy.removeAll(done.inputs());
                    tree.level(done.level()).removeAll(done.inputs());
                    tree.level(done.level() + 1).add(done.output());
                    if (tree == shared) {
                        // The output is saved before the inputs are deleted, so the saved state never names a missing file.
                        saveState();
                    }
                    deleteAll(done.inputs());
                    if (aborting) {
                        if (inflight == 0) {
                            result.completeExceptionally(new IOException("extraction was aborted"));
                            return;
                        }
                        continue;
                    }
                    settle(tree);
                    schedule(merges);
                } else if (event instanceof MergeFailed failed) {
                    result.completeExceptionally(failed.cause());
                    return;
                } else if (event instanceof Finish) {
                    finishing = true;
                } else if (event instanceof Abort) {
                    aborting = true;
                    if (inflight == 0) {
                        result.completeExceptionally(new IOException("extraction was aborted"));
                        return;
                    }
                }
                if (finishing && !aborting && inflight == 0) {
                    result.complete(mergeShared());
                    return;
                }
            }
        } catch (Throwable t) {
            result.completeExceptionally(t);
        } finally {
            merges.shutdown();
            try {
                merges.awaitTermination(1, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Acts on a tree whose merges have all finished. A committed tree moves its files to the shared tree, at the same
     * levels, and its completion is then saved. A discarded tree deletes its files. Until then the tree waits, so that no
     * file is deleted while a merge reads it.
     */
    private void settle(Tree tree) throws IOException {
        if (tree.inflight > 0) {
            return;
        }
        if (tree.discarded) {
            for (List<Path> level : tree.levels) {
                deleteAll(level);
                level.clear();
            }
        } else if (tree.committed) {
            for (int l = 0; l < tree.levels.size(); l++) {
                shared.level(l).addAll(tree.levels.get(l));
                tree.levels.get(l).clear();
            }
            if (tree.completion != null) {
                completed.add(tree.completion);
                tree.completion = null;
            }
            saveState();
        }
    }

    /** Saves the shared tree and the completed files, when there is a store to save them in. */
    private void saveState() throws IOException {
        if (store == null) {
            return;
        }
        List<List<String>> levels = new ArrayList<>();
        for (List<Path> level : shared.levels) {
            List<String> names = new ArrayList<>();
            for (Path file : level) {
                names.add(file.getFileName().toString());
            }
            levels.add(names);
        }
        store.save(new ManifestStore.Manifest(List.copyOf(completed), levels));
    }

    /**
     * Starts merges of exactly {@code fanIn} files at a level, wherever a level has that many that are not already in a
     * merge, while threads are free.
     */
    private void schedule(ExecutorService merges) {
        if (aborting) {
            return;
        }
        List<Tree> trees = new ArrayList<>(files.values());
        trees.add(shared);
        for (Tree tree : trees) {
            for (int l = 0; l < tree.levels.size(); l++) {
                while (available(tree, l) >= fanIn && inflight < threads) {
                    startMerge(tree, l, merges);
                }
            }
        }
    }

    private static int available(Tree tree, int level) {
        int count = 0;
        for (Path file : tree.levels.get(level)) {
            if (!tree.busy.contains(file)) {
                count++;
            }
        }
        return count;
    }

    private void startMerge(Tree tree, int level, ExecutorService merges) {
        List<Path> inputs = new ArrayList<>();
        for (Path file : tree.levels.get(level)) {
            if (inputs.size() == fanIn) {
                break;
            }
            if (!tree.busy.contains(file)) {
                inputs.add(file);
            }
        }
        tree.busy.addAll(inputs);
        tree.inflight++;
        inflight++;
        Path combined = runsDir.resolve("merged-" + UUID.randomUUID() + ".parquet");
        merges.submit(() -> {
            try {
                IriMerger.merge(inputs, combined, rowGroupRows);
                events.add(new MergeDone(tree, level, combined, inputs));
            } catch (Throwable t) {
                events.add(new MergeFailed(t));
            }
        });
    }

    /**
     * Merges the shared tree into the output, once no merge is in flight. Levels are merged bottom up. A level with a
     * single file moves up unchanged, and a level with more is merged into one file at the level above. Each step is
     * saved before its inputs are deleted. The last file is written to the output, and then the state is removed.
     */
    private long mergeShared() throws IOException {
        if (!files.isEmpty()) {
            throw new IllegalStateException("runs of files that were not committed or discarded: " + files.keySet());
        }
        while (shared.files() > 1) {
            int l = 0;
            while (shared.levels.get(l).isEmpty()) {
                l++;
            }
            List<Path> level = shared.levels.get(l);
            if (level.size() == 1) {
                shared.level(l + 1).add(level.remove(0));
                saveState();
            } else {
                Path combined = runsDir.resolve("merged-" + UUID.randomUUID() + ".parquet");
                IriMerger.merge(level, combined, rowGroupRows);
                List<Path> inputs = new ArrayList<>(level);
                level.clear();
                shared.level(l + 1).add(combined);
                saveState();
                deleteAll(inputs);
            }
        }
        List<Path> last = new ArrayList<>();
        for (List<Path> level : shared.levels) {
            last.addAll(level);
        }
        long rows = IriMerger.merge(last, output, rowGroupRows);
        IriMerger.verify(output, rows);
        if (store != null) {
            store.delete();
        }
        for (List<Path> level : shared.levels) {
            level.clear();
        }
        deleteAll(last);
        return rows;
    }

    private static void deleteAll(List<Path> files) {
        for (Path file : files) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                throw new IllegalStateException("cannot delete " + file, e);
            }
        }
    }
}

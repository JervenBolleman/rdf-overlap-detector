package swiss.sib.rdfoverlap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Merges the sorted run files of an extraction in the background, so that merging overlaps with parsing.
 *
 * <p>Runs are merged in a hierarchy. Whenever {@code fanIn} runs are waiting in one scope, the smallest {@code fanIn}
 * of them are merged into one larger run. That keeps the number of files small, and it keeps the number of open files
 * in any one merge at {@code fanIn}. Merges run on a pool of threads, so several can run at once. The scopes are:
 *
 * <ul>
 *   <li>Runs of one input file are merged among themselves, while the file is still being parsed. They stay in that
 *       file's scope, so a file that fails can be discarded without undoing any merge that other files depend on.</li>
 *   <li>When a file succeeds it is committed. Its runs move to the shared scope, which merges them the same way.</li>
 * </ul>
 *
 * <p>When extraction finishes, the shared scope is merged into one file, which is the dataset's sorted IRI file.
 *
 * <p>One coordinator thread owns all the state. It takes events in order: the caller's runs, commits, discards and
 * finish, and the completion of each merge. Merges themselves run on the pool, and touch only their own inputs and
 * their own output, so they need no locking. The number of threads defaults to one fewer than the cores, and to one
 * when there is a single core.
 */
final class RunPool {

    static final int DEFAULT_FAN_IN = 16;

    /**
     * The hard limit on files open at once, for the whole extraction. The limit is kept by capping the number of merges
     * in flight. A merge opens {@code fanIn} inputs and one output, and {@link #RESERVED_FILES} stay free for the
     * input being parsed and for everything else the process holds.
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

    /** The runs of one scope, and the merges of that scope that are in flight. Used only by the coordinator. */
    private static final class Scope {
        final List<Path> ready = new ArrayList<>();
        int inflight;
        boolean committed;
        boolean discarded;
    }

    private record Run(int file, Path path) {
    }

    private record Commit(int file) {
    }

    private record Discard(int file) {
    }

    private record Finish() {
    }

    private record Abort() {
    }

    private record MergeDone(Scope scope, Path output) {
    }

    private record MergeFailed(Throwable cause) {
    }

    private final Path runsDir;
    private final Path output;
    private final int fanIn;
    private final int rowGroupRows;
    private final int threads;
    private final BlockingQueue<Object> events = new LinkedBlockingQueue<>();
    private final CompletableFuture<Long> result = new CompletableFuture<>();
    private final Thread coordinator;

    /** Scopes of files that have not yet committed or been discarded. Used only by the coordinator. */
    private final Map<Integer, Scope> files = new HashMap<>();
    private final Scope shared = new Scope();
    private final AtomicLong names = new AtomicLong();
    private int inflight;
    private boolean finishing;
    private boolean aborting;

    RunPool(Path runsDir, Path output, int fanIn, int rowGroupRows) {
        this(runsDir, output, fanIn, rowGroupRows, defaultThreads());
    }

    RunPool(Path runsDir, Path output, int fanIn, int rowGroupRows, int threads) {
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

    /** Says that an input file parsed successfully, so its runs join the shared scope. */
    void commit(int file) throws IOException {
        submit(new Commit(file));
    }

    /** Says that an input file failed, so its runs are deleted once any merge of them has finished. */
    void discard(int file) throws IOException {
        submit(new Discard(file));
    }

    /**
     * Merges the shared runs into the output, and returns the number of distinct IRIs in it.
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

    /** Stops the pool, and waits until its merges in flight have finished. Used when extraction has failed. */
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
        } catch (java.util.concurrent.CompletionException e) {
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
                    scopeOf(run.file()).ready.add(run.path());
                    schedule(merges);
                } else if (event instanceof Commit commit) {
                    Scope scope = files.remove(commit.file());
                    if (scope != null) {
                        scope.committed = true;
                        settle(scope);
                    }
                    schedule(merges);
                } else if (event instanceof Discard discard) {
                    Scope scope = files.remove(discard.file());
                    if (scope != null) {
                        scope.discarded = true;
                        settle(scope);
                    }
                } else if (event instanceof MergeDone done) {
                    inflight--;
                    done.scope().inflight--;
                    if (aborting) {
                        done.scope().ready.add(done.output());
                        if (inflight == 0) {
                            result.completeExceptionally(new IOException("extraction was aborted"));
                            return;
                        }
                        continue;
                    }
                    done.scope().ready.add(done.output());
                    settle(done.scope());
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

    private Scope scopeOf(int file) {
        return files.computeIfAbsent(file, k -> new Scope());
    }

    /**
     * Acts on a scope whose merges have all finished. A committed scope hands its runs to the shared scope, and a
     * discarded scope deletes them. Until then the scope waits, so that no file is deleted while a merge reads it.
     */
    private void settle(Scope scope) {
        if (scope.inflight > 0) {
            return;
        }
        if (scope.discarded) {
            deleteAll(scope.ready);
            scope.ready.clear();
        } else if (scope.committed && scope != shared) {
            shared.ready.addAll(scope.ready);
            scope.ready.clear();
        }
    }

    /** Starts merges while there are free threads and a scope has {@code fanIn} runs waiting. */
    private void schedule(ExecutorService merges) {
        if (aborting) {
            return;
        }
        boolean started = true;
        while (started && inflight < threads) {
            started = false;
            for (Scope scope : candidates()) {
                if (scope.ready.size() >= fanIn && inflight < threads) {
                    startMerge(scope, merges);
                    started = true;
                }
            }
        }
    }

    private List<Scope> candidates() {
        List<Scope> scopes = new ArrayList<>(files.values());
        scopes.add(shared);
        return scopes;
    }

    private void startMerge(Scope scope, ExecutorService merges) {
        List<Path> inputs = smallest(scope.ready, fanIn);
        scope.ready.removeAll(inputs);
        scope.inflight++;
        inflight++;
        Path combined = runsDir.resolve(String.format("merged-%06d.parquet", names.incrementAndGet()));
        merges.submit(() -> {
            try {
                IriMerger.merge(inputs, combined, rowGroupRows);
                deleteAll(inputs);
                events.add(new MergeDone(scope, combined));
            } catch (Throwable t) {
                events.add(new MergeFailed(t));
            }
        });
    }

    /** Merges the shared runs into the output, once no merge is in flight. */
    private long mergeShared() throws IOException {
        if (!files.isEmpty()) {
            throw new IllegalStateException("runs of files that were not committed or discarded: " + files.keySet());
        }
        // Every scope with fanIn runs has been merged, so the shared runs are fewer than fanIn.
        long rows = IriMerger.merge(shared.ready, output, rowGroupRows);
        deleteAll(shared.ready);
        shared.ready.clear();
        IriMerger.verify(output, rows);
        return rows;
    }

    private static List<Path> smallest(List<Path> files, int count) {
        List<Path> sorted = new ArrayList<>(files);
        sorted.sort(Comparator.comparingLong((Path p) -> sizeOrMax(p)).thenComparing(Path::toString));
        return new ArrayList<>(sorted.subList(0, count));
    }

    private static long sizeOrMax(Path p) {
        try {
            return IriMerger.sizeOf(p);
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
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

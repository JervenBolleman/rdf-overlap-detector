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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Merges the sorted run files of an extraction in a background thread, so that merging overlaps with parsing.
 *
 * <p>Runs are merged in a hierarchy. Whenever {@code fanIn} runs are waiting, the smallest {@code fanIn} are merged
 * into one larger run. That keeps the number of files small, and it keeps the number of open files in any one merge
 * at {@code fanIn}. The merges happen in two scopes:
 *
 * <ul>
 *   <li>Runs of one input file are merged among themselves, while the file is still being parsed. They stay in that
 *       file's scope, so a file that fails can be discarded without undoing any merge that other files depend on.</li>
 *   <li>When a file succeeds it is committed. Its runs move to the shared pool, which merges them the same way.</li>
 * </ul>
 *
 * <p>When extraction finishes, the shared pool is merged into one file, which is the dataset's sorted IRI file.
 *
 * <p>All merging happens on the worker thread. The caller sends it runs, commits and discards in order, so the
 * worker sees each file's runs before that file's commit.
 */
final class RunPool {

    static final int DEFAULT_FAN_IN = 16;

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

    private final Path runsDir;
    private final Path output;
    private final int fanIn;
    private final int rowGroupRows;
    private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
    private final CompletableFuture<Long> result = new CompletableFuture<>();
    private final Thread worker;

    /** Runs of files that have not yet committed or been discarded, by file index. Used only by the worker. */
    private final Map<Integer, List<Path>> pending = new HashMap<>();
    /** Runs of committed files, waiting for the shared merge. Used only by the worker. */
    private final List<Path> shared = new ArrayList<>();
    private final AtomicLong merged = new AtomicLong();

    RunPool(Path runsDir, Path output, int fanIn, int rowGroupRows) {
        if (fanIn < 2) {
            throw new IllegalArgumentException("fanIn must be at least 2: " + fanIn);
        }
        this.runsDir = runsDir;
        this.output = output;
        this.fanIn = fanIn;
        this.rowGroupRows = rowGroupRows;
        this.worker = new Thread(this::work, "iri-run-merger");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /** Hands a run written for an input file to the worker. */
    void run(int file, Path path) throws IOException {
        submit(new Run(file, path));
    }

    /** Says that an input file parsed successfully, so its runs join the shared pool. */
    void commit(int file) throws IOException {
        submit(new Commit(file));
    }

    /** Says that an input file failed, so its runs are deleted. */
    void discard(int file) throws IOException {
        submit(new Discard(file));
    }

    /**
     * Merges the shared pool into the output, and returns the number of distinct IRIs in it.
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

    /** Stops the worker without merging anything, and waits for it to stop. Used when extraction has failed. */
    void abort() {
        queue.add(new Abort());
        try {
            worker.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void submit(Object message) throws IOException {
        if (result.isCompletedExceptionally()) {
            throw failure(cause());
        }
        queue.add(message);
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

    private void work() {
        try {
            while (true) {
                Object message = queue.take();
                if (message instanceof Run run) {
                    List<Path> files = pending.computeIfAbsent(run.file(), k -> new ArrayList<>());
                    files.add(run.path());
                    reduce(files);
                } else if (message instanceof Commit commit) {
                    List<Path> files = pending.remove(commit.file());
                    if (files != null) {
                        shared.addAll(files);
                    }
                    reduce(shared);
                } else if (message instanceof Discard discard) {
                    deleteAll(pending.remove(discard.file()));
                } else if (message instanceof Finish) {
                    result.complete(mergeShared());
                    return;
                } else if (message instanceof Abort) {
                    result.completeExceptionally(new IOException("extraction was aborted"));
                    return;
                }
            }
        } catch (Throwable t) {
            result.completeExceptionally(t);
        }
    }

    /** Merges the smallest runs in a list, while the list holds {@code fanIn} or more. */
    private void reduce(List<Path> files) throws IOException {
        while (files.size() >= fanIn) {
            List<Path> smallest = smallest(files, fanIn);
            Path combined = runsDir.resolve(String.format("merged-%06d.parquet", merged.incrementAndGet()));
            IriMerger.merge(smallest, combined, rowGroupRows);
            files.removeAll(smallest);
            deleteAll(smallest);
            files.add(combined);
        }
    }

    /** Merges the shared pool into the output, on the worker thread. */
    private long mergeShared() throws IOException {
        if (!pending.isEmpty()) {
            throw new IllegalStateException("runs of files that were not committed or discarded: " + pending.keySet());
        }
        // Each commit reduces the shared pool below fanIn, so the final merge opens at most fanIn - 1 inputs.
        long rows = IriMerger.merge(shared, output, rowGroupRows);
        deleteAll(shared);
        shared.clear();
        IriMerger.verify(output, rows);
        return rows;
    }

    private static List<Path> smallest(List<Path> files, int count) throws IOException {
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

    private static void deleteAll(List<Path> files) throws IOException {
        if (files == null) {
            return;
        }
        for (Path file : files) {
            Files.deleteIfExists(file);
        }
    }
}

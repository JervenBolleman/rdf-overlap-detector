package swiss.sib.rdfoverlap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IriMergerTest {

    @TempDir
    Path dir;

    /** Writes one sorted run file with the given IRIs. */
    private static Path run(Path file, String... iris) throws IOException {
        Files.createDirectories(file.getParent());
        List<String> sorted = new ArrayList<>(List.of(iris));
        sorted.sort(null);
        try (IriParquetWriter writer = new IriParquetWriter(file)) {
            for (String iri : sorted) {
                writer.write(IriParts.parse(iri));
            }
            writer.endRowGroup();
        }
        return file;
    }

    private static List<String> readIris(Path file) throws IOException {
        List<String> iris = new ArrayList<>();
        IriParquetReader.forEach(file, r -> iris.add(r.toIri()));
        return iris;
    }

    /** A completion record for a test file, which the pool keeps only when it has a state to save to. */
    private static ManifestStore.CompletedFile done(int file) {
        return new ManifestStore.CompletedFile("file-" + file, 0, 0, 0, "pred-" + file + ".parquet");
    }

    private static int filesIn(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return 0;
        }
        try (var files = Files.list(dir)) {
            return (int) files.count();
        }
    }

    @Test
    void mergeProducesOneSortedDeduplicatedFile() throws IOException {
        Path a = run(dir.resolve("a.parquet"), "http://ex/b", "http://ex/d", "http://ex/é");
        Path b = run(dir.resolve("b.parquet"), "http://ex/a", "http://ex/b", "http://ex/z");
        Path c = run(dir.resolve("c.parquet"), "http://ex/b", "http://ex/c");
        Path out = dir.resolve("out.parquet");

        long rows = IriMerger.merge(List.of(a, b, c), out, 1_000_000);
        IriMerger.verify(out, rows);

        assertEquals(List.of("http://ex/a", "http://ex/b", "http://ex/c", "http://ex/d", "http://ex/z",
                "http://ex/é"), readIris(out));
        assertEquals(6, rows);
    }

    @Test
    void outputRowGroupsFollowTheConfiguredSize() throws IOException {
        List<String> iris = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            iris.add("http://ex/" + i);
        }
        Path a = run(dir.resolve("a.parquet"), iris.toArray(String[]::new));
        Path out = dir.resolve("out.parquet");

        IriMerger.merge(List.of(a), out, 3);

        assertEquals(4, IriParquetReader.rowGroupCount(out));
    }

    @Test
    void poolMergesHierarchicallyAndKeepsOnlyTheOutput() throws IOException {
        Path runs = dir.resolve("runs");
        Path out = dir.resolve("iris.parquet");
        RunPool pool = new RunPool(runs, out, 2, 1_000_000);

        pool.run(1, run(runs.resolve("f1-1.parquet"), "http://ex/a"));
        pool.run(1, run(runs.resolve("f1-2.parquet"), "http://ex/c"));
        pool.run(1, run(runs.resolve("f1-3.parquet"), "http://ex/e"));
        pool.commit(1, done(1));
        pool.run(2, run(runs.resolve("f2-1.parquet"), "http://ex/b", "http://ex/e"));
        pool.run(2, run(runs.resolve("f2-2.parquet"), "http://ex/d"));
        pool.commit(2, done(2));

        long distinct = pool.finish();

        assertEquals(5, distinct);
        assertEquals(List.of("http://ex/a", "http://ex/b", "http://ex/c", "http://ex/d", "http://ex/e"),
                readIris(out));
        assertEquals(0, filesIn(runs), "every run and intermediate merge is removed");
    }

    /** Several merges at once, on several threads, must give the same result as one at a time. */
    @Test
    void poolWithManyThreadsGivesTheSameResult() throws IOException {
        for (int threads : new int[] {1, 4}) {
            Path runs = dir.resolve("runs-" + threads);
            Path out = dir.resolve("iris-" + threads + ".parquet");
            RunPool pool = new RunPool(runs, out, 2, 1_000_000, threads);
            List<String> expected = new ArrayList<>();
            for (int file = 1; file <= 6; file++) {
                for (int run = 1; run <= 5; run++) {
                    String iri = "http://ex/" + file + "-" + run;
                    pool.run(file, run(runs.resolve("f" + file + "-" + run + ".parquet"), iri, "http://ex/shared"));
                    expected.add(iri);
                }
                pool.commit(file, done(file));
            }
            expected.add("http://ex/shared");
            expected.sort(null);

            assertEquals(expected.size(), pool.finish(), "threads " + threads);
            assertEquals(expected, readIris(out), "threads " + threads);
            assertEquals(0, filesIn(runs), "threads " + threads);
        }
    }

    /** However many threads are asked for, the merges in flight never open more than the hard limit of files. */
    @Test
    void mergesNeverOpenMoreThanTheHardLimit() throws IOException {
        RunPool pool = new RunPool(dir.resolve("runs"), dir.resolve("iris.parquet"), 16, 1_000_000, 10_000);
        try {
            assertEquals(RunPool.maxConcurrentMerges(16), pool.threads());
            assertTrue(pool.threads() * (16 + 1) + RunPool.RESERVED_FILES <= RunPool.MAX_OPEN_FILES);
            assertEquals(44, RunPool.maxConcurrentMerges(16));
        } finally {
            pool.abort();
        }
    }

    @Test
    void defaultThreadsIsOneFewerThanTheCoresOrOne() {
        int cores = Runtime.getRuntime().availableProcessors();
        assertEquals(cores > 1 ? cores - 1 : 1, RunPool.defaultThreads());
    }

    @Test
    void discardedFileLeavesNoRowsAndOthersAreUnaffected() throws IOException {
        Path runs = dir.resolve("runs");
        Path out = dir.resolve("iris.parquet");
        RunPool pool = new RunPool(runs, out, 2, 1_000_000);

        pool.run(1, run(runs.resolve("f1-1.parquet"), "http://ex/only-in-failed-file"));
        pool.run(1, run(runs.resolve("f1-2.parquet"), "http://ex/also-in-failed-file"));
        pool.discard(1);
        pool.run(2, run(runs.resolve("f2-1.parquet"), "http://ex/kept"));
        pool.commit(2, done(2));

        assertEquals(1, pool.finish());
        assertEquals(List.of("http://ex/kept"), readIris(out));
        assertEquals(0, filesIn(runs));
    }

    @Test
    void poolWithNoFilesGivesAnEmptyOutput() throws IOException {
        Path out = dir.resolve("iris.parquet");
        RunPool pool = new RunPool(dir.resolve("runs"), out, 2, 1_000_000);

        assertEquals(0, pool.finish());
        assertEquals(List.of(), readIris(out));
    }

    @Test
    void abortStopsTheWorkerWithoutMerging() throws IOException {
        Path runs = dir.resolve("runs");
        RunPool pool = new RunPool(runs, dir.resolve("iris.parquet"), 2, 1_000_000);
        pool.run(1, run(runs.resolve("f1-1.parquet"), "http://ex/a"));

        pool.abort();

        assertFalse(Files.exists(dir.resolve("iris.parquet")));
    }
}

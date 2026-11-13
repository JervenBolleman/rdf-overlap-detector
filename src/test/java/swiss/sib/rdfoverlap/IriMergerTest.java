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

    /** Writes one run file with the given IRIs, sorted as the extractor would. */
    private static void writeRun(Path file, String... iris) throws IOException {
        Files.createDirectories(file.getParent());
        List<String> sorted = new ArrayList<>(List.of(iris));
        sorted.sort(null);
        try (IriParquetWriter writer = new IriParquetWriter(file)) {
            for (String iri : sorted) {
                writer.write(IriRecord.of(iri));
            }
            writer.endRowGroup();
        }
    }

    private static List<String> readIris(Path file) throws IOException {
        List<String> iris = new ArrayList<>();
        IriParquetReader.forEach(file, r -> iris.add(r.iri()));
        return iris;
    }

    @Test
    void mergesRunsIntoOneSortedDeduplicatedFile() throws IOException {
        Path ds = dir.resolve("ds");
        writeRun(ds.resolve("runs/run-000001.parquet"), "http://ex/b", "http://ex/d", "http://ex/é");
        writeRun(ds.resolve("runs/run-000002.parquet"), "http://ex/a", "http://ex/b", "http://ex/z");
        writeRun(ds.resolve("runs/run-000003.parquet"), "http://ex/b", "http://ex/c");

        IriMerger.MergeSummary summary = new IriMerger(ds).sort();

        List<String> expected = List.of("http://ex/a", "http://ex/b", "http://ex/c", "http://ex/d",
                "http://ex/z", "http://ex/é");
        assertEquals(expected, readIris(summary.output()));
        assertEquals(expected.size(), summary.distinctIris());
        assertEquals(8, summary.runRows());
        assertEquals(0, summary.passes());
    }

    @Test
    void multiPassMergeGivesTheSameResult() throws IOException {
        Path ds = dir.resolve("multi");
        writeRun(ds.resolve("runs/run-000001.parquet"), "http://ex/1", "http://ex/6");
        writeRun(ds.resolve("runs/run-000002.parquet"), "http://ex/2", "http://ex/7");
        writeRun(ds.resolve("runs/run-000003.parquet"), "http://ex/3", "http://ex/8");
        writeRun(ds.resolve("runs/run-000004.parquet"), "http://ex/4", "http://ex/9");
        writeRun(ds.resolve("runs/run-000005.parquet"), "http://ex/5", "http://ex/1");

        // Five runs with fan-in 2: 5 -> 3 -> 2 -> final merge, so two intermediate passes.
        IriMerger.MergeSummary summary = new IriMerger(ds, 2, 1_000_000).sort();

        assertEquals(2, summary.passes());
        assertEquals(List.of("http://ex/1", "http://ex/2", "http://ex/3", "http://ex/4", "http://ex/5",
                "http://ex/6", "http://ex/7", "http://ex/8", "http://ex/9"), readIris(summary.output()));
    }

    @Test
    void outputRowGroupsFollowTheConfiguredSize() throws IOException {
        Path ds = dir.resolve("groups");
        List<String> iris = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            iris.add("http://ex/" + i);
        }
        writeRun(ds.resolve("runs/run-000001.parquet"), iris.toArray(String[]::new));

        IriMerger.MergeSummary summary = new IriMerger(ds, 64, 3).sort();

        assertEquals(10, summary.distinctIris());
        assertEquals(4, IriParquetReader.rowGroupCount(summary.output()));
    }

    @Test
    void sortRefusesADatasetThatWasNeverExtracted() throws IOException {
        Path ds = dir.resolve("never-extracted");
        Files.createDirectories(ds);

        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> new IriMerger(ds).sort());
        assertFalse(Files.exists(ds.resolve("iris")));
    }

    @Test
    void extractedDatasetWithNoIrisSortsToAnEmptyOutput() throws IOException {
        Path ds = dir.resolve("no-iris");
        Path input = dir.resolve("no-iris.nt");
        Files.writeString(input, "");
        new IriExtractor(ds).extract(input);

        IriMerger.MergeSummary summary = new IriMerger(ds).sort();

        assertEquals(0, summary.distinctIris());
        assertEquals(List.of(), readIris(summary.output()));
    }

    @Test
    void runsAreDeletedOnlyAfterTheOutputIsVerified() throws IOException {
        Path ds = dir.resolve("cleanup");
        writeRun(ds.resolve("runs/run-000001.parquet"), "http://ex/a");
        writeRun(ds.resolve("runs/run-000002.parquet"), "http://ex/a");

        IriMerger.MergeSummary summary = new IriMerger(ds).sort();

        assertTrue(Files.exists(summary.output()));
        assertFalse(Files.exists(ds.resolve("runs")));
    }

    @Test
    void extractThenSortGivesTheSampleIris() throws IOException {
        Path ds = dir.resolve("sample");
        new IriExtractor(ds, 2).extract(Path.of("src/test/resources/samples/sample.jsonld.zst"));

        IriMerger.MergeSummary summary = new IriMerger(ds).sort();

        assertEquals(List.of("http://example.org/o1", "http://example.org/o2",
                "http://example.org/s1", "http://example.org/s2"), readIris(summary.output()));
    }
}

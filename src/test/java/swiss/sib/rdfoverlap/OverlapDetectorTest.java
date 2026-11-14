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

class OverlapDetectorTest {

    @TempDir
    Path dir;

    /** Writes a dataset's sorted IRI file, as the sort phase would leave it. */
    private static Path dataset(Path root, String name, String... iris) throws IOException {
        Path irisFile = root.resolve(name).resolve("iris").resolve("iris.parquet");
        Files.createDirectories(irisFile.getParent());
        List<String> sorted = new ArrayList<>(List.of(iris));
        sorted.sort(null);
        try (IriParquetWriter writer = new IriParquetWriter(irisFile)) {
            for (String iri : sorted) {
                writer.write(IriRecord.of(iri));
            }
            writer.endRowGroup();
        }
        return root.resolve(name);
    }

    private static List<OverlapParquetReader.OverlapRow> rows(Path file) throws IOException {
        List<OverlapParquetReader.OverlapRow> rows = new ArrayList<>();
        OverlapParquetReader.forEach(file, rows::add);
        return rows;
    }

    @Test
    void sharedRangeIsReportedWithCountsAndSharedIris() throws IOException {
        Path root = dir.resolve("data");
        Path a = dataset(root, "a", "http://ex/a/1", "http://ex/a/2", "http://ex/b/1");
        Path b = dataset(root, "b", "http://ex/a/2", "http://ex/a/3", "http://ex/c/1");

        List<OverlapDetector.PairSummary> summaries =
                new OverlapDetector().detect(List.of(a, b), dir.resolve("out"));

        assertEquals(1, summaries.size());
        OverlapDetector.PairSummary s = summaries.get(0);
        assertEquals(2, s.rangesFirst());
        assertEquals(2, s.rangesSecond());
        assertEquals(1, s.sharedRanges());
        assertEquals(1, s.sharedIris());
        assertEquals(List.of(new OverlapParquetReader.OverlapRow("http://ex/a/", 2, 2, 1)), rows(s.output()));
    }

    @Test
    void rangePresentInOnlyOneDatasetHasNoRow() throws IOException {
        Path root = dir.resolve("data");
        Path a = dataset(root, "a", "http://ex/only/1");
        Path b = dataset(root, "b", "http://ex/other/1");

        OverlapDetector.PairSummary s = new OverlapDetector().detect(List.of(a, b), dir.resolve("out")).get(0);

        assertEquals(0, s.sharedRanges());
        assertEquals(List.of(), rows(s.output()));
    }

    @Test
    void sameRangeWithoutSharedIrisIsStillAnOverlapRange() throws IOException {
        Path root = dir.resolve("data");
        Path a = dataset(root, "a", "http://ex/a/1");
        Path b = dataset(root, "b", "http://ex/a/2");

        OverlapDetector.PairSummary s = new OverlapDetector().detect(List.of(a, b), dir.resolve("out")).get(0);

        assertEquals(1, s.sharedRanges());
        assertEquals(0, s.sharedIris());
        assertEquals(List.of(new OverlapParquetReader.OverlapRow("http://ex/a/", 1, 1, 0)), rows(s.output()));
    }

    @Test
    void everyPairIsComparedAndSummarised() throws IOException {
        Path root = dir.resolve("data");
        Path a = dataset(root, "a", "http://ex/x/1");
        Path b = dataset(root, "b", "http://ex/x/1");
        Path c = dataset(root, "c", "http://ex/x/2");

        List<OverlapDetector.PairSummary> summaries =
                new OverlapDetector().detect(List.of(a, b, c), dir.resolve("out"));

        assertEquals(3, summaries.size());
        assertEquals("a", summaries.get(0).first());
        assertEquals("c", summaries.get(2).second());
        String csv = Files.readString(dir.resolve("out").resolve("summary.csv"));
        assertTrue(csv.startsWith("first,second,ranges_first"));
        assertEquals(4, csv.lines().count());
    }

    @Test
    void depthChangesTheRanges() throws IOException {
        Path root = dir.resolve("data");
        Path a = dataset(root, "a", "http://ex/a/b/1");
        Path b = dataset(root, "b", "http://ex/a/c/1");

        OverlapDetector.PairSummary two = new OverlapDetector(2).detect(List.of(a, b), dir.resolve("two")).get(0);
        OverlapDetector.PairSummary one = new OverlapDetector(1).detect(List.of(a, b), dir.resolve("one")).get(0);

        assertEquals(0, two.sharedRanges(), "a/b/ and a/c/ differ at depth 2");
        assertEquals(1, one.sharedRanges(), "both are in http://ex/a/ at depth 1");
    }

    /**
     * Ranges nest, so the IRIs of one range are not contiguous in sorted order. The counts must still match a
     * direct grouping by range key.
     */
    @Test
    void countsMatchDirectGroupingEvenWhenRangesInterleave() throws IOException {
        List<String> iris = List.of(
                "http://h/a", "http://h/a/", "http://h/a/a/1", "http://h/a/a/2", "http://h/a/b",
                "http://h/a/b-c/d", "http://h/a/b/", "http://h/a/b/c", "http://h/a/b/d", "http://h/a/x",
                "http://h/c/1", "urn:x:1");
        Path a = dataset(dir.resolve("data"), "a", iris.toArray(String[]::new));

        java.util.Map<RangeKey.Range, Long> expected = new java.util.TreeMap<>();
        for (String iri : iris) {
            expected.merge(RangeKey.of(IriParts.parse(iri), 2), 1L, Long::sum);
        }

        assertEquals(expected, new OverlapDetector(2).rangeCounts(a.resolve("iris").resolve("iris.parquet")));
    }

    @Test
    void needsAtLeastTwoDatasetsWithDistinctNames() throws IOException {
        Path root = dir.resolve("data");
        Path a = dataset(root, "a", "http://ex/1");
        Path sameName = dataset(dir.resolve("other"), "a", "http://ex/1");

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new OverlapDetector().detect(List.of(a), dir.resolve("out")));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new OverlapDetector().detect(List.of(a, sameName), dir.resolve("out2")));
        assertFalse(Files.exists(dir.resolve("out").resolve("summary.csv")));
    }
}

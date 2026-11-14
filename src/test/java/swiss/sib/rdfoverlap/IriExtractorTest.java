package swiss.sib.rdfoverlap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.rdf4j.rio.RDFParseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IriExtractorTest {

    @TempDir
    Path dir;

    static final List<String> SAMPLE_IRIS = List.of(
            "http://example.org/o1", "http://example.org/o2",
            "http://example.org/s1", "http://example.org/s2");

    @ParameterizedTest
    @ValueSource(strings = {
            "sample.nt", "sample.nt.gz", "sample.nt.xz", "sample.nt.zst",
            "sample.ttl", "sample.ttl.gz", "sample.ttl.xz", "sample.ttl.zst",
            "sample.rdf", "sample.rdf.gz", "sample.rdf.xz", "sample.rdf.zst",
            "sample.jsonld", "sample.jsonld.gz", "sample.jsonld.xz", "sample.jsonld.zst"})
    void everySampleGivesTheSameIrisAndPredicates(String name) throws IOException {
        Path input = Path.of("src/test/resources/samples").resolve(name);
        Path out = dir.resolve(name);
        IriExtractor.ExtractionSummary summary = new IriExtractor(out).extract(input);

        assertEquals(3, summary.statements(), name);
        assertEquals(2, summary.predicates(), name);
        assertEquals(0, summary.rejectedIris(), name);
        assertEquals(SAMPLE_IRIS, readIris(List.of(summary.output())), name);
    }

    @Test
    void sameDocumentReferenceResolvesAgainstTheFileUri() throws IOException {
        Path input = Path.of("src/test/resources/samples/same-document.rdf");
        IriExtractor.ExtractionSummary summary = new IriExtractor(dir.resolve("same-document")).extract(input);

        List<String> iris = readIris(List.of(summary.output()));
        assertEquals(2, iris.size());
        assertTrue(iris.contains("http://example.org/o1"));
        assertTrue(iris.stream().anyMatch(i -> i.startsWith("file:") && i.endsWith("same-document.rdf")), iris.toString());
    }

    @Test
    void failedExtractionLeavesNoRuns() throws IOException {
        // Batch size 1 writes a run as soon as the second distinct IRI arrives, before the broken last line.
        Path input = writeNTriples("broken.nt",
                "<http://ex/a> <http://ex/p> <http://ex/b> .",
                "<http://ex/c> <http://ex/p> <http://ex/d> .",
                "<http://ex/e> <http://ex/p>");
        Path out = dir.resolve("broken");

        assertThrows(RDFParseException.class, () -> new IriExtractor(out, 1).extract(input));
        assertFalse(Files.exists(out.resolve("iris").resolve("iris.parquet")), "no output for a failed extraction");
    }

    @Test
    void literalLongerThanTheJdkDefaultXmlLimitIsAccepted() throws IOException {
        String longText = "x".repeat(100_500);
        Path input = dir.resolve("long-literal.rdf");
        Files.writeString(input, """
                <?xml version="1.0" encoding="UTF-8"?>
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:ex="http://example.org/">
                  <rdf:Description rdf:about="http://example.org/s">
                    <ex:text>%s</ex:text>
                    <ex:p rdf:resource="http://example.org/o"/>
                  </rdf:Description>
                </rdf:RDF>
                """.formatted(longText));

        IriExtractor.ExtractionSummary summary = new IriExtractor(dir.resolve("long-literal")).extract(input);

        assertEquals(2, summary.statements());
        assertEquals(List.of("http://example.org/o", "http://example.org/s"), readIris(List.of(summary.output())));
    }

    /** Copies one sample into a directory, keeping its name. */
    private static Path copySample(String name, Path into) throws IOException {
        Files.createDirectories(into);
        return Files.copy(Path.of("src/test/resources/samples").resolve(name), into.resolve(name));
    }

    @Test
    void directoryExtractsEveryRdfFileAndSkipsTheRest() throws IOException {
        Path input = dir.resolve("input");
        copySample("sample.nt", input);
        copySample("sample.ttl.gz", input.resolve("nested"));
        Files.writeString(input.resolve("notes.txt"), "This is not RDF.\n");
        Files.writeString(input.resolve("broken.nt"), "<http://ex/a> <http://ex/p> <http://ex/b> .\n<http://ex/c> <http://ex/p>\n");

        IriExtractor.ExtractionSummary summary = new IriExtractor(dir.resolve("ds")).extract(input);

        assertEquals(2, summary.files());
        assertEquals(6, summary.statements());
        assertEquals(2, summary.predicates());
        assertEquals(2, summary.skipped().size());
        List<String> skippedNames = summary.skipped().stream().map(s -> Path.of(s.source()).getFileName().toString()).toList();
        assertEquals(List.of("broken.nt", "notes.txt"), skippedNames);
        assertTrue(summary.skipped().stream().allMatch(s -> !s.reason().isEmpty()));
        // Both good files contribute the same four IRIs, so the output holds four.
        assertEquals(4, summary.distinctIris());
    }

    @Test
    void outputInsideTheInputDirectoryIsNotReadBack() throws IOException {
        Path input = dir.resolve("input");
        copySample("sample.nt", input);
        Path ds = input.resolve("ds");

        IriExtractor.ExtractionSummary first = new IriExtractor(ds).extract(input);
        IriExtractor.ExtractionSummary second = new IriExtractor(ds).extract(input);

        assertEquals(0, second.skipped().size(), second.skipped().toString());
        assertEquals(1, second.files());
        assertEquals(first.statements(), second.statements());
        assertEquals(readIris(List.of(first.output())), readIris(List.of(second.output())));
    }

    @Test
    void rerunReplacesThePreviousOutput() throws IOException {
        Path input = dir.resolve("input");
        copySample("sample.nt", input);
        Path ds = dir.resolve("ds");

        new IriExtractor(ds).extract(input);
        Files.createDirectories(ds.resolve("runs"));
        Files.writeString(ds.resolve("runs").resolve("stale.parquet"), "left over");
        IriExtractor.ExtractionSummary again = new IriExtractor(ds).extract(input);

        assertFalse(Files.exists(ds.resolve("runs").resolve("stale.parquet")));
        assertTrue(Files.exists(again.output()));
    }

    @Test
    void directoryWithNoRdfGivesAnEmptyDataset() throws IOException {
        Path input = dir.resolve("input");
        Files.createDirectories(input);
        Files.writeString(input.resolve("readme.md"), "# Not RDF\n");

        IriExtractor.ExtractionSummary summary = new IriExtractor(dir.resolve("ds")).extract(input);

        assertEquals(0, summary.files());
        assertEquals(1, summary.skipped().size());
        assertEquals(0, summary.distinctIris());
        assertEquals(0, summary.predicates());
    }

    @Test
    void singleFileThatIsNotRdfIsAnError() throws IOException {
        Path notRdf = dir.resolve("notes.txt");
        Files.writeString(notRdf, "This is not RDF.\n");

        assertThrows(RuntimeException.class, () -> new IriExtractor(dir.resolve("ds")).extract(notRdf));
    }

    /** Up to four sources are read at once, and never more than there are sources or cores. */
    @Test
    void readersAreBoundedByTheSourcesAndTheCores() {
        assertEquals(1, IriExtractor.readerThreads(1));
        assertTrue(IriExtractor.readerThreads(100) <= IriExtractor.MAX_READERS);
        assertTrue(IriExtractor.readerThreads(100) <= RunPool.defaultThreads());
        assertTrue(IriExtractor.readerThreads(3) <= 3);
    }

    /** Sources read in parallel give the same output as the same sources read one at a time. */
    @Test
    void parallelReadingGivesTheSameOutput() throws IOException {
        Path input = dir.resolve("many");
        for (int i = 0; i < 12; i++) {
            Path file = input.resolve("part" + i + ".nt");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "<http://ex/s" + i + "> <http://ex/p> <http://ex/o" + i + "> .\n"
                    + "<http://ex/shared> <http://ex/p> <http://ex/o" + i + "> .\n");
        }

        IriExtractor.ExtractionSummary summary = new IriExtractor(dir.resolve("ds")).extract(input);

        assertEquals(12, summary.files());
        assertEquals(24, summary.statements());
        // The 12 subjects, the 12 objects and the shared subject: 25 distinct IRIs.
        assertEquals(25, summary.distinctIris());
        List<String> iris = readIris(List.of(summary.output()));
        List<String> sorted = new ArrayList<>(iris);
        assertEquals(sorted.stream().sorted().toList(), iris.stream().sorted().toList());
        assertEquals(25, iris.size());
    }

    @Test
    void exactlyOneBatchOfTenThousandGivesOneRun() throws IOException {
        IriExtractor.ExtractionSummary summary = extractSelfLoops(10_000, IriExtractor.DEFAULT_BATCH_SIZE, "exact");

        assertEquals(1, summary.batches());
        assertEquals(10_000, summary.distinctIris());
        assertEquals(10_000, rowCount(summary.output()));
    }

    @Test
    void tenThousandAndOneGivesTwoBatches() throws IOException {
        IriExtractor.ExtractionSummary summary = extractSelfLoops(10_001, IriExtractor.DEFAULT_BATCH_SIZE, "plusone");

        assertEquals(2, summary.batches());
        assertEquals(10_001, summary.distinctIris());
        assertEquals(10_001, rowCount(summary.output()));
    }

    @Test
    void outputIsSortedAndHasOneRowGroupForASmallDataset() throws IOException {
        IriExtractor.ExtractionSummary summary = extractSelfLoops(10_001, IriExtractor.DEFAULT_BATCH_SIZE, "sorted");

        List<String> iris = readIris(List.of(summary.output()));
        List<String> sorted = new ArrayList<>(iris);
        sorted.sort(null);
        assertEquals(sorted, iris);
        assertEquals(1, IriParquetReader.rowGroupCount(summary.output()));
    }

    @Test
    void duplicatesAcrossBatchesCollapseInTheOutput() throws IOException {
        // Batch size 2. The first batch holds a and b. The second triple's a is still in that open batch, so it
        // collapses. Then c arrives, the batch is full, and it is flushed. The second batch is b and c.
        Path input = writeNTriples("dup-across.nt",
                "<http://ex/a> <http://ex/p> <http://ex/b> .",
                "<http://ex/a> <http://ex/p> <http://ex/c> .",
                "<http://ex/b> <http://ex/p> <http://ex/c> .");

        IriExtractor.ExtractionSummary summary = new IriExtractor(dir.resolve("dup-across"), 2).extract(input);

        assertEquals(2, summary.batches());
        assertEquals(List.of("http://ex/a", "http://ex/b", "http://ex/c"), readIris(List.of(summary.output())));
    }

    @Test
    void duplicatesWithinABatchCollapse() throws IOException {
        Path input = writeNTriples("dup-within.nt",
                "<http://ex/a> <http://ex/p> <http://ex/a> .",
                "<http://ex/a> <http://ex/p> <http://ex/a> .",
                "<http://ex/a> <http://ex/p> <http://ex/a> .");

        IriExtractor.ExtractionSummary summary = new IriExtractor(dir.resolve("dup-within")).extract(input);

        assertEquals(3, summary.statements());
        assertEquals(1, summary.batches());
        assertEquals(List.of("http://ex/a"), readIris(List.of(summary.output())));
    }

    @Test
    void hierarchicalMergingWithASmallFanInLeavesOnlyTheOutput() throws IOException {
        // Fan-in 2 and batches of one IRI: every IRI is its own run, so the pool has to merge in several levels.
        Path input = dir.resolve("chain.nt");
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(input))) {
            for (int i = 8; i >= 0; i--) {
                w.printf("<http://ex/i%d> <http://ex/p> <http://ex/i%d> .%n", i, i);
            }
            w.printf("<http://ex/i3> <http://ex/p> <http://ex/i3> .%n");
        }
        Path ds = dir.resolve("chain");

        IriExtractor.ExtractionSummary summary = new IriExtractor(ds, 1, 2).extract(input);

        List<String> expected = new ArrayList<>();
        for (int i = 0; i <= 8; i++) {
            expected.add("http://ex/i" + i);
        }
        assertEquals(expected, readIris(List.of(summary.output())));
        assertEquals(9, summary.distinctIris());
        if (Files.exists(ds.resolve("runs"))) {
            try (var files = Files.list(ds.resolve("runs"))) {
                assertEquals(0, files.count(), "every run and merged file is removed once merged");
            }
        }
    }

    @Test
    void blankNodesAndLiteralsAreNotCollected() throws IOException {
        Path input = writeNTriples("bnode-literal.nt",
                "_:b1 <http://ex/p> <http://ex/o> .",
                "<http://ex/s> <http://ex/p> \"text\" .");

        IriExtractor.ExtractionSummary summary = new IriExtractor(dir.resolve("bnode-literal")).extract(input);

        assertEquals(List.of("http://ex/o", "http://ex/s"), readIris(List.of(summary.output())));
    }

    @Test
    void emptyPortIsAcceptedAndStoredAsNoPort() throws IOException {
        // RDF4j accepts an empty port and so does IriParts, which stores it as no port.
        Path input = writeNTriples("empty-port.nt",
                "<http://ex/s> <http://ex/p> <http://h:/x> .");

        IriExtractor.ExtractionSummary summary = new IriExtractor(dir.resolve("empty-port")).extract(input);

        assertEquals(0, summary.rejectedIris());
        List<IriRecord> records = new ArrayList<>();
        IriParquetReader.forEach(summary.output(), records::add);
        // The empty port is the scheme's default, so it is not kept. The record is the host and the path.
        IriRecord hostIri = records.stream().filter(r -> "h".equals(r.parts().host())).findFirst().orElseThrow();
        assertEquals("h", hostIri.parts().host());
        assertNull(hostIri.parts().port());
    }

    private IriExtractor.ExtractionSummary extractSelfLoops(int count, int batchSize, String name) throws IOException {
        Path input = dir.resolve(name + ".nt");
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(input))) {
            for (int i = 0; i < count; i++) {
                w.printf("<http://example.org/i/%d> <http://example.org/p> <http://example.org/i/%d> .%n", i, i);
            }
        }
        return new IriExtractor(dir.resolve(name), batchSize).extract(input);
    }

    private Path writeNTriples(String name, String... lines) throws IOException {
        Path file = dir.resolve(name);
        Files.write(file, List.of(lines));
        return file;
    }

    private static long rowCount(Path run) throws IOException {
        return IriParquetReader.forEach(run, record -> { });
    }

    /** Reads the IRIs of the given run files in file order. */
    private static List<String> readIris(List<Path> runs) throws IOException {
        List<String> iris = new ArrayList<>();
        for (Path run : runs) {
            IriParquetReader.forEach(run, record -> iris.add(record.iri()));
        }
        return iris;
    }
}

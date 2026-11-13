package swiss.sib.rdfoverlap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.RDFInputDispatcher;
import org.eclipse.rdf4j.rio.helpers.StatementCollector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Every sample file holds the same three triples, in one of four syntaxes, plain or compressed.
 * Parsing each one through RDFInputDispatcher must give those three triples.
 */
class SampleParsingTest {

    static final List<String> SAMPLES = List.of(
            "sample.nt", "sample.nt.gz", "sample.nt.xz", "sample.nt.zst",
            "sample.ttl", "sample.ttl.gz", "sample.ttl.xz", "sample.ttl.zst",
            "sample.rdf", "sample.rdf.gz", "sample.rdf.xz", "sample.rdf.zst",
            "sample.jsonld", "sample.jsonld.gz", "sample.jsonld.xz", "sample.jsonld.zst");

    @ParameterizedTest
    @ValueSource(strings = {
            "sample.nt", "sample.nt.gz", "sample.nt.xz", "sample.nt.zst",
            "sample.ttl", "sample.ttl.gz", "sample.ttl.xz", "sample.ttl.zst",
            "sample.rdf", "sample.rdf.gz", "sample.rdf.xz", "sample.rdf.zst",
            "sample.jsonld", "sample.jsonld.gz", "sample.jsonld.xz", "sample.jsonld.zst"})
    void parsesToThreeStatements(String name) throws IOException {
        Path file = Path.of("src/test/resources/samples").resolve(name);
        assertEquals(3, countStatements(file), name);
    }

    @Test
    void coversEveryFormatAndCompression() {
        assertEquals(16, SAMPLES.size());
    }

    static long countStatements(Path file) throws IOException {
        long[] count = {0};
        try (InputStream in = new FileInputStream(file.toFile())) {
            new RDFInputDispatcher(new org.eclipse.rdf4j.rio.ParserConfig()).dispatch(
                    in, file.getFileName().toString(), null, (input, sourceName, format) -> {
                        StatementCollector collector = new StatementCollector();
                        Rio.createParser(format).setRDFHandler(collector).parse(input, "http://example.org/");
                        count[0] += collector.getStatements().size();
                    });
        }
        return count[0];
    }
}

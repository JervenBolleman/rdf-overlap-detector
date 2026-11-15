package swiss.sib.rdfoverlap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IriParquetRoundTripTest {

    @TempDir
    Path dir;

    static final List<String> TRICKY = List.of(
            "http://example.org/a",
            "http://example.org/a?",
            "http://example.org/a?#",
            "http://example.org/a?q=1#frag",
            "https://user:pw@host:8443/x",
            "http://[2001:db8::1]:8080/",
            "file:///etc/hosts",
            "urn:example:animal",
            "http://例子.example/路径?q=é#ф",
            "http://h/a%20b%2Fc?q=%7E");

    @Test
    void recordsSurviveTheRoundTrip() throws IOException {
        Path file = dir.resolve("iris.parquet");
        List<IriParts> written = new ArrayList<>();
        try (IriParquetWriter writer = new IriParquetWriter(file)) {
            for (String iri : TRICKY) {
                IriParts parts = IriParts.parse(iri);
                written.add(parts);
                writer.write(parts);
            }
            writer.endRowGroup();
        }

        List<IriParts> read = new ArrayList<>();
        long count = IriParquetReader.forEach(file, read::add);

        assertEquals(written.size(), count);
        assertEquals(written, read);
    }

    @Test
    void rowGroupsFollowTheBatchBoundaries() throws IOException {
        Path file = dir.resolve("batches.parquet");
        int total = 25_000;
        int batch = 10_000;
        try (IriParquetWriter writer = new IriParquetWriter(file)) {
            for (int i = 0; i < total; i++) {
                writer.write(IriParts.parse("http://example.org/item/" + i));
                if ((i + 1) % batch == 0) {
                    writer.endRowGroup();
                }
            }
            writer.endRowGroup();
        }

        assertEquals(3, IriParquetReader.rowGroupCount(file));
        assertEquals(total, IriParquetReader.forEach(file, record -> { }));
    }
}

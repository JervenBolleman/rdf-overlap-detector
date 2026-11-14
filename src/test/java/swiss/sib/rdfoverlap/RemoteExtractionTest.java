package swiss.sib.rdfoverlap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Serves files from a local HTTP server, so the remote and Metalink paths run without the internet. */
class RemoteExtractionTest {

    @TempDir
    Path dir;

    private HttpServer server;
    private String base;
    private final Map<String, byte[]> routes = new ConcurrentHashMap<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = routes.get(exchange.getRequestURI().getPath());
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static byte[] sample(String name) throws IOException {
        return Files.readAllBytes(Path.of("src/test/resources/samples").resolve(name));
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private List<String> sourceNames(IriExtractor.ExtractionSummary summary) {
        List<String> names = new ArrayList<>();
        summary.skipped().forEach(s -> names.add(s.source()));
        return names;
    }

    @Test
    void singleRemoteFileIsExtracted() throws Exception {
        routes.put("/data/sample.nt", sample("sample.nt"));

        IriExtractor.ExtractionSummary summary =
                new IriExtractor(dir.resolve("ds")).extract(base + "/data/sample.nt");

        assertEquals(1, summary.files());
        assertEquals(3, summary.statements());
        assertEquals(2, summary.predicates());
    }

    @Test
    void compressedRemoteFileIsDecompressedByItsName() throws Exception {
        routes.put("/data/sample.ttl.gz", sample("sample.ttl.gz"));

        IriExtractor.ExtractionSummary summary =
                new IriExtractor(dir.resolve("ds")).extract(base + "/data/sample.ttl.gz");

        assertEquals(3, summary.statements());
    }

    @Test
    void singleRemoteFileThatFailsIsAnError() {
        assertThrows(IOException.class,
                () -> new IriExtractor(dir.resolve("ds")).extract(base + "/data/missing.nt"));
    }

    @Test
    void metalink4ListsFilesWithChecksumsMirrorsAndRelativeUrls() throws Exception {
        byte[] nt = sample("sample.nt");
        byte[] ttlGz = sample("sample.ttl.gz");
        routes.put("/data/sample.nt", nt);
        routes.put("/data/sample.ttl.gz", ttlGz);
        routes.put("/data/bad.nt", "<http://ex/a> <http://ex/p>\n".getBytes(StandardCharsets.UTF_8));
        String metalink = """
                <?xml version="1.0" encoding="UTF-8"?>
                <metalink xmlns="urn:ietf:params:xml:ns:metalink">
                  <file name="a.nt">
                    <hash type="sha-256">%1$s</hash>
                    <url priority="2">%2$s/unused/sample.nt</url>
                    <url priority="1">%2$s/data/sample.nt</url>
                  </file>
                  <file name="b.ttl.gz">
                    <hash type="sha-256">%3$s</hash>
                    <url>%2$s/data/sample.ttl.gz</url>
                  </file>
                  <file name="c.nt">
                    <hash type="sha-256">0000000000000000000000000000000000000000000000000000000000000000</hash>
                    <url>%2$s/data/sample.nt</url>
                  </file>
                  <file name="d.nt">
                    <url>%2$s/data/missing.nt</url>
                  </file>
                  <file name="bad.nt">
                    <url>%2$s/data/bad.nt</url>
                  </file>
                  <file name="e.nt">
                    <url>../data/sample.nt</url>
                  </file>
                </metalink>
                """.formatted(sha256(nt), base, sha256(ttlGz));
        routes.put("/meta/list.meta4", metalink.getBytes(StandardCharsets.UTF_8));

        IriExtractor.ExtractionSummary summary =
                new IriExtractor(dir.resolve("ds")).extract(base + "/meta/list.meta4");

        // a and e name the same file, but only a expects a checksum, so they are different entries and both are read.
        assertEquals(3, summary.files(), "a, b and e are extracted");
        assertEquals(9, summary.statements());
        List<String> skipped = sourceNames(summary);
        assertEquals(List.of("c.nt", "d.nt", "bad.nt"), skipped);
        String checksumReason = summary.skipped().get(0).reason();
        assertTrue(checksumReason.contains("checksum mismatch"), checksumReason);
        assertTrue(summary.skipped().get(1).reason().contains("HTTP 404"), summary.skipped().get(1).reason());
    }

    @Test
    void metalink3ListsFilesWithVerificationHashes() throws Exception {
        byte[] nt = sample("sample.nt");
        routes.put("/data/sample.nt", nt);
        String metalink = """
                <?xml version="1.0" encoding="UTF-8"?>
                <metalink version="3.0" xmlns="http://www.metalinker.org/">
                  <files>
                    <file name="sample.nt">
                      <resources>
                        <url type="http" preference="100">%1$s/data/sample.nt</url>
                      </resources>
                      <verification>
                        <hash type="sha256">%2$s</hash>
                      </verification>
                    </file>
                  </files>
                </metalink>
                """.formatted(base, sha256(nt));
        routes.put("/meta/list.metalink", metalink.getBytes(StandardCharsets.UTF_8));

        IriExtractor.ExtractionSummary summary =
                new IriExtractor(dir.resolve("ds")).extract(base + "/meta/list.metalink");

        assertEquals(1, summary.files());
        assertEquals(3, summary.statements());
        assertEquals(List.of(), sourceNames(summary));
    }

    @Test
    void localMetalinkFileResolvesItsRelativeUrlsAgainstItself() throws Exception {
        byte[] nt = sample("sample.nt");
        routes.put("/data/sample.nt", nt);
        String metalink = """
                <?xml version="1.0" encoding="UTF-8"?>
                <metalink xmlns="urn:ietf:params:xml:ns:metalink">
                  <file name="a.nt"><url>%s/data/sample.nt</url></file>
                </metalink>
                """.formatted(base);
        Path file = dir.resolve("list.meta4");
        Files.writeString(file, metalink);

        IriExtractor.ExtractionSummary summary = new IriExtractor(dir.resolve("ds")).extract(file);

        assertEquals(1, summary.files());
        assertEquals(3, summary.statements());
    }

    @Test
    void documentThatIsNotMetalinkIsAnError() throws Exception {
        Path file = dir.resolve("list.meta4");
        Files.writeString(file, "<root/>");

        assertThrows(IOException.class, () -> new IriExtractor(dir.resolve("ds")).extract(file));
    }
}

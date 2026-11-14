package swiss.sib.rdfoverlap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Runs the ssh path with a stand-in ssh client. The stand-in drops the ssh options and host, then runs the remote
 * command in the local shell, so the quoting and the streaming are exercised without a server.
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
class SshExtractionTest {

    @TempDir
    Path dir;

    @BeforeEach
    void useStandInSsh() throws IOException {
        Path script = dir.resolve("fake-ssh");
        Files.writeString(script, """
                #!/bin/sh
                for last; do :; done
                eval "$last"
                """);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        System.setProperty("rdfoverlap.ssh", script.toString());
    }

    @AfterEach
    void restoreSsh() {
        System.clearProperty("rdfoverlap.ssh");
    }

    /** An ssh URL for an absolute local path, encoded so that spaces and quotes are allowed. */
    private static String sshUrl(String host, Path file) throws Exception {
        return new URI("ssh", host, file.toAbsolutePath().toString(), null).toString();
    }

    private static Path sample(String name, Path into) throws IOException {
        Files.createDirectories(into);
        return Files.copy(Path.of("src/test/resources/samples").resolve(name), into.resolve(name));
    }

    @Test
    void sshFileIsStreamedAndExtracted() throws Exception {
        Path file = sample("sample.nt", dir.resolve("remote"));

        IriExtractor.ExtractionSummary summary =
                new IriExtractor(dir.resolve("ds")).extract(sshUrl("localhost", file));

        assertEquals(1, summary.files());
        assertEquals(3, summary.statements());
        assertEquals(2, summary.predicates());
    }

    @Test
    void pathWithSpacesAndQuotesIsPassedLiterally() throws Exception {
        Path file = sample("sample.ttl", dir.resolve("it's here"));
        Path renamed = Files.move(file, file.resolveSibling("a file's name.ttl"));

        IriExtractor.ExtractionSummary summary =
                new IriExtractor(dir.resolve("ds")).extract(sshUrl("localhost", renamed));

        assertEquals(3, summary.statements());
    }

    @Test
    void missingRemoteFileIsAnErrorWithTheRemoteMessage() throws Exception {
        Path missing = dir.resolve("no-such-file.nt");

        IOException e = assertThrows(IOException.class,
                () -> new IriExtractor(dir.resolve("ds")).extract(sshUrl("localhost", missing)));

        assertTrue(e.getMessage().contains("ssh exited with status"), e.getMessage());
    }

    @Test
    void hostThatCouldBeAnSshOptionIsRejected() {
        IOException e = assertThrows(IOException.class,
                () -> new IriExtractor(dir.resolve("ds")).extract("ssh://-oProxyCommand=evil/data/x.nt"));

        assertTrue(e.getMessage().contains("invalid host"), e.getMessage());
    }

    @Test
    void sshUrlNeedsAnAbsolutePath() {
        IOException e = assertThrows(IOException.class,
                () -> new IriExtractor(dir.resolve("ds")).extract("ssh://localhost"));

        assertTrue(e.getMessage().contains("absolute path"), e.getMessage());
    }

    @Test
    void metalinkCanListSshFiles() throws Exception {
        Path good = sample("sample.nt", dir.resolve("remote"));
        Path missing = dir.resolve("remote").resolve("missing.nt");
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(good)));
        Path metalink = dir.resolve("list.meta4");
        Files.writeString(metalink, """
                <?xml version="1.0" encoding="UTF-8"?>
                <metalink xmlns="urn:ietf:params:xml:ns:metalink">
                  <file name="good.nt">
                    <hash type="sha-256">%1$s</hash>
                    <url>%2$s</url>
                  </file>
                  <file name="missing.nt">
                    <url>%3$s</url>
                  </file>
                </metalink>
                """.formatted(sha256, sshUrl("localhost", good), sshUrl("localhost", missing)));

        IriExtractor.ExtractionSummary summary = new IriExtractor(dir.resolve("ds")).extract(metalink);

        assertEquals(1, summary.files());
        assertEquals(3, summary.statements());
        assertEquals(1, summary.skipped().size());
        assertTrue(summary.skipped().get(0).reason().contains("ssh exited with status"),
                summary.skipped().get(0).reason());
    }
}

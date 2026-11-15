package swiss.sib.rdfoverlap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

class ResumeTest {

    @TempDir
    Path dir;

    /** Six files, each with two distinct IRIs of its own. */
    private Path input() throws IOException {
        Path input = dir.resolve("input");
        for (int i = 0; i < 6; i++) {
            Path file = input.resolve("part" + i + ".nt");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "<http://ex/s" + i + "> <http://ex/p> <http://ex/o" + i + "> .\n");
        }
        return input;
    }

    private static List<String> readIris(Path file) throws IOException {
        List<String> iris = new ArrayList<>();
        IriParquetReader.forEach(file, r -> iris.add(r.toIri()));
        return iris;
    }

    @Test
    void resumedExtractionGivesTheFullResult() throws IOException {
        Path input = input();
        Path ds = dir.resolve("ds");

        assertThrows(IllegalStateException.class, () -> new IriExtractor(ds).failAfter(3).extract(input));
        IriExtractor.ExtractionSummary summary = new IriExtractor(ds).extract(input, true);

        assertEquals(6, summary.files());
        assertEquals(6, summary.statements());
        assertEquals(12, summary.distinctIris());
        assertEquals(12, readIris(summary.output()).size());
        assertFalse(Files.exists(ds.resolve("state")), "the state is removed once the extraction finishes");
    }

    /** A file completed before the interruption is restored from the state, so it is not read again. */
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void completedFilesAreNotReadAgain() throws IOException {
        Path input = input();
        Path ds = dir.resolve("ds");
        Path first = input.resolve("part0.nt");

        assertThrows(IllegalStateException.class, () -> new IriExtractor(ds).failAfter(3).extract(input));
        Files.setPosixFilePermissions(first, PosixFilePermissions.fromString("---------"));
        try {
            IriExtractor.ExtractionSummary summary = new IriExtractor(ds).extract(input, true);

            assertEquals(0, summary.skipped().size(), "an unreadable completed file would have been skipped");
            assertEquals(6, summary.files());
            assertEquals(12, summary.distinctIris());
        } finally {
            Files.setPosixFilePermissions(first, PosixFilePermissions.fromString("rw-r--r--"));
        }
    }

    @Test
    void resumeWithNoSavedStateStartsAfresh() throws IOException {
        Path input = input();

        IriExtractor.ExtractionSummary summary = new IriExtractor(dir.resolve("ds")).extract(input, true);

        assertEquals(6, summary.files());
        assertEquals(12, summary.distinctIris());
    }

    @Test
    void damagedStateIsRefusedAndNamesTheMissingFile() throws IOException {
        Path input = input();
        Path ds = dir.resolve("ds");
        assertThrows(IllegalStateException.class, () -> new IriExtractor(ds).failAfter(3).extract(input));
        // Remove a file that the saved state names, to model a damaged state.
        ManifestStore.Manifest state = new ManifestStore(ds.resolve("state")).load();
        String named = state.sharedLevels().stream().flatMap(List::stream).findFirst().orElseThrow();
        Files.delete(ds.resolve("runs").resolve(named));

        IOException e = assertThrows(IOException.class, () -> new IriExtractor(ds).extract(input, true));

        assertTrue(e.getMessage().contains(named), e.getMessage());
        assertTrue(e.getMessage().contains("which is missing"), e.getMessage());
    }
}

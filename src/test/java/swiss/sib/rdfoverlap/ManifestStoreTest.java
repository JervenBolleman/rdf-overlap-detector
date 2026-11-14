package swiss.sib.rdfoverlap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManifestStoreTest {

    @TempDir
    Path dir;

    /** Keys with spaces, tabs and non-ASCII text survive a save and a load. */
    @Test
    void stateRoundTrips() throws Exception {
        ManifestStore store = new ManifestStore(dir.resolve("state"));
        ManifestStore.Manifest saved = new ManifestStore.Manifest(
                List.of(new ManifestStore.CompletedFile("file:/a b/\tc é.nt|12|345", 3, 1, 2, "pred-abc.parquet")),
                List.of(List.of("run-1.parquet", "run-2.parquet"), List.of("merged-x.parquet")));

        store.save(saved);

        assertEquals(saved, store.load());
    }

    @Test
    void missingStateIsEmpty() throws Exception {
        assertEquals(ManifestStore.Manifest.empty(), new ManifestStore(dir.resolve("none")).load());
    }
}

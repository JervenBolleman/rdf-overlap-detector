package swiss.sib.rdfoverlap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {

    private static final class Result {
        final int code;
        final String out;
        final String err;

        Result(int code, String out, String err) {
            this.code = code;
            this.out = out;
            this.err = err;
        }
    }

    private static Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(args,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void noArgumentsIsUsageError() {
        Result r = run();
        assertEquals(1, r.code);
        assertTrue(r.err.contains("usage:"));
    }

    @Test
    void helpPrintsUsageToStdout() {
        Result r = run("--help");
        assertEquals(0, r.code);
        assertTrue(r.out.contains("extract"));
        assertTrue(r.out.contains("overlap"));
    }

    @Test
    void unknownCommandIsUsageError() {
        Result r = run("frobnicate");
        assertEquals(1, r.code);
        assertTrue(r.err.contains("unknown command: frobnicate"));
    }

    @Test
    void overlapComparesExtractedAndSortedDatasets(@TempDir Path dir) {
        for (String name : new String[] {"a", "b"}) {
            assertEquals(0, run("extract", "src/test/resources/samples/sample.nt.xz", dir.resolve(name).toString()).code);
        }
        Result r = run("overlap", dir.resolve("out").toString(), dir.resolve("a").toString(), dir.resolve("b").toString());
        assertEquals(0, r.code, r.err);
        assertTrue(r.out.contains("sharedIris=4"), r.out);
        assertTrue(Files.exists(dir.resolve("out").resolve("a__b.parquet")));
        assertTrue(Files.exists(dir.resolve("out").resolve("void.ttl")));
        assertTrue(r.out.contains("void description:"), r.out);
    }

    @Test
    void overlapAcceptsAVoidBaseOption(@TempDir Path dir) {
        for (String name : new String[] {"a", "b"}) {
            assertEquals(0, run("extract", "src/test/resources/samples/sample.nt.xz", dir.resolve(name).toString()).code);
        }
        Result r = run("overlap", "--void-base=https://example.org/void/", dir.resolve("out").toString(),
                dir.resolve("a").toString(), dir.resolve("b").toString());
        assertEquals(0, r.code, r.err);
        assertTrue(r.out.contains("sharedIris=4"), r.out);
    }

    @Test
    void overlapNeedsTwoDatasets() {
        Result r = run("overlap", "out", "only-one");
        assertEquals(1, r.code);
        assertTrue(r.err.contains("usage: rdf-overlap-detector overlap"));
    }

    @Test
    void extractWritesRunsAndPredicates(@TempDir Path dir) {
        Result r = run("extract", "src/test/resources/samples/sample.nt.xz", dir.toString());
        assertEquals(0, r.code, r.err);
        assertTrue(r.out.contains("statements=3"), r.out);
        assertTrue(r.out.contains("predicates=2"), r.out);
        assertTrue(Files.exists(dir.resolve("predicates").resolve("predicates.parquet")));
    }

    @Test
    void extractReadsEveryRdfFileInADirectory(@TempDir Path dir) {
        Result r = run("extract", "src/test/resources/samples", dir.toString());
        assertEquals(0, r.code, r.err);
        assertTrue(r.out.contains("files=17"), r.out);
        assertTrue(r.out.contains("skipped=0"), r.out);
        assertTrue(Files.exists(dir.resolve("iris").resolve("iris.parquet")));
    }

    @Test
    void extractNeedsTwoArguments() {
        Result r = run("extract", "only-one-arg");
        assertEquals(1, r.code);
        assertTrue(r.err.contains("usage: rdf-overlap-detector extract"));
    }
}

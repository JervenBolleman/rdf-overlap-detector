package swiss.sib.rdfoverlap;

import java.io.IOException;
import org.eclipse.rdf4j.common.exception.RDF4JException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Command line entry point. Every subcommand is implemented.
 */
public final class Main {

    static final String USAGE = """
            usage: rdf-overlap-detector <command> [args]

            commands:
              extract   read RDF files and write one sorted, deduplicated IRI file for the dataset; input is a
                        file, a directory, an http(s) URL, an ssh URL or a Metalink file (.meta4, .metalink).
                        With --resume, continue an extraction that did not finish
              overlap   report IRI ranges that appear in two or more datasets, as VoID in void.ttl
            """;

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * Runs one subcommand and returns the process exit code.
     *
     * @param args   command line arguments
     * @param out    stream for normal output
     * @param err    stream for errors and usage
     * @return 0 on success, 1 for a usage error, 2 for a command that is not implemented yet
     */
    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            err.print(USAGE);
            return 1;
        }
        return switch (args[0]) {
            case "extract" -> extract(args, out, err);
            case "overlap" -> overlap(args, out, err);
            case "-h", "--help", "help" -> {
                out.print(USAGE);
                yield 0;
            }
            default -> {
                err.println("unknown command: " + args[0]);
                err.print(USAGE);
                yield 1;
            }
        };
    }

    /** overlap [--void-base=IRI] &lt;outDir&gt; &lt;datasetDir&gt;...: compares every pair of sorted datasets. */
    private static int overlap(String[] args, PrintStream out, PrintStream err) {
        String voidBase = OverlapDetector.DEFAULT_VOID_BASE;
        int first = 1;
        if (first < args.length && args[first].startsWith("--void-base=")) {
            voidBase = args[first].substring("--void-base=".length());
            first++;
        }
        if (args.length - first < 3) {
            err.println("usage: rdf-overlap-detector overlap [--void-base=IRI] <outDir> <datasetDir> <datasetDir> [more...]");
            return 1;
        }
        List<Path> datasets = new ArrayList<>();
        for (int i = first + 1; i < args.length; i++) {
            datasets.add(Path.of(args[i]));
        }
        Path outDir = Path.of(args[first]);
        try {
            for (OverlapDetector.PairSummary s : new OverlapDetector().detect(datasets, outDir, voidBase)) {
                out.printf("%s %s ranges=%d/%d sharedRanges=%d sharedIris=%d%n", s.first(), s.second(),
                        s.rangesFirst(), s.rangesSecond(), s.sharedRanges(), s.sharedIris());
            }
            out.println("void description: " + outDir.resolve("void.ttl"));
            return 0;
        } catch (IOException | IllegalArgumentException e) {
            err.println("overlap failed: " + e.getMessage());
            return 3;
        }
    }

    /** extract &lt;input&gt; &lt;outDir&gt;: extracts an RDF file, a directory, a URL or a Metalink file. */
    private static int extract(String[] args, PrintStream out, PrintStream err) {
        boolean resume = args.length > 1 && args[1].equals("--resume");
        int first = resume ? 2 : 1;
        if (args.length - first != 2) {
            err.println("usage: rdf-overlap-detector extract [--resume] <file|dir|url|metalink> <outDir>");
            return 1;
        }
        try {
            IriExtractor.ExtractionSummary summary =
                    new IriExtractor(Path.of(args[first + 1])).extract(args[first], resume);
            for (IriExtractor.Skipped skip : summary.skipped()) {
                err.println("skipped (not RDF): " + skip.source() + ": " + skip.reason());
            }
            out.printf("files=%d skipped=%d statements=%d batches=%d predicates=%d distinctIris=%d rejectedIris=%d%n",
                    summary.files(), summary.skipped().size(), summary.statements(), summary.batches(),
                    summary.predicates(), summary.distinctIris(), summary.rejectedIris());
            out.println("sorted IRIs: " + summary.output());
            return 0;
        } catch (IOException | RDF4JException e) {
            err.println("extract failed: " + e.getMessage());
            return 3;
        }
    }
}

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
              extract   write the IRIs of one RDF file, or of a directory of RDF files, as sorted runs
              sort      merge extracted batches into one sorted, deduplicated IRI file per dataset
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
            case "sort" -> sort(args, out, err);
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

    /** sort &lt;datasetDir&gt;: merges the run files of one dataset into its sorted IRI file. */
    private static int sort(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 2) {
            err.println("usage: rdf-overlap-detector sort <datasetDir>");
            return 1;
        }
        try {
            IriMerger.MergeSummary summary = new IriMerger(Path.of(args[1])).sort();
            out.printf("runRows=%d distinctIris=%d passes=%d output=%s%n",
                    summary.runRows(), summary.distinctIris(), summary.passes(), summary.output());
            return 0;
        } catch (IOException | IllegalStateException e) {
            err.println("sort failed: " + e.getMessage());
            return 3;
        }
    }

    /** extract &lt;input&gt; &lt;outDir&gt;: extracts one RDF file, or every RDF file under a directory. */
    private static int extract(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 3) {
            err.println("usage: rdf-overlap-detector extract <input> <outDir>");
            return 1;
        }
        try {
            IriExtractor.ExtractionSummary summary = new IriExtractor(Path.of(args[2])).extract(Path.of(args[1]));
            for (IriExtractor.Skipped skip : summary.skipped()) {
                err.println("skipped (not RDF): " + skip.file() + ": " + skip.reason());
            }
            out.printf("files=%d skipped=%d statements=%d runs=%d predicates=%d rejectedIris=%d%n",
                    summary.files(), summary.skipped().size(), summary.statements(), summary.runs().size(),
                    summary.predicates(), summary.rejectedIris());
            return 0;
        } catch (IOException | RDF4JException e) {
            err.println("extract failed: " + e.getMessage());
            return 3;
        }
    }
}

package swiss.sib.rdfoverlap;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Finds the IRI ranges that two or more datasets have in common, and describes the result as VoID.
 *
 * <p>Each dataset's sorted IRI file is read once to count the IRIs in each range, giving a sorted map from range
 * key to count. For every pair of datasets, the ranges that both maps contain are the overlapping ranges. A
 * streaming merge of the two sorted IRI files then counts the IRIs the pair shares in each range.
 *
 * <p>Output for a pair {@code A} and {@code B} goes to {@code <outDir>/A__B.parquet}, with one row per shared range
 * sorted by range key. A {@code summary.csv} in {@code outDir} has one line per pair. {@code void.ttl} describes
 * the datasets and the pairwise linksets in VoID, see {@link VoidWriter}.
 *
 * <p>The range maps are kept in memory, one entry per range and not per IRI. The number of ranges is usually far
 * smaller than the number of IRIs, so this is the expected bound.
 */
public final class OverlapDetector {

    public static final int DEFAULT_DEPTH = RangeKey.DEFAULT_DEPTH;

    /** Base for the VoID IRIs of datasets and linksets when none is given. */
    public static final String DEFAULT_VOID_BASE = "urn:rdf-overlap:";

    /** Dataset names become IRI path segments, so they are restricted to characters that need no escaping. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]+");

    /**
     * Summary of one pair of datasets.
     *
     * @param first        name of the first dataset, the directory name
     * @param second       name of the second dataset
     * @param rangesFirst  ranges the first dataset has
     * @param rangesSecond ranges the second dataset has
     * @param sharedRanges ranges both datasets have
     * @param sharedIris   IRIs both datasets have
     * @param output       the overlap file for this pair
     */
    public record PairSummary(String first, String second, int rangesFirst, int rangesSecond,
                              int sharedRanges, long sharedIris, Path output) {
    }

    /**
     * One range that two datasets share.
     *
     * @param range       the range key
     * @param countFirst  IRIs of the first dataset in the range
     * @param countSecond IRIs of the second dataset in the range
     * @param sharedIris  IRIs both datasets have in the range
     */
    record SharedRange(RangeKey.Range range, long countFirst, long countSecond, long sharedIris) {
    }

    /**
     * Everything the pair produced: its summary and the ranges it shares, sorted by range key.
     *
     * @param summary the pair summary
     * @param ranges  the shared ranges
     */
    record PairResult(PairSummary summary, List<SharedRange> ranges) {
    }

    private final int depth;

    public OverlapDetector() {
        this(DEFAULT_DEPTH);
    }

    public OverlapDetector(int depth) {
        if (depth < 0) {
            throw new IllegalArgumentException("depth must not be negative: " + depth);
        }
        this.depth = depth;
    }

    /**
     * Compares every pair of datasets, using the default VoID base.
     *
     * @param datasetDirs dataset directories, each holding {@code iris/iris.parquet} from the sort phase
     * @param outDir      directory for the pair files, the summary and the VoID description
     * @return one summary per pair, in dataset order
     * @throws IOException if a dataset cannot be read or the output cannot be written
     */
    public List<PairSummary> detect(List<Path> datasetDirs, Path outDir) throws IOException {
        return detect(datasetDirs, outDir, DEFAULT_VOID_BASE);
    }

    /**
     * Compares every pair of datasets.
     *
     * @param datasetDirs dataset directories, each holding {@code iris/iris.parquet} from the sort phase
     * @param outDir      directory for the pair files, the summary and the VoID description
     * @param voidBase    base IRI for the VoID dataset and linkset IRIs, such as {@code https://example.org/void/}
     * @return one summary per pair, in dataset order
     * @throws IOException if a dataset cannot be read or the output cannot be written
     */
    public List<PairSummary> detect(List<Path> datasetDirs, Path outDir, String voidBase) throws IOException {
        if (datasetDirs.size() < 2) {
            throw new IllegalArgumentException("need at least two datasets, got " + datasetDirs.size());
        }
        Files.createDirectories(outDir);

        List<String> names = new ArrayList<>();
        List<Path> irisFiles = new ArrayList<>();
        List<TreeMap<RangeKey.Range, Long>> ranges = new ArrayList<>();
        for (Path dir : datasetDirs) {
            String name = dir.getFileName().toString();
            if (!NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("dataset name must match [A-Za-z0-9._-]+: " + name);
            }
            if (names.contains(name)) {
                throw new IllegalArgumentException("two datasets are named " + name);
            }
            names.add(name);
            Path iris = dir.resolve("iris").resolve("iris.parquet");
            irisFiles.add(iris);
            ranges.add(rangeCounts(iris));
        }

        List<PairResult> results = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            for (int j = i + 1; j < names.size(); j++) {
                results.add(comparePair(names.get(i), names.get(j), ranges.get(i), ranges.get(j),
                        irisFiles.get(i), irisFiles.get(j), outDir));
            }
        }
        List<PairSummary> summaries = new ArrayList<>();
        for (PairResult r : results) {
            summaries.add(r.summary());
        }
        writeSummary(summaries, outDir.resolve("summary.csv"));

        List<Long> totals = new ArrayList<>();
        for (TreeMap<RangeKey.Range, Long> map : ranges) {
            totals.add(map.values().stream().mapToLong(Long::longValue).sum());
        }
        VoidWriter.write(outDir.resolve("void.ttl"), voidBase, names, totals, results);
        return summaries;
    }

    /** Counts the IRIs of one sorted IRI file in each range. Returns a map sorted by range key. */
    TreeMap<RangeKey.Range, Long> rangeCounts(Path irisFile) throws IOException {
        TreeMap<RangeKey.Range, Long> counts = new TreeMap<>();
        try (RunCursor cursor = new RunCursor(irisFile)) {
            while (cursor.current() != null) {
                counts.merge(RangeKey.of(cursor.current().parts(), depth), 1L, Long::sum);
                cursor.advance();
            }
        }
        return counts;
    }

    private PairResult comparePair(String nameA, String nameB, TreeMap<RangeKey.Range, Long> rangesA,
                                   TreeMap<RangeKey.Range, Long> rangesB, Path irisA, Path irisB, Path outDir)
            throws IOException {
        List<RangeKey.Range> shared = new ArrayList<>();
        for (RangeKey.Range range : rangesA.keySet()) {
            if (rangesB.containsKey(range)) {
                shared.add(range);
            }
        }
        Map<RangeKey.Range, Long> sharedIris = sharedIrisByRange(irisA, irisB);

        List<SharedRange> sharedRanges = new ArrayList<>();
        long total = 0;
        for (RangeKey.Range range : shared) {
            long common = sharedIris.getOrDefault(range, 0L);
            sharedRanges.add(new SharedRange(range, rangesA.get(range), rangesB.get(range), common));
            total += common;
        }

        Path output = outDir.resolve(nameA + "__" + nameB + ".parquet");
        try (OverlapParquetWriter writer = new OverlapParquetWriter(output)) {
            for (SharedRange r : sharedRanges) {
                writer.write(r.range(), r.countFirst(), r.countSecond(), r.sharedIris());
            }
            if (!sharedRanges.isEmpty()) {
                writer.endRowGroup();
            }
        }
        PairSummary summary = new PairSummary(nameA, nameB, rangesA.size(), rangesB.size(), shared.size(), total,
                output);
        return new PairResult(summary, sharedRanges);
    }

    /** Streams two sorted IRI files together and counts the IRIs they have in common, per range. */
    Map<RangeKey.Range, Long> sharedIrisByRange(Path irisA, Path irisB) throws IOException {
        Map<RangeKey.Range, Long> counts = new HashMap<>();
        try (RunCursor a = new RunCursor(irisA); RunCursor b = new RunCursor(irisB)) {
            while (a.current() != null && b.current() != null) {
                int order = a.current().compareTo(b.current());
                if (order < 0) {
                    a.advance();
                } else if (order > 0) {
                    b.advance();
                } else {
                    counts.merge(RangeKey.of(a.current().parts(), depth), 1L, Long::sum);
                    a.advance();
                    b.advance();
                }
            }
        }
        return counts;
    }

    private static void writeSummary(List<PairSummary> summaries, Path file) throws IOException {
        try (Writer w = Files.newBufferedWriter(file)) {
            w.write("first,second,ranges_first,ranges_second,shared_ranges,shared_iris\n");
            for (PairSummary s : summaries) {
                w.write(String.join(",", s.first(), s.second(), String.valueOf(s.rangesFirst()),
                        String.valueOf(s.rangesSecond()), String.valueOf(s.sharedRanges()),
                        String.valueOf(s.sharedIris())));
                w.write('\n');
            }
        }
    }
}

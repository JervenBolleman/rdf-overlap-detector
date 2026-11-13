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

/**
 * Finds the IRI ranges that two or more datasets have in common.
 *
 * <p>Each dataset's sorted IRI file is read once to count the IRIs in each range, giving a sorted map from range
 * key to count. For every pair of datasets, the ranges that both maps contain are the overlapping ranges. A
 * streaming merge of the two sorted IRI files then counts the IRIs the pair shares in each range.
 *
 * <p>Output for a pair {@code A} and {@code B} goes to {@code <outDir>/A__B.parquet}, with one row per shared range
 * sorted by range key. A {@code summary.csv} in {@code outDir} has one line per pair.
 *
 * <p>The range maps are kept in memory, one entry per range and not per IRI. The number of ranges is usually far
 * smaller than the number of IRIs, so this is the expected bound.
 */
public final class OverlapDetector {

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

    private final int depth;

    public OverlapDetector() {
        this(RangeKey.DEFAULT_DEPTH);
    }

    public OverlapDetector(int depth) {
        if (depth < 0) {
            throw new IllegalArgumentException("depth must not be negative: " + depth);
        }
        this.depth = depth;
    }

    /**
     * Compares every pair of datasets.
     *
     * @param datasetDirs dataset directories, each holding {@code iris/iris.parquet} from the sort phase
     * @param outDir      directory for the pair files and the summary
     * @return one summary per pair, in dataset order
     * @throws IOException if a dataset cannot be read or the output cannot be written
     */
    public List<PairSummary> detect(List<Path> datasetDirs, Path outDir) throws IOException {
        if (datasetDirs.size() < 2) {
            throw new IllegalArgumentException("need at least two datasets, got " + datasetDirs.size());
        }
        Files.createDirectories(outDir);

        List<String> names = new ArrayList<>();
        List<Path> irisFiles = new ArrayList<>();
        List<TreeMap<String, Long>> ranges = new ArrayList<>();
        for (Path dir : datasetDirs) {
            String name = dir.getFileName().toString();
            if (names.contains(name)) {
                throw new IllegalArgumentException("two datasets are named " + name);
            }
            names.add(name);
            Path iris = dir.resolve("iris").resolve("iris.parquet");
            irisFiles.add(iris);
            ranges.add(rangeCounts(iris));
        }

        List<PairSummary> summaries = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            for (int j = i + 1; j < names.size(); j++) {
                summaries.add(comparePair(names.get(i), names.get(j), ranges.get(i), ranges.get(j),
                        irisFiles.get(i), irisFiles.get(j), outDir));
            }
        }
        writeSummary(summaries, outDir.resolve("summary.csv"));
        return summaries;
    }

    /** Counts the IRIs of one sorted IRI file in each range. Returns a map sorted by range key. */
    TreeMap<String, Long> rangeCounts(Path irisFile) throws IOException {
        TreeMap<String, Long> counts = new TreeMap<>();
        try (RunCursor cursor = new RunCursor(irisFile)) {
            while (cursor.current() != null) {
                counts.merge(RangeKey.of(cursor.current().iri(), depth), 1L, Long::sum);
                cursor.advance();
            }
        }
        return counts;
    }

    private PairSummary comparePair(String nameA, String nameB, TreeMap<String, Long> rangesA,
                                    TreeMap<String, Long> rangesB, Path irisA, Path irisB, Path outDir)
            throws IOException {
        List<String> shared = new ArrayList<>();
        for (String range : rangesA.keySet()) {
            if (rangesB.containsKey(range)) {
                shared.add(range);
            }
        }
        Map<String, Long> sharedIris = sharedIrisByRange(irisA, irisB);

        Path output = outDir.resolve(nameA + "__" + nameB + ".parquet");
        long total = 0;
        try (OverlapParquetWriter writer = new OverlapParquetWriter(output)) {
            for (String range : shared) {
                long common = sharedIris.getOrDefault(range, 0L);
                writer.write(range, rangesA.get(range), rangesB.get(range), common);
                total += common;
            }
            if (!shared.isEmpty()) {
                writer.endRowGroup();
            }
        }
        return new PairSummary(nameA, nameB, rangesA.size(), rangesB.size(), shared.size(), total, output);
    }

    /** Streams two sorted IRI files together and counts the IRIs they have in common, per range. */
    Map<String, Long> sharedIrisByRange(Path irisA, Path irisB) throws IOException {
        Map<String, Long> counts = new HashMap<>();
        try (RunCursor a = new RunCursor(irisA); RunCursor b = new RunCursor(irisB)) {
            while (a.current() != null && b.current() != null) {
                int order = a.current().iri().compareTo(b.current().iri());
                if (order < 0) {
                    a.advance();
                } else if (order > 0) {
                    b.advance();
                } else {
                    counts.merge(RangeKey.of(a.current().iri(), depth), 1L, Long::sum);
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

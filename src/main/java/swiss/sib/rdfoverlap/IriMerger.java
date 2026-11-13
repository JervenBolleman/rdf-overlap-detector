package swiss.sib.rdfoverlap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.stream.Stream;

/**
 * Merges the sorted run files of one dataset into a single sorted, deduplicated IRI file.
 *
 * <p>The merge keeps one cursor per run in a priority queue, so memory use is one record per run plus the output
 * writer's buffer. When there are more runs than the fan-in, the runs are merged in several passes. Each pass
 * merges groups of at most {@code fanIn} runs into intermediate files, and removes the inputs it consumed.
 *
 * <p>Input runs are read from {@code <datasetDir>/runs/}. The result goes to {@code <datasetDir>/iris/iris.parquet}.
 * The result is checked (strictly increasing, and its row count matches what was written) before the runs
 * directory is deleted.
 */
public final class IriMerger {

    public static final int DEFAULT_FAN_IN = 64;
    public static final int DEFAULT_ROW_GROUP_ROWS = 1_000_000;

    private static final Comparator<IriRecord> BY_IRI = Comparator.comparing(IriRecord::iri);

    /**
     * What a sort produced.
     *
     * @param runRows       rows in the original run files
     * @param distinctIris  rows in the output file
     * @param passes        intermediate merge passes run before the final merge
     * @param output        the sorted IRI file
     */
    public record MergeSummary(long runRows, long distinctIris, int passes, Path output) {
    }

    private final Path datasetDir;
    private final int fanIn;
    private final int rowGroupRows;

    public IriMerger(Path datasetDir) {
        this(datasetDir, DEFAULT_FAN_IN, DEFAULT_ROW_GROUP_ROWS);
    }

    public IriMerger(Path datasetDir, int fanIn, int rowGroupRows) {
        if (fanIn < 2) {
            throw new IllegalArgumentException("fanIn must be at least 2: " + fanIn);
        }
        if (rowGroupRows < 1) {
            throw new IllegalArgumentException("rowGroupRows must be at least 1: " + rowGroupRows);
        }
        this.datasetDir = datasetDir;
        this.fanIn = fanIn;
        this.rowGroupRows = rowGroupRows;
    }

    /**
     * Sorts and deduplicates the runs of this dataset into one file.
     *
     * @return counts, the output path, and the number of intermediate passes
     * @throws IOException if a run cannot be read, the output cannot be written, or the output fails its check
     */
    public MergeSummary sort() throws IOException {
        Path runsDir = datasetDir.resolve("runs");
        if (!Files.isDirectory(runsDir)) {
            throw new IOException("no runs directory in " + datasetDir + ", run extract first");
        }
        Path outDir = datasetDir.resolve("iris");
        Files.createDirectories(outDir);
        Path output = outDir.resolve("iris.parquet");

        List<Path> runs = listRuns(runsDir);
        long runRows = 0;
        for (Path run : runs) {
            runRows += IriParquetReader.rowCount(run);
        }

        int passes = 0;
        List<Path> current = runs;
        while (current.size() > fanIn) {
            passes++;
            Path passDir = Files.createDirectories(runsDir.resolve("pass-" + passes));
            List<Path> next = new ArrayList<>();
            for (int start = 0; start < current.size(); start += fanIn) {
                List<Path> group = current.subList(start, Math.min(start + fanIn, current.size()));
                Path merged = passDir.resolve(String.format("merged-%06d.parquet", next.size() + 1));
                mergeInto(group, merged);
                for (Path consumed : group) {
                    Files.delete(consumed);
                }
                next.add(merged);
            }
            current = next;
        }

        long distinct = mergeInto(current, output);
        verify(output, distinct);
        deleteRecursively(runsDir);
        return new MergeSummary(runRows, distinct, passes, output);
    }

    /** Merges the given sorted files into {@code output}, dropping duplicates. Returns the rows written. */
    private long mergeInto(List<Path> inputs, Path output) throws IOException {
        List<RunCursor> cursors = new ArrayList<>();
        try {
            PriorityQueue<RunCursor> queue = new PriorityQueue<>(
                    Math.max(1, inputs.size()), Comparator.comparing((RunCursor c) -> c.current().iri()));
            for (Path input : inputs) {
                RunCursor cursor = new RunCursor(input);
                cursors.add(cursor);
                if (cursor.current() != null) {
                    queue.add(cursor);
                }
            }

            long written = 0;
            long inGroup = 0;
            String last = null;
            try (IriParquetWriter writer = new IriParquetWriter(output)) {
                while (!queue.isEmpty()) {
                    RunCursor cursor = queue.poll();
                    IriRecord record = cursor.current();
                    if (!record.iri().equals(last)) {
                        writer.write(record);
                        last = record.iri();
                        written++;
                        inGroup++;
                        if (inGroup == rowGroupRows) {
                            writer.endRowGroup();
                            inGroup = 0;
                        }
                    }
                    cursor.advance();
                    if (cursor.current() != null) {
                        queue.add(cursor);
                    }
                }
                if (inGroup > 0) {
                    writer.endRowGroup();
                }
            }
            return written;
        } finally {
            for (RunCursor cursor : cursors) {
                cursor.close();
            }
        }
    }

    /** Checks that the output is strictly increasing and that its footer row count matches what was written. */
    private static void verify(Path output, long expectedRows) throws IOException {
        IriRecord[] previous = {null};
        long read = IriParquetReader.forEach(output, record -> {
            if (previous[0] != null && BY_IRI.compare(previous[0], record) >= 0) {
                throw new IllegalStateException("output not strictly increasing at " + record.iri());
            }
            previous[0] = record;
        });
        if (read != expectedRows || IriParquetReader.rowCount(output) != expectedRows) {
            throw new IllegalStateException("output has " + read + " rows, expected " + expectedRows);
        }
    }

    private static List<Path> listRuns(Path runsDir) throws IOException {
        if (!Files.isDirectory(runsDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(runsDir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".parquet")).sorted().toList();
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path p : paths) {
                Files.delete(p);
            }
        }
    }
}

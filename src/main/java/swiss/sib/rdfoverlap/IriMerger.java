package swiss.sib.rdfoverlap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Merges sorted parquet files of IRIs into one sorted, deduplicated file.
 *
 * <p>The merge keeps one cursor per input in a priority queue, so memory use is one record per input plus the output
 * writer's buffer. Callers choose how many inputs to merge at once, because each open input is an open file.
 */
final class IriMerger {

    private IriMerger() {
    }

    /**
     * Merges the sorted inputs into {@code output}, dropping duplicates.
     *
     * @param inputs        sorted IRI files, each with the IriSchema layout
     * @param output        the file to write
     * @param rowGroupRows  rows per row group in the output
     * @return the number of rows written
     * @throws IOException if an input cannot be read or the output cannot be written
     */
    static long merge(List<Path> inputs, Path output, int rowGroupRows) throws IOException {
        List<RunCursor> cursors = new ArrayList<>();
        try {
            PriorityQueue<RunCursor> queue = new PriorityQueue<>(
                    Math.max(1, inputs.size()), (a, b) -> a.current().compareTo(b.current()));
            for (Path input : inputs) {
                RunCursor cursor = new RunCursor(input);
                cursors.add(cursor);
                if (cursor.current() != null) {
                    queue.add(cursor);
                }
            }

            long written = 0;
            long inGroup = 0;
            IriRecord last = null;
            try (IriParquetWriter writer = new IriParquetWriter(output)) {
                while (!queue.isEmpty()) {
                    RunCursor cursor = queue.poll();
                    IriRecord record = cursor.current();
                    if (!record.equals(last)) {
                        writer.write(record);
                        last = record;
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

    /** Checks that the output is strictly increasing and that its footer row count matches the rows written. */
    static void verify(Path output, long expectedRows) throws IOException {
        IriRecord[] previous = {null};
        long read = IriParquetReader.forEach(output, record -> {
            if (previous[0] != null && previous[0].compareTo(record) >= 0) {
                throw new IllegalStateException("output not strictly increasing at " + record.iri());
            }
            previous[0] = record;
        });
        if (read != expectedRows || IriParquetReader.rowCount(output) != expectedRows) {
            throw new IllegalStateException("output has " + read + " rows, expected " + expectedRows);
        }
    }

    /** The size of a file, used to pick the smallest files to merge next. */
    static long sizeOf(Path file) throws IOException {
        return Files.size(file);
    }
}

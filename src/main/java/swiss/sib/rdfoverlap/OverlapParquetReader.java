package swiss.sib.rdfoverlap;

import dev.hardwood.InputFile;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Reads the overlap files written by {@link OverlapParquetWriter}. */
public final class OverlapParquetReader {

    /**
     * One row of an overlap file.
     *
     * @param range       the range key
     * @param countFirst  IRIs of the first dataset in the range
     * @param countSecond IRIs of the second dataset in the range
     * @param sharedIris  IRIs that both datasets have in the range
     */
    public record OverlapRow(String range, long countFirst, long countSecond, long sharedIris) {
    }

    private OverlapParquetReader() {
    }

    /**
     * Passes every row of an overlap file to the consumer, in file order.
     *
     * @param file     an overlap file
     * @param consumer receives one row per range
     * @return the number of rows read
     * @throws IOException if the file cannot be read
     */
    public static long forEach(Path file, Consumer<OverlapRow> consumer) throws IOException {
        long count = 0;
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(file));
             RowReader rows = reader.rowReader()) {
            while (rows.hasNext()) {
                rows.next();
                consumer.accept(new OverlapRow(
                        rows.getString(OverlapParquetWriter.RANGE),
                        rows.getLong(OverlapParquetWriter.COUNT_FIRST),
                        rows.getLong(OverlapParquetWriter.COUNT_SECOND),
                        rows.getLong(OverlapParquetWriter.SHARED_IRIS)));
                count++;
            }
        }
        return count;
    }
}

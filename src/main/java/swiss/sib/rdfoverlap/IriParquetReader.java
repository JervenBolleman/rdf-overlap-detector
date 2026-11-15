package swiss.sib.rdfoverlap;

import dev.hardwood.InputFile;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import dev.hardwood.row.StructAccessor;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Reads the {@link IriSchema} parquet files written by {@link IriParquetWriter}. */
public final class IriParquetReader {

    private IriParquetReader() {
    }

    /**
     * Passes every row of the file to the consumer, in file order.
     *
     * @param file     a parquet file written with {@link IriSchema}
     * @param consumer receives one record per row
     * @return the number of rows read
     * @throws IOException if the file cannot be read
     */
    public static long forEach(Path file, Consumer<IriParts> consumer) throws IOException {
        NativeCodecs.loadZstdIfNative();
        long count = 0;
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(file));
             RowReader rows = reader.rowReader()) {
            while (rows.hasNext()) {
                rows.next();
                consumer.accept(toRecord(rows));
                count++;
            }
        }
        return count;
    }

    /**
     * Returns the number of rows in a parquet file, read from its footer without reading the data.
     *
     * @param file a parquet file
     * @return the number of rows
     * @throws IOException if the file cannot be read
     */
    public static long rowCount(Path file) throws IOException {
        NativeCodecs.loadZstdIfNative();
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(file))) {
            return reader.getFileMetaData().numRows();
        }
    }

    /**
     * Returns the number of row groups in a parquet file, which the tests use to check batch boundaries.
     *
     * @param file a parquet file
     * @return the number of row groups
     * @throws IOException if the file cannot be read
     */
    public static int rowGroupCount(Path file) throws IOException {
        NativeCodecs.loadZstdIfNative();
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(file))) {
            return reader.getFileMetaData().rowGroups().size();
        }
    }

    static IriParts toRecord(StructAccessor row) {
        String userinfo = optionalString(row, IriSchema.USERINFO);
        String host = optionalString(row, IriSchema.HOST);
        Integer port = row.isNull(IriSchema.PORT) ? null : row.getInt(IriSchema.PORT);
        return new IriParts(
                row.getString(IriSchema.SCHEME),
                userinfo,
                host,
                port,
                row.getString(IriSchema.PATH),
                optionalString(row, IriSchema.QUERY),
                optionalString(row, IriSchema.FRAGMENT));
    }

    private static String optionalString(StructAccessor row, int index) {
        return row.isNull(index) ? null : row.getString(index);
    }
}

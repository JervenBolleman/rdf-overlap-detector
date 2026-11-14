package swiss.sib.rdfoverlap;

import dev.hardwood.OutputFile;
import dev.hardwood.metadata.LogicalType;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.metadata.RepetitionType;
import dev.hardwood.schema.FileSchema;
import dev.hardwood.writer.ParquetFileWriter;
import dev.hardwood.writer.RowWriter;
import dev.hardwood.writer.WriterConfig;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Writes one row per range that two datasets share: the range key, how many IRIs each dataset has in it, and how
 * many IRIs the two datasets share.
 */
public final class OverlapParquetWriter implements Closeable {

    static final int RANGE = 0;
    static final int COUNT_FIRST = 1;
    static final int COUNT_SECOND = 2;
    static final int SHARED_IRIS = 3;

    static final FileSchema FILE_SCHEMA = FileSchema.builder("overlap")
            .addColumn("range", PhysicalType.BYTE_ARRAY, RepetitionType.REQUIRED,
                    LogicalType.string())
            .addColumn("count_first", PhysicalType.INT64, RepetitionType.REQUIRED)
            .addColumn("count_second", PhysicalType.INT64, RepetitionType.REQUIRED)
            .addColumn("shared_iris", PhysicalType.INT64, RepetitionType.REQUIRED)
            .build();

    private final ParquetFileWriter writer;
    private final RowWriter rows;

    public OverlapParquetWriter(Path file) throws IOException {
        NativeCodecs.loadZstdIfNative();
        this.writer = ParquetFileWriter.create(OutputFile.of(file), FILE_SCHEMA, WriterConfig.defaults());
        this.rows = writer.rowWriter();
    }

    public void write(RangeKey.Range range, long countFirst, long countSecond, long sharedIris) throws IOException {
        rows.writeRow(s -> s
                .setString(RANGE, range.toString())
                .setLong(COUNT_FIRST, countFirst)
                .setLong(COUNT_SECOND, countSecond)
                .setLong(SHARED_IRIS, sharedIris));
    }

    public void endRowGroup() throws IOException {
        writer.endRowGroup();
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }
}

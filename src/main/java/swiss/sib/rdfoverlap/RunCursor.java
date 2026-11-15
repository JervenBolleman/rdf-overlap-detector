package swiss.sib.rdfoverlap;

import dev.hardwood.InputFile;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Streams the records of one sorted parquet file, one at a time, for the k-way merge.
 *
 * <p>The cursor holds the current record and reads the next one on {@link #advance()}. It returns null from
 * {@link #current()} once the file is exhausted, and then closes the file.
 */
final class RunCursor implements Closeable {

    private final ParquetFileReader reader;
    private final RowReader rows;
    private IriParts current;

    RunCursor(Path file) throws IOException {
        NativeCodecs.loadZstdIfNative();
        this.reader = ParquetFileReader.open(InputFile.of(file));
        try {
            this.rows = reader.rowReader();
        } catch (IOException | RuntimeException e) {
            reader.close();
            throw e;
        }
        advance();
    }

    /** The current record, or null when the file is exhausted. */
    IriParts current() {
        return current;
    }

    void advance() throws IOException {
        if (rows.hasNext()) {
            rows.next();
            current = IriParquetReader.toRecord(rows);
        } else {
            current = null;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            rows.close();
        } finally {
            reader.close();
        }
    }
}

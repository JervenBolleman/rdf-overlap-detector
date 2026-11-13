package swiss.sib.rdfoverlap;

import dev.hardwood.OutputFile;
import dev.hardwood.writer.ParquetFileWriter;
import dev.hardwood.writer.RowWriter;
import dev.hardwood.writer.WriterConfig;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Writes {@link IriRecord}s to a parquet file using {@link IriSchema}.
 *
 * <p>Rows accumulate in the current row group until {@link #endRowGroup()} is called. The extractor calls it once per
 * sorted batch, so each batch becomes one row group. Wrapping Hardwood here keeps its experimental writer API in one
 * place.
 */
public final class IriParquetWriter implements Closeable {

    private final ParquetFileWriter writer;
    private final RowWriter rows;

    public IriParquetWriter(Path file) throws IOException {
        this(file, WriterConfig.defaults());
    }

    IriParquetWriter(Path file, WriterConfig config) throws IOException {
        this.writer = ParquetFileWriter.create(OutputFile.of(file), IriSchema.FILE_SCHEMA, config);
        this.rows = writer.rowWriter();
    }

    public void write(IriRecord record) throws IOException {
        IriParts p = record.parts();
        rows.writeRow(s -> {
            s.setString(IriSchema.IRI, record.iri());
            s.setString(IriSchema.SCHEME, p.scheme());
            if (p.userinfo() == null) {
                s.setNull(IriSchema.USERINFO);
            } else {
                s.setString(IriSchema.USERINFO, p.userinfo());
            }
            if (p.host() == null) {
                s.setNull(IriSchema.HOST);
            } else {
                s.setString(IriSchema.HOST, p.host());
            }
            if (p.port() == null) {
                s.setNull(IriSchema.PORT);
            } else {
                s.setInt(IriSchema.PORT, p.port());
            }
            s.setString(IriSchema.PATH, p.path());
            if (p.query() == null) {
                s.setNull(IriSchema.QUERY);
            } else {
                s.setString(IriSchema.QUERY, p.query());
            }
            if (p.fragment() == null) {
                s.setNull(IriSchema.FRAGMENT);
            } else {
                s.setString(IriSchema.FRAGMENT, p.fragment());
            }
        });
    }

    /** Closes the current row group so the rows written so far form one row group in the file. */
    public void endRowGroup() throws IOException {
        writer.endRowGroup();
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }
}

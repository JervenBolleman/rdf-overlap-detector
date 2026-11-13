package swiss.sib.rdfoverlap;

import dev.hardwood.metadata.LogicalType;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.metadata.RepetitionType;
import dev.hardwood.schema.FileSchema;

/**
 * The parquet schema shared by the IRI files and the predicate files: one row per IRI, with the full IRI and its
 * RFC 3986 components.
 *
 * <p>Hardwood's row accessors take column indices, so the index constants below must follow the column order in
 * {@link #FILE_SCHEMA}. {@code IriSchemaTest} checks that they match.
 */
public final class IriSchema {

    public static final int IRI = 0;
    public static final int SCHEME = 1;
    public static final int USERINFO = 2;
    public static final int HOST = 3;
    public static final int PORT = 4;
    public static final int PATH = 5;
    public static final int QUERY = 6;
    public static final int FRAGMENT = 7;

    public static final FileSchema FILE_SCHEMA = FileSchema.builder("iri")
            .addColumn("iri", PhysicalType.BYTE_ARRAY, RepetitionType.REQUIRED, LogicalType.string())
            .addColumn("scheme", PhysicalType.BYTE_ARRAY, RepetitionType.REQUIRED, LogicalType.string())
            .addColumn("userinfo", PhysicalType.BYTE_ARRAY, RepetitionType.OPTIONAL, LogicalType.string())
            .addColumn("host", PhysicalType.BYTE_ARRAY, RepetitionType.OPTIONAL, LogicalType.string())
            .addColumn("port", PhysicalType.INT32, RepetitionType.OPTIONAL)
            .addColumn("path", PhysicalType.BYTE_ARRAY, RepetitionType.REQUIRED, LogicalType.string())
            .addColumn("query", PhysicalType.BYTE_ARRAY, RepetitionType.OPTIONAL, LogicalType.string())
            .addColumn("fragment", PhysicalType.BYTE_ARRAY, RepetitionType.OPTIONAL, LogicalType.string())
            .build();

    private IriSchema() {
    }
}

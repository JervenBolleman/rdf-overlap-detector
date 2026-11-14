package swiss.sib.rdfoverlap;

import dev.hardwood.metadata.LogicalType;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.metadata.RepetitionType;
import dev.hardwood.schema.FileSchema;

/**
 * The parquet schema shared by the IRI files and the predicate files: one row per IRI, with its RFC 3986 components.
 * The IRI text is not stored, because the components rebuild it (see {@link IriParts#toIri()}).
 *
 * <p>Hardwood's row accessors take column indices, so the index constants below must follow the column order in
 * {@link #FILE_SCHEMA}. {@code IriSchemaTest} checks that they match.
 */
public final class IriSchema {

    public static final int SCHEME = 0;
    public static final int USERINFO = 1;
    public static final int HOST = 2;
    public static final int PORT = 3;
    public static final int PATH = 4;
    public static final int QUERY = 5;
    public static final int FRAGMENT = 6;

    public static final FileSchema FILE_SCHEMA = FileSchema.builder("iri")
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

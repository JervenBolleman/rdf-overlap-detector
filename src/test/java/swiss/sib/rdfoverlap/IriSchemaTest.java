package swiss.sib.rdfoverlap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class IriSchemaTest {

    /** The index constants must match the column order, because the reader and writer use them. */
    @Test
    void indexConstantsMatchColumnOrder() {
        List<String> expected = List.of(
                "scheme", "userinfo", "host", "port", "path", "query", "fragment");
        assertEquals(expected.size(), IriSchema.FILE_SCHEMA.getColumnCount());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i), IriSchema.FILE_SCHEMA.getColumn(i).name(), "column " + i);
        }
        assertEquals(0, IriSchema.SCHEME);
        assertEquals(6, IriSchema.FRAGMENT);
    }
}

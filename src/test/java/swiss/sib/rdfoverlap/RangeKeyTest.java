package swiss.sib.rdfoverlap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RangeKeyTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "http://h/a/b/c|2|http://h/a/b/",
            "http://h/a/1|2|http://h/a/",
            "http://h/a/b|2|http://h/a/",
            "http://h/a/b/|2|http://h/a/b/",
            "http://h/a/b/c/d|2|http://h/a/b/",
            "http://h/x|2|http://h/",
            "http://h|2|http://h",
            "http://h/|2|http://h/",
            "http://h/a/b/c|1|http://h/a/",
            "http://h/a/b/c|0|http://h/",
            "http://h/a/b/c?x/y|2|http://h/a/b/",
            "http://h/a/b#f/g|2|http://h/a/",
            "http://u:pw@h:8080/a/b/c|2|http://u:pw@h:8080/a/b/",
            "file:///etc/hosts|2|file:///etc/",
            "urn:isbn:1|2|urn:",
            "mailto:x@example.org|2|mailto:"})
    void keysForExamples(String iri, int depth, String expected) {
        assertEquals(expected, RangeKey.of(iri, depth));
    }

    @Test
    void keyIsAPrefixOfTheIri() {
        for (String iri : List.of("http://h/a/b/c", "https://u@h:1/x?q#f", "file:///a/b", "urn:x:y", "http://h")) {
            for (int depth = 0; depth <= 4; depth++) {
                assertTrue(iri.startsWith(RangeKey.of(iri, depth)), iri + " depth " + depth);
            }
        }
    }

    /** The range from parts must be the range from the text, for every IRI shape the tool keeps. */
    @Test
    void partsRangeMatchesTextRange() {
        String[] iris = {"http://h/a/b/c", "http://h/a/1", "http://h/a/b", "http://h/x", "http://h", "http://h/",
                "http://h/a/b/c?x/y", "http://h/a/b#f/g", "http://u:pw@h:8080/a/b/c", "file:///etc/hosts",
                "urn:isbn:1", "mailto:x@example.org", "http://[::1]:8080/a/b/"};
        for (String iri : iris) {
            for (int depth = 0; depth <= 3; depth++) {
                IriParts parts = IriParts.parse(iri);
                assertEquals(RangeKey.of(iri, depth), RangeKey.of(parts, depth).toString(), iri + " depth " + depth);
            }
        }
    }

    /** Two IRIs in the same range give equal, equally hashed range keys, and no string is built to compare them. */
    @Test
    void rangesCompareAndHashOnComponents() {
        RangeKey.Range a = RangeKey.of(IriParts.parse("http://h/a/b/c"), 2);
        RangeKey.Range b = RangeKey.of(IriParts.parse("http://h/a/b/zzz"), 2);
        RangeKey.Range c = RangeKey.of(IriParts.parse("http://h/a/c/c"), 2);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertTrue(a.compareTo(c) < 0);
        assertTrue(c.compareTo(a) > 0);
    }

    @Test
    void rejectsNegativeDepthAndMissingScheme() {
        assertThrows(IllegalArgumentException.class, () -> RangeKey.of("http://h/a", -1));
        assertThrows(IllegalArgumentException.class, () -> RangeKey.of("/relative", 2));
    }
}

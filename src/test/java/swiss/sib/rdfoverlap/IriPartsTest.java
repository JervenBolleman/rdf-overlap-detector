package swiss.sib.rdfoverlap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class IriPartsTest {

    /** Examples from RFC 3986 section 5.4.1, the base is http://a/b/c/d;p?q. */
    @Test
    void rfc3986Example() {
        assertEquals(new IriParts("http", null, "a", null, "/b/c/d;p", "q", null),
                IriParts.parse("http://a/b/c/d;p?q"));
    }

    @Test
    void schemeWithoutAuthority() {
        assertEquals(new IriParts("urn", null, null, null, "example:animal", null, null),
                IriParts.parse("urn:example:animal"));
    }

    @Test
    void emptyAuthorityGivesEmptyHostNotNull() {
        IriParts parts = IriParts.parse("file:///etc/hosts");
        assertEquals("", parts.host());
        assertEquals("/etc/hosts", parts.path());
    }

    @Test
    void userinfoHostAndPort() {
        assertEquals(new IriParts("https", "user:pw", "host", 8443, "/x", null, null),
                IriParts.parse("https://user:pw@host:8443/x"));
    }

    @Test
    void userinfoMayBeEmptyWhileHostIsNot() {
        assertEquals(new IriParts("http", "", "h", null, "/", null, null),
                IriParts.parse("http://@h/"));
    }

    @Test
    void ipv6LiteralKeepsBracketsAndPortIsSeparated() {
        assertEquals(new IriParts("http", null, "[2001:db8::1]", 8080, "/", null, null),
                IriParts.parse("http://[2001:db8::1]:8080/"));
        assertEquals(new IriParts("http", null, "[::1]", null, "/", null, null),
                IriParts.parse("http://[::1]/"));
    }

    @Test
    void emptyPortIsAbsent() {
        assertNull(IriParts.parse("http://h:/").port());
    }

    @Test
    void emptyQueryAndEmptyFragmentAreNotNull() {
        IriParts parts = IriParts.parse("http://h/p?#");
        assertEquals("", parts.query());
        assertEquals("", parts.fragment());
    }

    @Test
    void absentQueryAndFragmentAreNull() {
        IriParts parts = IriParts.parse("http://h/p");
        assertNull(parts.query());
        assertNull(parts.fragment());
    }

    @Test
    void emptyPathWhenAbsent() {
        assertEquals("", IriParts.parse("http://h").path());
    }

    @Test
    void questionMarkAndHashInFragmentAreKept() {
        IriParts parts = IriParts.parse("http://h/p?a=1#x?y#z");
        assertEquals("a=1", parts.query());
        assertEquals("x?y#z", parts.fragment());
    }

    @Test
    void percentEncodingIsNotDecoded() {
        IriParts parts = IriParts.parse("http://h/a%20b%2Fc?q=%7E");
        assertEquals("/a%20b%2Fc", parts.path());
        assertEquals("q=%7E", parts.query());
    }

    @Test
    void schemeIsNotLowerCased() {
        assertEquals("HTTP", IriParts.parse("HTTP://h/").scheme());
    }

    @Test
    void nonAsciiIriIsKeptAsWritten() {
        IriParts parts = IriParts.parse("http://例子.example/路径?q=é#ф");
        assertEquals("例子.example", parts.host());
        assertEquals("/路径", parts.path());
        assertEquals("q=é", parts.query());
        assertEquals("ф", parts.fragment());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "/relative/path",
            "relative/path:with-colon-after-slash/",
            "//no-scheme/here",
            "http://h:abc/",
            "http://h:-1/",
            "http://h:99999999999/"})
    void rejectsInputWeCannotDecompose(String iri) {
        assertThrows(IllegalArgumentException.class, () -> IriParts.parse(iri), iri);
    }

    /** Parts compare by components, and rebuild the IRI text, except for an empty port, which is the default. */
    @Test
    void partsRebuildTheirIriTextAndCompareByComponents() {
        String[] iris = {"http://h/a/b/c?x#y", "https://user:pw@host:8443/x", "http://[2001:db8::1]:8080/",
                "http://@h/", "file:///etc/hosts", "urn:example:animal", "http://h/a%20b%2Fc?q=%7E",
                "http://例子.example/路径?q=é#ф", "http://h/p?#"};
        for (String iri : iris) {
            assertEquals(iri, IriParts.parse(iri).toIri(), iri);
        }
        assertEquals("http://h/x", IriParts.parse("http://h:/x").toIri(), "empty port is the default");
        assertTrue(IriParts.parse("http://a/x").compareTo(IriParts.parse("http://b/x")) < 0);
        assertTrue(IriParts.parse("http://h/x").compareTo(IriParts.parse("http://h/x?q")) < 0, "absent sorts first");
    }
}

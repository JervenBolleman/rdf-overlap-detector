package swiss.sib.rdfoverlap;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The components of an IRI, split as in RFC 3986 section 3.
 *
 * <p>Components are stored exactly as they appear in the input. There is no percent-decoding and no case
 * normalisation, so equal IRIs always give equal parts.
 *
 * <p>{@code userinfo}, {@code host}, {@code port}, {@code query} and {@code fragment} are {@code null} when the
 * component is absent. An empty but present component is kept as the empty string, so {@code file:///x} has an
 * empty host and {@code http://h/?} has an empty query. The host keeps its brackets for IPv6 literals.
 *
 * @param scheme   required, e.g. {@code http}
 * @param userinfo optional user information before the {@code @}
 * @param host     optional host, including brackets for IPv6 literals
 * @param port     optional port number
 * @param path     path, possibly empty
 * @param query    optional query without the leading {@code ?}
 * @param fragment optional fragment without the leading {@code #}
 */
public record IriParts(
        String scheme,
        String userinfo,
        String host,
        Integer port,
        String path,
        String query,
        String fragment) {

    /** The regular expression of RFC 3986 appendix B, with the groups named for readability. */
    private static final Pattern RFC3986 = Pattern.compile(
            "^(?<scheme>[^:/?#]+):(?<authority>//[^/?#]*)?(?<path>[^?#]*)(?:\\?(?<query>[^#]*))?(?:#(?<fragment>.*))?$",
            Pattern.DOTALL);

    /**
     * Splits an absolute IRI into its components.
     *
     * @param iri an absolute IRI, which must have a scheme
     * @return the components of the IRI
     * @throws IllegalArgumentException if the IRI has no scheme or its port is not a valid number
     */
    public static IriParts parse(String iri) {
        Matcher m = RFC3986.matcher(iri);
        if (!m.matches()) {
            // The RFC regex matches every string once the scheme is optional, so this only means "no scheme".
            throw new IllegalArgumentException("not an absolute IRI: " + iri);
        }
        String scheme = m.group("scheme");
        String userinfo = null;
        String host = null;
        Integer port = null;
        String authority = m.group("authority");
        if (authority != null) {
            String rest = authority.substring(2);
            int at = rest.lastIndexOf('@');
            if (at >= 0) {
                userinfo = rest.substring(0, at);
                rest = rest.substring(at + 1);
            }
            int colon = portSeparator(rest);
            if (colon >= 0) {
                String portText = rest.substring(colon + 1);
                rest = rest.substring(0, colon);
                if (!portText.isEmpty()) {
                    port = parsePort(portText, iri);
                }
            }
            host = rest;
        }
        return new IriParts(
                scheme,
                userinfo,
                host,
                port,
                m.group("path"),
                m.group("query"),
                m.group("fragment"));
    }

    /** Finds the colon that starts the port. A colon inside an IPv6 literal in brackets does not count. */
    private static int portSeparator(String hostAndPort) {
        int close = hostAndPort.lastIndexOf(']');
        int colon = hostAndPort.lastIndexOf(':');
        return colon > close ? colon : -1;
    }

    private static int parsePort(String text, String iri) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') {
                throw new IllegalArgumentException("port is not a number in " + iri);
            }
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("port out of range in " + iri, e);
        }
    }
}

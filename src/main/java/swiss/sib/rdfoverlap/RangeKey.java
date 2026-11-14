package swiss.sib.rdfoverlap;

/**
 * The range an IRI falls in, used to compare datasets at the level of IRI prefixes.
 *
 * <p>The key is always a string prefix of the IRI. Ranges are not contiguous in sorted IRI order, because a key can
 * be a prefix of another range's key: {@code http://h/a/b} is in range {@code http://h/a/}, which is a prefix of
 * {@code http://h/a/b-c/}, the range of {@code http://h/a/b-c/d}. So the counting code adds to a map per IRI and
 * does not rely on grouping. For an IRI with an authority, the key is
 * {@code scheme://authority} followed by the path up to and including the {@code depth}-th leading segment's
 * trailing slash. The last path segment, which is the leaf, is never part of the key. An IRI with fewer segments
 * gets the path up to its last directory slash, and an IRI with no path gets the authority alone.
 *
 * <p>An IRI with no authority, such as {@code urn:isbn:1}, has only the scheme as its key, here {@code urn:}.
 *
 * <p>Examples with depth 2: {@code http://h/a/b/c} gives {@code http://h/a/b/}, {@code http://h/a/1} gives
 * {@code http://h/a/}, and {@code http://h/a/b/c?x/y} gives {@code http://h/a/b/}, because the query is not part
 * of the path.
 */
public final class RangeKey {

    public static final int DEFAULT_DEPTH = 2;

    /**
     * A range, held without building its text: the parts of the IRI it came from, and how much of the path is in the
     * range. The range is {@code scheme://authority} plus {@code path[0, pathEnd)}. Ranges compare and hash on those
     * components, so the overlap detection never allocates a string for an IRI.
     *
     * <p>{@link #toString()} builds the text, which is for output only.
     */
    public record Range(String scheme, String userinfo, String host, Integer port, String path, int pathEnd)
            implements Comparable<Range> {

        @Override
        public int compareTo(Range other) {
            int c = scheme.compareTo(other.scheme);
            if (c != 0) {
                return c;
            }
            c = compareNullable(userinfo, other.userinfo);
            if (c != 0) {
                return c;
            }
            c = compareNullable(host, other.host);
            if (c != 0) {
                return c;
            }
            c = compareNullable(port, other.port);
            if (c != 0) {
                return c;
            }
            int common = Math.min(pathEnd, other.pathEnd);
            for (int i = 0; i < common; i++) {
                c = Character.compare(path.charAt(i), other.path.charAt(i));
                if (c != 0) {
                    return c;
                }
            }
            return Integer.compare(pathEnd, other.pathEnd);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Range other)) {
                return false;
            }
            return pathEnd == other.pathEnd
                    && scheme.equals(other.scheme)
                    && java.util.Objects.equals(userinfo, other.userinfo)
                    && java.util.Objects.equals(host, other.host)
                    && java.util.Objects.equals(port, other.port)
                    && path.regionMatches(0, other.path, 0, pathEnd);
        }

        @Override
        public int hashCode() {
            int h = scheme.hashCode();
            h = 31 * h + java.util.Objects.hash(userinfo, host, port);
            for (int i = 0; i < pathEnd; i++) {
                h = 31 * h + path.charAt(i);
            }
            return 31 * h + pathEnd;
        }

        /** The text of the range, such as {@code http://h/a/}. This allocates, so use it only for output. */
        @Override
        public String toString() {
            StringBuilder text = new StringBuilder(scheme.length() + pathEnd + 16).append(scheme).append(':');
            if (host != null) {
                text.append("//");
                if (userinfo != null) {
                    text.append(userinfo).append('@');
                }
                text.append(host);
                if (port != null) {
                    text.append(':').append(port);
                }
            }
            return text.append(path, 0, pathEnd).toString();
        }

        private static <T extends Comparable<T>> int compareNullable(T a, T b) {
            if (a == null || b == null) {
                return a == null ? (b == null ? 0 : -1) : 1;
            }
            return a.compareTo(b);
        }
    }

    private RangeKey() {
    }

    /**
     * The range of an IRI, from its parts. It gives the same range as {@link #of(String, int)} for the same IRI, but
     * builds no string.
     *
     * @param parts the parts of an absolute IRI
     * @param depth the number of leading path segments in the range, at least 0
     * @return the range
     */
    public static Range of(IriParts parts, int depth) {
        if (depth < 0) {
            throw new IllegalArgumentException("depth must not be negative: " + depth);
        }
        if (parts.host() == null) {
            return new Range(parts.scheme(), null, null, null, "", 0);
        }
        String path = parts.path();
        int cut = 0;
        int slashes = 0;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') {
                slashes++;
                cut = i + 1;
                if (slashes == depth + 1) {
                    break;
                }
            }
        }
        return new Range(parts.scheme(), parts.userinfo(), parts.host(), parts.port(), path, cut);
    }

    /**
     * Returns the range key of an absolute IRI.
     *
     * @param iri   an absolute IRI with a scheme
     * @param depth the number of leading path segments in the key, at least 0
     * @return the prefix of {@code iri} that names its range
     * @throws IllegalArgumentException if the IRI has no scheme or the depth is negative
     */
    public static String of(String iri, int depth) {
        if (depth < 0) {
            throw new IllegalArgumentException("depth must not be negative: " + depth);
        }
        int colon = iri.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("not an absolute IRI: " + iri);
        }
        if (!iri.startsWith("//", colon + 1)) {
            return iri.substring(0, colon + 1);
        }
        int pathStart = colon + 3;
        while (pathStart < iri.length() && "/?#".indexOf(iri.charAt(pathStart)) < 0) {
            pathStart++;
        }
        int pathEnd = pathStart;
        while (pathEnd < iri.length() && "?#".indexOf(iri.charAt(pathEnd)) < 0) {
            pathEnd++;
        }
        int cut = pathStart;
        int slashes = 0;
        for (int i = pathStart; i < pathEnd; i++) {
            if (iri.charAt(i) == '/') {
                slashes++;
                cut = i + 1;
                if (slashes == depth + 1) {
                    break;
                }
            }
        }
        return iri.substring(0, cut);
    }
}

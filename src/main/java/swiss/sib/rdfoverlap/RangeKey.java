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

    private RangeKey() {
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

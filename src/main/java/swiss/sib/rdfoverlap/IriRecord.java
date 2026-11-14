package swiss.sib.rdfoverlap;

/**
 * One IRI, held as its parts. Records compare by parts, so the merge and the deduplication never build an IRI string.
 *
 * @param parts the RFC 3986 components of the IRI
 */
public record IriRecord(IriParts parts) implements Comparable<IriRecord> {

    /**
     * Decomposes an IRI string into a record.
     *
     * @param iri an absolute IRI
     * @return the record for that IRI
     * @throws IllegalArgumentException if the IRI cannot be decomposed, see {@link IriParts#parse(String)}
     */
    public static IriRecord of(String iri) {
        return new IriRecord(IriParts.parse(iri));
    }

    /**
     * The IRI text. This allocates a string, so it is for display and for the range computation, not for comparisons.
     *
     * @return the IRI text, see {@link IriParts#toIri()}
     */
    public String iri() {
        return parts.toIri();
    }

    @Override
    public int compareTo(IriRecord other) {
        return parts.compareTo(other.parts);
    }
}

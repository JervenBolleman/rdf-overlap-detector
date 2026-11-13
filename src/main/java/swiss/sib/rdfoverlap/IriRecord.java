package swiss.sib.rdfoverlap;

/**
 * One IRI and its decomposed components, as stored in an IRI parquet file.
 *
 * @param iri   the IRI exactly as it was read from the RDF input
 * @param parts the RFC 3986 components of {@code iri}
 */
public record IriRecord(String iri, IriParts parts) {

    /**
     * Decomposes an IRI string into a record.
     *
     * @param iri an absolute IRI
     * @return the record for that IRI
     * @throws IllegalArgumentException if the IRI cannot be decomposed, see {@link IriParts#parse(String)}
     */
    public static IriRecord of(String iri) {
        return new IriRecord(iri, IriParts.parse(iri));
    }
}

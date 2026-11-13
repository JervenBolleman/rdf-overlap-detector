package swiss.sib.rdfoverlap;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

/**
 * One RDF file to extract: a local file, a remote URL, or an entry of a Metalink file.
 *
 * @param displayName how the source is named in messages
 * @param uris        where the file can be read from, in the order to try them (mirrors)
 * @param checksums   checksums the content must match, possibly none
 */
public record RdfSource(String displayName, List<URI> uris, List<Checksum> checksums) {

    /**
     * A checksum of the file as it is served, before any decompression.
     *
     * @param algorithm a MessageDigest algorithm name, such as SHA-256
     * @param hex       the expected digest in hexadecimal
     */
    public record Checksum(String algorithm, String hex) {
    }

    public static RdfSource local(Path file) {
        return new RdfSource(file.toString(), List.of(file.toAbsolutePath().toUri()), List.of());
    }

    public static RdfSource remote(URI url) {
        return new RdfSource(url.toString(), List.of(url), List.of());
    }
}

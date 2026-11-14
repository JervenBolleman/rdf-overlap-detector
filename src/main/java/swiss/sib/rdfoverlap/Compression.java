package swiss.sib.rdfoverlap;

import com.github.luben.zstd.ZstdInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import org.tukaani.xz.XZInputStream;

/**
 * Removes compression layers from an input by its file name: {@code .gz}, {@code .xz} and {@code .zst}.
 *
 * <p>The decoders are called directly, not through RDF4j's reflective lookup, so a native image includes them.
 * Layers are removed while the name still ends with a compression suffix, so {@code x.nt.gz.xz} is read as
 * {@code x.nt}.
 */
final class Compression {

    /** The decoded stream, and the name it has once the compression suffixes are removed. */
    record Decoded(InputStream stream, String name) {
    }

    private static final int MAX_LAYERS = 4;

    private Compression() {
    }

    static Decoded decodeAll(InputStream in, String name) throws IOException {
        InputStream stream = in;
        String current = name;
        for (int layer = 0; layer < MAX_LAYERS; layer++) {
            Decoded next = decodeOne(stream, current);
            if (next == null) {
                break;
            }
            stream = next.stream();
            current = next.name();
        }
        return new Decoded(stream, current);
    }

    private static Decoded decodeOne(InputStream in, String name) throws IOException {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".gz") && name.length() > 3) {
            return new Decoded(new GZIPInputStream(in, 1 << 16), strip(name, 3));
        }
        if (lower.endsWith(".xz") && name.length() > 3) {
            return new Decoded(new XZInputStream(in), strip(name, 3));
        }
        if (lower.endsWith(".zst") && name.length() > 4) {
            NativeCodecs.loadZstdIfNative();
            return new Decoded(new ZstdInputStream(in), strip(name, 4));
        }
        return null;
    }

    private static String strip(String name, int suffixLength) {
        return name.substring(0, name.length() - suffixLength);
    }
}

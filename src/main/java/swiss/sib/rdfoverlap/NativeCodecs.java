package swiss.sib.rdfoverlap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Loads the zstd-jni native library when running as a GraalVM native image.
 *
 * <p>zstd-jni finds its library inside its own jar, which a native image cannot do. The native build therefore copies
 * the library for each platform into the image as {@code native/<os>-<arch>/libzstd-jni.<ext>}. This class extracts
 * that copy, loads it with {@link System#load}, and then tells zstd-jni that the library is loaded.
 *
 * <p>On the JVM this does nothing, because zstd-jni loads its library from the jar as usual.
 */
final class NativeCodecs {

    private static boolean zstdReady;

    private NativeCodecs() {
    }

    /**
     * Makes zstd-jni usable. Call it before the first zstd use. It is cheap after the first call.
     *
     * @throws IOException if running as a native image and the library cannot be found or loaded
     */
    static synchronized void loadZstdIfNative() throws IOException {
        if (zstdReady || !inImageCode()) {
            return;
        }
        String resource = resourceName();
        try (InputStream in = NativeCodecs.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("no zstd native library for this platform in the image: " + resource);
            }
            byte[] library = in.readAllBytes();
            String extension = resource.substring(resource.lastIndexOf('.'));
            load(library, extension, Path.of(System.getProperty("java.io.tmpdir")));
        }
        com.github.luben.zstd.util.Native.assumeLoaded();
        zstdReady = true;
    }

    /**
     * Writes the library into a directory and loads it. The temp directory is tried first. A user's home directory is
     * the fallback, for systems that mount the temp directory noexec.
     */
    private static void load(byte[] library, String extension, Path tempDir) throws IOException {
        UnsatisfiedLinkError first;
        try {
            loadFrom(library, extension, tempDir);
            return;
        } catch (UnsatisfiedLinkError e) {
            first = e;
        }
        Path home = Path.of(System.getProperty("user.home"), ".rdf-overlap-detector", "native");
        try {
            loadFrom(library, extension, home);
        } catch (UnsatisfiedLinkError e) {
            e.addSuppressed(first);
            throw new IOException("cannot load the zstd native library from " + tempDir + " or " + home, e);
        }
    }

    private static void loadFrom(byte[] library, String extension, Path dir) throws IOException {
        Files.createDirectories(dir);
        Path file = Files.createTempFile(dir, "libzstd-jni-", extension);
        file.toFile().deleteOnExit();
        Files.write(file, library);
        System.load(file.toAbsolutePath().toString());
    }

    /** The resource holding this platform's library, for example native/linux-x86_64/libzstd-jni.so. */
    static String resourceName() {
        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String os;
        String extension;
        if (osName.contains("linux")) {
            os = "linux";
            extension = ".so";
        } else if (osName.contains("mac")) {
            os = "macos";
            extension = ".dylib";
        } else if (osName.contains("windows")) {
            os = "windows";
            extension = ".dll";
        } else {
            throw new IllegalStateException("no zstd native library for operating system " + osName);
        }
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String architecture = switch (arch) {
            case "aarch64", "arm64" -> "aarch64";
            case "amd64", "x86_64" -> "x86_64";
            default -> throw new IllegalStateException("no zstd native library for architecture " + arch);
        };
        return "native/" + os + "-" + architecture + "/libzstd-jni" + extension;
    }

    /** True when running as a GraalVM native image, detected without a compile-time dependency on GraalVM. */
    static boolean inImageCode() {
        try {
            Class<?> info = Class.forName("org.graalvm.nativeimage.ImageInfo");
            Object result = info.getMethod("inImageCode").invoke(null);
            return result instanceof Boolean b && b;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }
}

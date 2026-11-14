package swiss.sib.rdfoverlap;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Opens RDF sources: local files, http(s) URLs and Metalink files.
 *
 * <p>Metalink files are recognised by name, {@code .meta4} for Metalink 4 (RFC 5854) and {@code .metalink} for
 * Metalink 3. Both versions list files with their URLs and checksums, and both are read here.
 */
final class RdfSources {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /** Checksum types as written in Metalink files, mapped to MessageDigest names. */
    private static final Map<String, String> ALGORITHMS = Map.of(
            "md5", "MD5",
            "sha1", "SHA-1",
            "sha256", "SHA-256",
            "sha512", "SHA-512");

    private RdfSources() {
    }

    /** A stream that has been opened, and the URI it was opened from. */
    record Opened(URI uri, InputStream stream) {
    }

    static boolean isHttp(String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    static boolean isSsh(String input) {
        return input.toLowerCase(Locale.ROOT).startsWith("ssh://");
    }

    /** True for an input that is read over the network, by http(s) or ssh. */
    static boolean isRemote(String input) {
        return isHttp(input) || isSsh(input);
    }

    static boolean isMetalink(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".meta4") || lower.endsWith(".metalink");
    }

    /** The last path segment of a URI, used to pick the decompression and format from the file name. */
    static String fileName(URI uri) {
        String path = uri.getPath();
        if (path == null || path.isEmpty()) {
            return uri.toString();
        }
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /**
     * Opens the first location of a source that can be read. Failures on earlier locations are reported only if
     * none of them can be read.
     */
    static Opened open(RdfSource source) throws IOException {
        if (source.uris().isEmpty()) {
            throw new IOException("no location for " + source.displayName());
        }
        IOException last = null;
        for (URI uri : source.uris()) {
            try {
                return new Opened(uri, openUri(uri));
            } catch (IOException e) {
                last = e;
            }
        }
        throw last;
    }

    static InputStream openUri(URI uri) throws IOException {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        switch (scheme) {
            case "file":
                return Files.newInputStream(Path.of(uri));
            case "http":
            case "https":
                HttpRequest request = HttpRequest.newBuilder(uri).GET().build();
                HttpResponse<InputStream> response;
                try {
                    response = CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while fetching " + uri, e);
                }
                if (response.statusCode() != 200) {
                    response.body().close();
                    throw new IOException("HTTP " + response.statusCode() + " for " + uri);
                }
                return response.body();
            case "ssh":
                return openSsh(uri);
            default:
                throw new IOException("unsupported URL scheme in " + uri);
        }
    }

    /** The ssh client to run. Set rdfoverlap.ssh to use another program, which is how the tests run without a server. */
    private static String sshCommand() {
        return System.getProperty("rdfoverlap.ssh", "ssh");
    }

    /**
     * Streams a remote file by running {@code cat} on the host over ssh. The system ssh client is used, so the user's
     * ssh_config, keys, agent and known_hosts apply. Passwords are never asked for, because BatchMode is set.
     *
     * <p>The ssh URL is {@code ssh://[user@]host[:port]/absolute/path}. The host and user names must be plain, so they
     * cannot be read as ssh options. The path is quoted for the remote shell.
     */
    static InputStream openSsh(URI uri) throws IOException {
        String path = checkSsh(uri);
        return runSsh(uri, "cat -- " + shellQuote(path));
    }

    /** The files found under an ssh path, and whether the path was a directory. */
    record Listing(boolean directory, List<RdfSource> sources) {
    }

    /**
     * Lists an ssh path with find. A path that is a file lists as itself, and is then read as a single file. A
     * directory lists the regular files below it, in sorted order, and is read as a directory. A missing path is an
     * error.
     */
    static Listing listSsh(URI uri) throws IOException {
        String path = checkSsh(uri);
        byte[] output;
        try (InputStream in = runSsh(uri, "find -L -- " + shellQuote(path) + " -type f -print0")) {
            output = in.readAllBytes();
        }
        List<String> found = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < output.length; i++) {
            if (output[i] == 0) {
                found.add(new String(output, start, i - start, StandardCharsets.UTF_8));
                start = i + 1;
            }
        }
        if (found.size() == 1 && found.get(0).equals(path)) {
            return new Listing(false, List.of(RdfSource.remote(uri)));
        }
        found.sort(null);
        List<RdfSource> sources = new ArrayList<>();
        for (String file : found) {
            try {
                sources.add(RdfSource.remote(new URI("ssh", uri.getUserInfo(), uri.getHost(), uri.getPort(), file,
                        null, null)));
            } catch (java.net.URISyntaxException e) {
                throw new IOException("cannot make an ssh URL for " + file, e);
            }
        }
        return new Listing(true, sources);
    }

    /** Checks the parts of an ssh URL that go onto the command line, and returns its absolute path. */
    private static String checkSsh(URI uri) throws IOException {
        String host = uri.getHost();
        String user = uri.getUserInfo();
        int port = uri.getPort();
        String path = uri.getPath();
        if (host == null || !SSH_HOST.matcher(host).matches()) {
            throw new IOException("invalid host in ssh URL: " + uri);
        }
        if (user != null && !SSH_USER.matcher(user).matches()) {
            throw new IOException("invalid user in ssh URL: " + uri);
        }
        if (port != -1 && (port < 1 || port > 65535)) {
            throw new IOException("invalid port in ssh URL: " + uri);
        }
        if (path == null || !path.startsWith("/") || path.length() == 1) {
            throw new IOException("ssh URL needs an absolute path: " + uri);
        }
        return path;
    }

    /** Runs one command on the host of an ssh URL, and returns its output. */
    private static InputStream runSsh(URI uri, String remoteCommand) throws IOException {
        List<String> command = new ArrayList<>(List.of(sshCommand(), "-o", "BatchMode=yes", "-o", "ConnectTimeout=30"));
        if (uri.getPort() != -1) {
            command.add("-p");
            command.add(String.valueOf(uri.getPort()));
        }
        String user = uri.getUserInfo();
        command.add(user == null ? uri.getHost() : user + "@" + uri.getHost());
        command.add(remoteCommand);

        Path stderr = Files.createTempFile("rdfoverlap-ssh-", ".err");
        Process process;
        try {
            process = new ProcessBuilder(command).redirectError(stderr.toFile()).start();
        } catch (IOException e) {
            Files.deleteIfExists(stderr);
            throw new IOException("cannot run " + sshCommand() + ": " + e.getMessage(), e);
        }
        process.getOutputStream().close();
        return new SshInputStream(process, stderr);
    }

    private static final java.util.regex.Pattern SSH_HOST =
            java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9.-]*");
    private static final java.util.regex.Pattern SSH_USER =
            java.util.regex.Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]*");

    /** Quotes a path for a POSIX shell, so spaces and quotes in the path stay literal. */
    static String shellQuote(String path) {
        return "'" + path.replace("'", "'\\''") + "'";
    }

    /**
     * The output of a remote command. Reaching the end of the output and then checking the exit status turns a failed
     * command into a read error. Closing before the end stops the command, which is not an error.
     */
    static final class SshInputStream extends FilterInputStream {

        private final Process process;
        private final Path stderr;
        private boolean atEnd;

        SshInputStream(Process process, Path stderr) {
            super(process.getInputStream());
            this.process = process;
            this.stderr = stderr;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b < 0) {
                atEnd = true;
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = super.read(buffer, offset, length);
            if (n < 0) {
                atEnd = true;
            }
            return n;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
                if (!atEnd) {
                    process.destroyForcibly();
                }
                int status = waitFor(process);
                if (atEnd && status != 0) {
                    throw new IOException("ssh exited with status " + status + ": " + stderrText());
                }
            } finally {
                Files.deleteIfExists(stderr);
            }
        }

        private String stderrText() throws IOException {
            String text = Files.readString(stderr).strip();
            return text.length() > 500 ? text.substring(0, 500) + "..." : text;
        }

        private static int waitFor(Process process) throws IOException {
            try {
                return process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while waiting for ssh", e);
            }
        }
    }

    /** Reads a Metalink file, local or remote, and lists its files as sources. */
    static List<RdfSource> readMetalink(URI metalink) throws IOException {
        byte[] xml;
        try (InputStream in = openUri(metalink)) {
            xml = in.readAllBytes();
        }
        return parseMetalink(xml, metalink);
    }

    /**
     * Lists the files of a Metalink document. Relative URLs are resolved against {@code base}, and each file's URLs
     * are ordered by their priority attribute, lowest first.
     */
    static List<RdfSource> parseMetalink(byte[] xml, URI base) throws IOException {
        Element root = parseXml(xml);
        if (!"metalink".equals(root.getLocalName() == null ? root.getTagName() : root.getLocalName())) {
            throw new IOException("not a Metalink document: root element is " + root.getTagName());
        }
        List<RdfSource> sources = new ArrayList<>();
        NodeList files = root.getElementsByTagNameNS("*", "file");
        for (int i = 0; i < files.getLength(); i++) {
            Element file = (Element) files.item(i);
            String name = file.getAttribute("name");
            List<Located> located = new ArrayList<>();
            for (Element url : elements(file, "url")) {
                located.add(new Located(resolve(base, url.getTextContent().trim()), priority(url)));
            }
            // Stable sort: URLs with equal priority keep their order in the document.
            located.sort(java.util.Comparator.comparingInt(Located::priority));
            List<URI> uris = located.stream().map(Located::uri).toList();
            List<RdfSource.Checksum> checksums = new ArrayList<>();
            for (Element hash : elements(file, "hash")) {
                // Piece hashes in Metalink 4 carry a piece attribute and cover parts of the file, not all of it.
                if (hash.hasAttribute("piece")) {
                    continue;
                }
                String algorithm = ALGORITHMS.get(hash.getAttribute("type").replace("-", "").toLowerCase(Locale.ROOT));
                if (algorithm != null) {
                    checksums.add(new RdfSource.Checksum(algorithm, hash.getTextContent().trim().toLowerCase(Locale.ROOT)));
                }
            }
            String displayName = !name.isEmpty() ? name : (uris.isEmpty() ? "file" : uris.get(0).toString());
            sources.add(new RdfSource(displayName, List.copyOf(uris), List.copyOf(checksums)));
        }
        return sources;
    }

    private static Element parseXml(byte[] xml) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // A Metalink file is data, not a document to expand. Refuse DTDs and external entities.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml)).getDocumentElement();
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("cannot parse Metalink file: " + e.getMessage(), e);
        }
    }

    /** Direct child elements with the given local name, in document order. */
    private static List<Element> elements(Element parent, String localName) {
        List<Element> found = new ArrayList<>();
        NodeList nodes = parent.getElementsByTagNameNS("*", localName);
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element e) {
                found.add(e);
            }
        }
        return found;
    }

    private static URI resolve(URI base, String text) throws IOException {
        URI uri;
        try {
            uri = base.resolve(new URI(text));
        } catch (java.net.URISyntaxException e) {
            throw new IOException("bad URL in Metalink file: " + text, e);
        }
        return uri;
    }

    /** A URL with the priority its url element gives it. */
    private record Located(URI uri, int priority) {
    }

    /** The priority of a url element. Lower values are tried first, and a missing value comes last. */
    private static int priority(Element url) {
        String value = url.getAttribute("priority");
        if (value.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * Passes every byte through and feeds each digest. It is the only way the content is read, so each digest sees
     * every byte exactly once, including bytes that a reader skips or pushes back.
     */
    static final class DigestingInputStream extends FilterInputStream {

        private final MessageDigest[] digests;

        DigestingInputStream(InputStream in, MessageDigest[] digests) {
            super(in);
            this.digests = digests;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                for (MessageDigest d : digests) {
                    d.update((byte) b);
                }
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = super.read(buffer, offset, length);
            if (n > 0) {
                for (MessageDigest d : digests) {
                    d.update(buffer, offset, n);
                }
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            byte[] buffer = new byte[(int) Math.min(8192, Math.max(1, n))];
            long skipped = 0;
            while (skipped < n) {
                int read = read(buffer, 0, (int) Math.min(buffer.length, n - skipped));
                if (read < 0) {
                    break;
                }
                skipped += read;
            }
            return skipped;
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        @Override
        public synchronized void mark(int readLimit) {
        }

        @Override
        public synchronized void reset() throws IOException {
            throw new IOException("mark and reset are not supported");
        }
    }

    static MessageDigest[] digestsFor(List<RdfSource.Checksum> checksums) throws IOException {
        MessageDigest[] digests = new MessageDigest[checksums.size()];
        for (int i = 0; i < digests.length; i++) {
            try {
                digests[i] = MessageDigest.getInstance(checksums.get(i).algorithm());
            } catch (NoSuchAlgorithmException e) {
                throw new IOException("no digest algorithm " + checksums.get(i).algorithm(), e);
            }
        }
        return digests;
    }

    /** Reads the rest of a stream into nowhere, so that a digest covers the whole file. */
    static void drain(InputStream in) throws IOException {
        in.transferTo(OutputStream.nullOutputStream());
    }

    /** Fails with a message if a digest does not match its expected value. */
    static void verify(List<RdfSource.Checksum> checksums, MessageDigest[] digests) throws IOException {
        for (int i = 0; i < checksums.size(); i++) {
            String actual = HexFormat.of().formatHex(digests[i].digest());
            if (!actual.equals(checksums.get(i).hex())) {
                throw new IOException("checksum mismatch: " + checksums.get(i).algorithm() + " expected "
                        + checksums.get(i).hex() + " but got " + actual);
            }
        }
    }
}

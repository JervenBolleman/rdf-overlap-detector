package swiss.sib.rdfoverlap;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Locale;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.rio.ParserConfig;
import org.eclipse.rdf4j.rio.RDFHandlerException;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler;
import org.eclipse.rdf4j.rio.helpers.LongRioSetting;
import org.eclipse.rdf4j.rio.helpers.RDFInputDispatcher;

/**
 * Streams RDF into sorted run files: one dataset, from one RDF file or from every file under a directory.
 *
 * <p>Subject and object IRIs are collected into a batch, where duplicates collapse. When the batch holds
 * {@code batchSize} distinct IRIs, it is sorted and written as one row group of one run file under
 * {@code <outDir>/runs/}. Duplicates across batches are kept here and removed later by the sort phase.
 *
 * <p>Predicate IRIs go into one set for the whole dataset, written once at the end as {@code <outDir>/predicates/
 * predicates.parquet}. Predicates repeat heavily, so a set that holds all of them stays small.
 *
 * <p>The input is one of: a local RDF file, an http(s) URL of an RDF file, a directory, or a Metalink file (local or
 * http(s)) that lists the files to read. A directory or a Metalink file is read file by file, in order. Files under
 * {@code outDir} are left out of a directory, so a dataset directory can sit inside its input directory. A file that
 * does not parse, or whose checksum does not match, is skipped and recorded in {@link ExtractionSummary#skipped()},
 * and its partial output is removed. A single file that does not parse is an error. A failure to write output is
 * always an error.
 *
 * <p>Every extraction replaces the output of the previous one, so {@code runs/} and {@code predicates/} are
 * rebuilt from scratch.
 *
 * <p>Blank nodes and literals are skipped, and so are named graph names. An IRI that {@link IriParts} cannot
 * decompose is counted in {@link ExtractionSummary#rejectedIris()} and skipped.
 *
 * <p>Sorting uses {@link String#compareTo}, which is the same order the merge phase must use.
 */
public final class IriExtractor {

    private static final Logger LOG = LoggerFactory.getLogger(IriExtractor.class);

    public static final int DEFAULT_BATCH_SIZE = 10_000;

    /** At most this many sources are read at once. */
    static final int MAX_READERS = 4;

    /** Makes the next extraction fail after {@code files} sources. For the tests of resuming. */
    IriExtractor failAfter(int files) {
        this.failAfterFiles = files;
        return this;
    }

    /** Rows per row group in the output. A merge holds one row group in memory at a time. */
    static final int ROW_GROUP_ROWS = 1_000_000;

    /**
     * The JDK's bundled jaxp.properties caps XML entity sizes and replacements at 100,000. Real RDF/XML dumps have
     * longer literals, such as Rhea's htmlEquation values, which fail under that cap. The input is a dump the user
     * chose, so these limits are raised to 10 million, unless the user has already set them. The expansion-count
     * limit and the RDF4j decompression limits still apply.
     */
    private static final String XML_LIMIT_PREFIX = "jdk.xml.";
    private static final String XML_LIMIT = "10000000";

    static {
        for (String name : List.of("maxGeneralEntitySizeLimit", "totalEntitySizeLimit", "entityReplacementLimit")) {
            if (System.getProperty(XML_LIMIT_PREFIX + name) == null) {
                System.setProperty(XML_LIMIT_PREFIX + name, XML_LIMIT);
            }
        }
    }

    /** Decompression limits, set explicitly so they do not depend on RDF4j's defaults. */
    private static final long MAX_EXPANDED_BYTES = 4L * 1024 * 1024 * 1024;
    private static final long MAX_EXPANSION_RATIO = 200L;

    private final Path outDir;

    /** For the tests: fail once this many sources have been combined, to simulate an interrupted extraction. */
    private int failAfterFiles = -1;
    private final int batchSize;
    private final ParserConfig parserConfig;
    private final int fanIn;

    public IriExtractor(Path outDir) {
        this(outDir, DEFAULT_BATCH_SIZE);
    }

    public IriExtractor(Path outDir, int batchSize) {
        this(outDir, batchSize, RunPool.DEFAULT_FAN_IN);
    }

    /** The fan-in is how many runs one merge combines. It is exposed so the tests can force several merge levels. */
    IriExtractor(Path outDir, int batchSize, int fanIn) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1: " + batchSize);
        }
        this.outDir = outDir;
        this.batchSize = batchSize;
        this.fanIn = fanIn;
        this.parserConfig = new ParserConfig()
                .set(new LongRioSetting("org.eclipse.rdf4j.rio.loader.max_expanded_bytes", "limit", MAX_EXPANDED_BYTES),
                        MAX_EXPANDED_BYTES)
                .set(new LongRioSetting("org.eclipse.rdf4j.rio.loader.max_expansion_ratio", "limit", MAX_EXPANSION_RATIO),
                        MAX_EXPANSION_RATIO);
    }

    /**
     * A file that was not extracted.
     *
     * @param source the file, as a path or URL
     * @param reason why it could not be read as RDF
     */
    public record Skipped(String source, String reason) {
    }

    /**
     * What an extraction produced.
     *
     * @param statements   statements parsed from the files that were extracted
     * @param output       the sorted, deduplicated IRI file
     * @param distinctIris distinct IRIs in the output
     * @param batches      run files written before merging
     * @param predicates   distinct predicates across those files
     * @param rejectedIris IRIs that could not be decomposed and were skipped
     * @param files        files extracted
     * @param skipped      files that could not be read as RDF, with the reason
     */
    public record ExtractionSummary(long statements, Path output, long distinctIris, long batches, int predicates,
                                    long rejectedIris, int files, List<Skipped> skipped) {
    }

    /**
     * Extracts an RDF file, a directory of them, or the files listed in a Metalink file. The input may be a local path
     * or an http(s) URL.
     *
     * @param input a local path or an http(s) URL
     * @return counts, the run files written, and the files skipped
     * @throws IOException if the input cannot be read, a file cannot be written, or a single input file is not RDF
     */
    public ExtractionSummary extract(String input) throws IOException {
        return extract(input, false);
    }

    /**
     * Extracts an input, and with {@code resume} continues an earlier extraction of the same input that did not finish.
     * Files that were read completely before are not read again.
     *
     * @param input  a local path or an http(s) or ssh URL
     * @param resume true to continue an unfinished extraction, false to start afresh
     * @return counts, the output file, and the files skipped
     * @throws IOException if the input cannot be read, the saved state cannot be used, or a file cannot be written
     */
    public ExtractionSummary extract(String input, boolean resume) throws IOException {
        if (RdfSources.isRemote(input)) {
            URI uri = URI.create(input);
            if (RdfSources.isMetalink(RdfSources.fileName(uri))) {
                return run(RdfSources.readMetalink(uri), true, resume);
            }
            if (RdfSources.isSsh(input)) {
                RdfSources.Listing listing = RdfSources.listSsh(uri);
                return run(listing.sources(), listing.directory(), resume);
            }
            return run(List.of(RdfSource.remote(uri)), false, resume);
        }
        return extract(Path.of(input), resume);
    }

    /**
     * Extracts an RDF file, or every RDF file under a directory, or the files listed in a Metalink file.
     *
     * @param input a local RDF file, a directory, or a Metalink file
     * @return counts, the run files written, and the files skipped
     * @throws IOException if the input cannot be read, a file cannot be written, or a single input file is not RDF
     */
    public ExtractionSummary extract(Path input) throws IOException {
        return extract(input, false);
    }

    /** As {@link #extract(String, boolean)}, for a local path. */
    public ExtractionSummary extract(Path input, boolean resume) throws IOException {
        if (Files.isDirectory(input)) {
            return run(localFiles(input), true, resume);
        }
        if (RdfSources.isMetalink(input.getFileName().toString())) {
            return run(RdfSources.readMetalink(input.toAbsolutePath().toUri()), true, resume);
        }
        return run(List.of(RdfSource.local(input)), false, resume);
    }

    /**
     * Extracts the given sources in order, and merges their runs as they arrive into the dataset's sorted IRI file.
     *
     * @param sources the files to read
     * @param lenient true to skip a source that cannot be read as RDF, false to fail on it
     */
    private ExtractionSummary run(List<RdfSource> sources, boolean lenient, boolean resume) throws IOException {
        Path runsDir = outDir.resolve("runs");
        Path predicatesDir = outDir.resolve("predicates");
        Path irisDir = outDir.resolve("iris");
        Path stateDir = outDir.resolve("state");
        Path output = irisDir.resolve("iris.parquet");
        ManifestStore store = new ManifestStore(stateDir);
        ManifestStore.Manifest initial = resume ? store.load() : ManifestStore.Manifest.empty();
        if (!resume) {
            for (Path dir : List.of(runsDir, predicatesDir, irisDir, stateDir)) {
                deleteRecursively(dir);
            }
        }
        for (Path dir : List.of(runsDir, predicatesDir, irisDir, stateDir)) {
            Files.createDirectories(dir);
        }
        if (resume) {
            removeUnreferenced(initial, runsDir, stateDir);
        }
        // A file listed twice is read once. Its key is its identity, so its run files have one name each.
        Map<String, RdfSource> unique = new LinkedHashMap<>();
        for (RdfSource source : sources) {
            unique.putIfAbsent(keyOrName(source), source);
        }
        List<Map.Entry<String, RdfSource>> inputs = new ArrayList<>(unique.entrySet());
        Map<String, ManifestStore.CompletedFile> done = new HashMap<>();
        Progress progress = new Progress(inputs.size());
        for (ManifestStore.CompletedFile c : initial.completed()) {
            done.put(c.key(), c);
        }
        if (resume) {
            logResumeState(initial, inputs, done);
        }

        RunPool pool = new RunPool(runsDir, output, fanIn, ROW_GROUP_ROWS, RunPool.defaultThreads(), store, initial);
        ExecutorService readers = Executors.newFixedThreadPool(readerThreads(sources.size()), runnable -> {
            Thread thread = new Thread(runnable, "iri-reader");
            thread.setDaemon(true);
            return thread;
        });
        Set<IriParts> predicates = new TreeSet<>();
        List<Skipped> skipped = new ArrayList<>();
        long statements = 0;
        long rejected = 0;
        long batches = 0;
        int extracted = 0;
        try {
            // Sources are read in parallel, but their results are combined in source order, so the output does not
            // depend on which file finishes first.
            List<Future<FileResult>> results = new ArrayList<>();
            for (int i = 0; i < inputs.size(); i++) {
                String key = inputs.get(i).getKey();
                RdfSource source = inputs.get(i).getValue();
                int index = i;
                results.add(readers.submit(() -> read(source, key, index, lenient, pool, runsDir, stateDir, done,
                        progress)));
            }
            for (int i = 0; i < results.size(); i++) {
                if (i == failAfterFiles) {
                    throw new IllegalStateException("simulated failure after " + i + " files");
                }
                FileResult result;
                try {
                    result = results.get(i).get();
                } catch (ExecutionException e) {
                    throw rethrow(e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while reading sources", e);
                }
                if (result.skipped() != null) {
                    skipped.add(result.skipped());
                    continue;
                }
                statements += result.statements();
                rejected += result.rejectedIris();
                batches += result.batches();
                predicates.addAll(result.predicates());
                extracted++;
            }

            writePredicates(predicatesDir.resolve("predicates.parquet"), predicates);
            long distinct = pool.finish();
            deleteRecursively(stateDir);
            deleteRecursively(runsDir);
            return new ExtractionSummary(statements, output, distinct, batches, predicates.size(), rejected,
                    extracted, skipped);
        } catch (IOException | RuntimeException e) {
            // The state is kept, so the extraction can be resumed. Only the output is absent, so nothing reads the
            // partial dataset as complete. Wait for the readers to stop before the pool is stopped.
            readers.shutdownNow();
            try {
                readers.awaitTermination(1, TimeUnit.MINUTES);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            pool.abort();
            throw e;
        } finally {
            readers.shutdown();
        }
    }

    /** Logs what a resume found in the saved state, so the start of the run shows how much is left to do. */
    private static void logResumeState(ManifestStore.Manifest state, List<Map.Entry<String, RdfSource>> inputs,
                                       Map<String, ManifestStore.CompletedFile> done) {
        int runs = state.sharedLevels().stream().mapToInt(List::size).sum();
        if (state.completed().isEmpty() && runs == 0) {
            LOG.info("resume requested but no saved state was found, starting afresh");
            return;
        }
        long completed = inputs.stream().filter(input -> done.containsKey(input.getKey())).count();
        LOG.info("resuming: {} of {} inputs already read completely, {} remaining; {} runs in {} shared levels",
                completed, inputs.size(), inputs.size() - completed, runs, state.sharedLevels().size());
    }

    /**
     * Prepares a resume. A file that the saved state names must still exist, or the state is damaged. Every other run
     * and predicate file is left over from an unfinished input, and is deleted.
     */
    private static void removeUnreferenced(ManifestStore.Manifest state, Path runsDir, Path stateDir)
            throws IOException {
        Set<String> runs = new HashSet<>();
        for (List<String> level : state.sharedLevels()) {
            runs.addAll(level);
        }
        Set<String> predicates = new HashSet<>();
        for (ManifestStore.CompletedFile c : state.completed()) {
            predicates.add(c.predicatesFile());
        }
        for (String name : runs) {
            if (!Files.exists(runsDir.resolve(name))) {
                throw new IOException("the saved state names " + name + ", which is missing; run without --resume");
            }
        }
        for (String name : predicates) {
            if (!Files.exists(stateDir.resolve(name))) {
                throw new IOException("the saved state names " + name + ", which is missing; run without --resume");
            }
        }
        deleteUnlisted(runsDir, runs);
        try (Stream<Path> files = Files.list(stateDir)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (name.startsWith("pred-") && !predicates.contains(name)) {
                    Files.delete(file);
                }
            }
        }
    }

    private static void deleteUnlisted(Path dir, Set<String> keep) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
                if (!keep.contains(file.getFileName().toString())) {
                    Files.delete(file);
                }
            }
        }
    }

    /**
     * The identity of an input, which a resume uses to recognise it. A local file is identified by its path, size and
     * modification time, so a changed file is read again. A remote file is identified by its URL.
     */
    static String keyOf(RdfSource source) throws IOException {
        URI uri = source.uris().isEmpty() ? null : source.uris().get(0);
        if (uri == null) {
            return "none:" + source.displayName();
        }
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            Path file = Path.of(uri);
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            return "file:" + file.toAbsolutePath() + "|" + attributes.size() + "|"
                    + attributes.lastModifiedTime().toMillis();
        }
        return uri.toString();
    }

    /** The key of a source, or a name made from its display name when the key cannot be made, such as a missing file. */
    private static String keyOrName(RdfSource source) {
        StringBuilder key = new StringBuilder();
        try {
            key.append(keyOf(source));
        } catch (IOException e) {
            key.append("unreadable:").append(source.displayName());
        }
        // The expected checksums are part of the identity. Entries for one file that expect different content are
        // checked separately, and identical entries are read once.
        for (RdfSource.Checksum checksum : source.checksums()) {
            key.append('|').append(checksum.algorithm()).append('=').append(checksum.hex());
        }
        return key.toString();
    }

    /** A short name for an input, from its key, used in the names of its run and predicate files. */
    static String tokenOf(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * Reads one source, and hands its runs to the pool. A source that was read completely before is restored from the
     * saved state instead. A source that cannot be read as RDF is discarded and, when the extraction is lenient, reported
     * as skipped. A failure to write output is always thrown.
     */
    private FileResult read(RdfSource source, String key, int index, boolean lenient, RunPool pool, Path runsDir,
                            Path stateDir, Map<String, ManifestStore.CompletedFile> done, Progress progress)
            throws IOException {
        ManifestStore.CompletedFile record = done.get(key);
        if (record != null) {
            progress.startFile();
            LOG.info("restored {} from the saved state ({})", source.displayName(), progress.finishFile());
            return restored(record, stateDir);
        }
        long started = System.nanoTime();
        LOG.info("started {} ({})", source.displayName(), progress.startFile());
        String token = tokenOf(key);
        Collector collector = new Collector(index, token, pool, runsDir);
        try {
            parse(source, collector);
        } catch (IOException | RuntimeException e) {
            if (collector.writeFailure != null) {
                throw collector.writeFailure;
            }
            pool.discard(index);
            if (!lenient) {
                throw e;
            }
            LOG.warn("skipped {} after {}: not RDF: {} ({})", source.displayName(), elapsed(started), reason(e),
                    progress.finishFile());
            return FileResult.skippedWith(new Skipped(source.displayName(), reason(e)));
        }
        // The last partial batch is still in memory. Write it before the file counts as parsed.
        collector.finish();
        // The predicates are saved before the file is committed, so a committed file always has them.
        String predicatesFile = "pred-" + token + ".parquet";
        writePredicates(stateDir.resolve(predicatesFile), collector.predicates.values().stream().collect(
                java.util.stream.Collectors.toCollection(TreeSet::new)));
        pool.commit(index, new ManifestStore.CompletedFile(key, collector.statements, collector.rejectedIris,
                collector.batches, predicatesFile));
        LOG.info("done {} in {}: {} statements, {} batches ({})", source.displayName(), elapsed(started),
                collector.statements, collector.batches, progress.finishFile());
        return new FileResult(collector.statements, collector.rejectedIris, collector.batches,
                Set.copyOf(collector.predicates.values()), null);
    }

    /** The time since a start, in seconds, for a log message. */
    private static String elapsed(long startNanos) {
        return String.format(Locale.ROOT, "%.2f s", (System.nanoTime() - startNanos) / 1e9);
    }

    /** A source read completely in an earlier run: its counts and predicates come from the saved state. */
    private static FileResult restored(ManifestStore.CompletedFile record, Path stateDir) throws IOException {
        Set<IriParts> predicates = new TreeSet<>();
        IriParquetReader.forEach(stateDir.resolve(record.predicatesFile()), r -> predicates.add(r));
        return new FileResult(record.statements(), record.rejectedIris(), record.batches(), predicates, null);
    }

    /** Counts the files of one extraction that have started and finished, for the log. */
    private static final class Progress {

        private final int total;
        private final AtomicInteger started = new AtomicInteger();
        private final AtomicInteger finished = new AtomicInteger();

        Progress(int total) {
            this.total = total;
        }

        /** Counts a file as started, and returns the counts for the log. */
        String startFile() {
            return started.incrementAndGet() + " of " + total + " started, " + finished.get() + " finished";
        }

        /** Counts a file as finished, and returns the counts for the log. */
        String finishFile() {
            return finished.incrementAndGet() + " of " + total + " finished, " + started.get() + " started";
        }
    }

    /** What reading one source produced: its counts, or the reason it was skipped. */
    private record FileResult(long statements, long rejectedIris, long batches, Set<IriParts> predicates,
                              Skipped skipped) {

        static FileResult skippedWith(Skipped skipped) {
            return new FileResult(0, 0, 0, Set.of(), skipped);
        }
    }

    /** How many sources are read at once: up to four, and no more than the machine can run in parallel. */
    static int readerThreads(int sources) {
        return Math.max(1, Math.min(Math.min(MAX_READERS, RunPool.defaultThreads()), sources));
    }

    /** The exception to throw for a failure in a reader, keeping its type. */
    private static RuntimeException rethrow(Throwable cause) throws IOException {
        if (cause instanceof IOException io) {
            throw io;
        }
        if (cause instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        throw new IOException("reading a source failed", cause);
    }

    /**
     * Parses one source into the collector. Opening, reading, parsing and checksum verification all happen here.
     * Throws on input that is not RDF, on read errors, and on a checksum mismatch.
     */
    private void parse(RdfSource source, Collector collector) throws IOException {
        RdfSources.Opened opened = RdfSources.open(source);
        List<RdfSource.Checksum> checksums = source.checksums();
        MessageDigest[] digests = RdfSources.digestsFor(checksums);
        String name = RdfSources.fileName(opened.uri());
        // The URI the file was read from is the base, so same-document references such as rdf:about="" resolve.
        String base = opened.uri().toString();
        try (InputStream raw = opened.stream()) {
            // The digest covers the bytes as served, so it sits below the decompression.
            InputStream served = checksums.isEmpty() ? raw : new RdfSources.DigestingInputStream(raw, digests);
            Compression.Decoded decoded = Compression.decodeAll(served, name);
            new RDFInputDispatcher(parserConfig).dispatch(decoded.stream(), decoded.name(), null,
                    (stream, sourceName, format) -> {
                        RDFParser parser = Rio.createParser(format, SimpleValueFactory.getInstance());
                        parser.setParserConfig(parserConfig);
                        parser.setRDFHandler(collector);
                        parser.parse(stream, base);
                    });
            // The parser may stop before the end of the file. Read the rest, so the digests cover the whole file, and
            // so that an ssh command's failure is seen at the end of its output.
            RdfSources.drain(served);
            RdfSources.verify(checksums, digests);
        }
    }

    /** The regular files under a directory, sorted, leaving out this extraction's own output. */
    private List<RdfSource> localFiles(Path dir) throws IOException {
        Path output = outDir.toAbsolutePath().normalize();
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> !p.toAbsolutePath().normalize().startsWith(output))
                    .sorted()
                    .map(RdfSource::local)
                    .toList();
        }
    }

    private static String reason(Exception e) {
        String message = e.getMessage();
        String firstLine = message == null ? "" : message.lines().findFirst().orElse("");
        return e.getClass().getSimpleName() + (firstLine.isEmpty() ? "" : ": " + firstLine);
    }

    private static void writePredicates(Path file, Set<IriParts> predicates) throws IOException {
        try (IriParquetWriter writer = new IriParquetWriter(file)) {
            for (IriParts parts : predicates) {
                writer.write(parts);
            }
            if (!predicates.isEmpty()) {
                writer.endRowGroup();
            }
        }
    }

    /** Holds the batch of one input file, and sends each run it writes to the run pool. */
    private final class Collector extends AbstractRDFHandler {

        private final int fileIndex;
        private final String token;
        private final RunPool pool;
        private final Path runsDir;

        /** Batch of distinct IRIs and their parts, keyed by IRI. */
        private final Map<String, IriParts> batch = new HashMap<>();
        private final Map<String, IriParts> predicates = new HashMap<>();
        private long statements;
        private long rejectedIris;
        private int batches;

        /** Set when a run cannot be written or handed on. Such a failure stops the whole extraction. */
        private IOException writeFailure;

        Collector(int fileIndex, String token, RunPool pool, Path runsDir) {
            this.fileIndex = fileIndex;
            this.token = token;
            this.pool = pool;
            this.runsDir = runsDir;
        }

        @Override
        public void handleStatement(Statement st) throws RDFHandlerException {
            statements++;
            if (st.getSubject() instanceof IRI subject) {
                addIri(subject.stringValue());
            }
            addPredicate(st.getPredicate().stringValue());
            if (st.getObject() instanceof IRI object) {
                addIri(object.stringValue());
            }
        }

        private void addIri(String iri) throws RDFHandlerException {
            if (batch.containsKey(iri)) {
                return;
            }
            IriParts parts = parts(iri);
            if (parts == null) {
                return;
            }
            // Flush only when a new distinct IRI arrives and the batch is already full, so a batch of exactly
            // batchSize IRIs is one run and not a full run plus a stray duplicate.
            if (batch.size() >= batchSize) {
                flushBatch();
            }
            batch.put(iri, parts);
        }

        private void addPredicate(String iri) {
            if (predicates.containsKey(iri)) {
                return;
            }
            IriParts parts = parts(iri);
            if (parts != null) {
                predicates.put(iri, parts);
            }
        }

        /** Returns the parts, or null and a count when the IRI cannot be decomposed. */
        private IriParts parts(String iri) {
            try {
                return IriParts.parse(iri);
            } catch (IllegalArgumentException e) {
                rejectedIris++;
                return null;
            }
        }

        private void flushBatch() throws RDFHandlerException {
            if (batch.isEmpty()) {
                return;
            }
            List<IriParts> sorted = new ArrayList<>(batch.values());
            sorted.sort(null);
            batches++;
            Path run = runsDir.resolve(token + String.format("-run-%06d.parquet", batches));
            try (IriParquetWriter writer = new IriParquetWriter(run)) {
                for (IriParts parts : sorted) {
                    writer.write(parts);
                }
                writer.endRowGroup();
            } catch (IOException e) {
                writeFailure = e;
                throw new RDFHandlerException("cannot write " + run, e);
            }
            // Hand the run on only once it is closed, so the merger never reads a file that is still being written.
            try {
                pool.run(fileIndex, run);
            } catch (IOException e) {
                writeFailure = e;
                throw new RDFHandlerException("cannot merge " + run, e);
            }
            batch.clear();
        }

        /** Writes the last partial batch. Any failure here is a write failure. */
        void finish() throws IOException {
            try {
                flushBatch();
            } catch (RDFHandlerException e) {
                throw writeFailure != null ? writeFailure : new IOException(e);
            }
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}

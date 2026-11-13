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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
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

    public static final int DEFAULT_BATCH_SIZE = 10_000;

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
    private final int batchSize;
    private final ParserConfig parserConfig;

    public IriExtractor(Path outDir) {
        this(outDir, DEFAULT_BATCH_SIZE);
    }

    public IriExtractor(Path outDir, int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1: " + batchSize);
        }
        this.outDir = outDir;
        this.batchSize = batchSize;
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
     * @param statements  statements parsed from the files that were extracted
     * @param runs        the run files written, in order
     * @param predicates  distinct predicates across those files
     * @param rejectedIris IRIs that could not be decomposed and were skipped
     * @param files       files extracted
     * @param skipped     files that could not be read as RDF, with the reason
     */
    public record ExtractionSummary(long statements, List<Path> runs, int predicates, long rejectedIris,
                                    int files, List<Skipped> skipped) {
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
        if (RdfSources.isHttp(input)) {
            URI uri = URI.create(input);
            if (RdfSources.isMetalink(RdfSources.fileName(uri))) {
                return run(RdfSources.readMetalink(uri), true);
            }
            return run(List.of(RdfSource.remote(uri)), false);
        }
        return extract(Path.of(input));
    }

    /**
     * Extracts an RDF file, or every RDF file under a directory, or the files listed in a Metalink file.
     *
     * @param input a local RDF file, a directory, or a Metalink file
     * @return counts, the run files written, and the files skipped
     * @throws IOException if the input cannot be read, a file cannot be written, or a single input file is not RDF
     */
    public ExtractionSummary extract(Path input) throws IOException {
        if (Files.isDirectory(input)) {
            return run(localFiles(input), true);
        }
        if (RdfSources.isMetalink(input.getFileName().toString())) {
            return run(RdfSources.readMetalink(input.toAbsolutePath().toUri()), true);
        }
        return run(List.of(RdfSource.local(input)), false);
    }

    /**
     * Extracts the given sources in order.
     *
     * @param sources the files to read
     * @param lenient true to skip a source that cannot be read as RDF, false to fail on it
     */
    private ExtractionSummary run(List<RdfSource> sources, boolean lenient) throws IOException {
        Path runsDir = outDir.resolve("runs");
        Path predicatesDir = outDir.resolve("predicates");
        deleteRecursively(runsDir);
        deleteRecursively(predicatesDir);
        Files.createDirectories(runsDir);
        Files.createDirectories(predicatesDir);

        Map<String, IriParts> predicates = new TreeMap<>();
        List<Path> runs = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        long statements = 0;
        long rejected = 0;
        int extracted = 0;
        int index = 0;
        for (RdfSource source : sources) {
            index++;
            Collector collector = new Collector(String.format("f%06d-", index));
            try {
                parse(source, collector);
            } catch (IOException | RuntimeException e) {
                if (collector.writeFailure != null) {
                    deleteRecursively(runsDir);
                    deleteRecursively(predicatesDir);
                    throw collector.writeFailure;
                }
                deleteFiles(collector.runs);
                if (!lenient) {
                    deleteRecursively(runsDir);
                    deleteRecursively(predicatesDir);
                    throw e;
                }
                skipped.add(new Skipped(source.displayName(), reason(e)));
                continue;
            }
            try {
                collector.finish();
            } catch (IOException e) {
                deleteRecursively(runsDir);
                deleteRecursively(predicatesDir);
                throw e;
            }
            runs.addAll(collector.runs);
            statements += collector.statements;
            rejected += collector.rejectedIris;
            collector.predicates.forEach(predicates::putIfAbsent);
            extracted++;
        }

        writePredicates(predicatesDir.resolve("predicates.parquet"), predicates);
        return new ExtractionSummary(statements, runs, predicates.size(), rejected, extracted, skipped);
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
            InputStream in = checksums.isEmpty() ? raw : new RdfSources.DigestingInputStream(raw, digests);
            new RDFInputDispatcher(parserConfig).dispatch(in, name, null,
                    (stream, sourceName, format) -> {
                        RDFParser parser = Rio.createParser(format, SimpleValueFactory.getInstance());
                        parser.setParserConfig(parserConfig);
                        parser.setRDFHandler(collector);
                        parser.parse(stream, base);
                    });
            if (!checksums.isEmpty()) {
                // The parser may stop before the end of the file, so read the rest before comparing digests.
                RdfSources.drain(in);
                RdfSources.verify(checksums, digests);
            }
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

    private static void writePredicates(Path file, Map<String, IriParts> predicates) throws IOException {
        try (IriParquetWriter writer = new IriParquetWriter(file)) {
            for (Map.Entry<String, IriParts> e : predicates.entrySet()) {
                writer.write(new IriRecord(e.getKey(), e.getValue()));
            }
            if (!predicates.isEmpty()) {
                writer.endRowGroup();
            }
        }
    }

    /** Holds the batch and the output of one input file. */
    private final class Collector extends AbstractRDFHandler {

        private final String runPrefix;

        /** Batch of distinct IRIs and their parts, keyed by IRI. */
        private final Map<String, IriParts> batch = new HashMap<>();
        private final Map<String, IriParts> predicates = new HashMap<>();
        private final List<Path> runs = new ArrayList<>();
        private long statements;
        private long rejectedIris;

        /** Set when a run cannot be written. Such a failure stops the whole extraction. */
        private IOException writeFailure;

        Collector(String runPrefix) {
            this.runPrefix = runPrefix;
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
            List<String> sorted = new ArrayList<>(batch.keySet());
            sorted.sort(null);
            Path run = outDir.resolve("runs").resolve(runPrefix + String.format("run-%06d.parquet", runs.size() + 1));
            try (IriParquetWriter writer = new IriParquetWriter(run)) {
                for (String iri : sorted) {
                    writer.write(new IriRecord(iri, batch.get(iri)));
                }
                writer.endRowGroup();
            } catch (IOException e) {
                writeFailure = e;
                throw new RDFHandlerException("cannot write " + run, e);
            }
            runs.add(run);
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

    private static void deleteFiles(List<Path> files) throws IOException {
        for (Path file : files) {
            Files.deleteIfExists(file);
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

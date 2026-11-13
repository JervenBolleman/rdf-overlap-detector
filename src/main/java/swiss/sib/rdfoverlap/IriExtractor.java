package swiss.sib.rdfoverlap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.rio.ParserConfig;
import org.eclipse.rdf4j.rio.RDFHandlerException;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler;
import org.eclipse.rdf4j.rio.helpers.LongRioSetting;
import org.eclipse.rdf4j.rio.helpers.RDFInputDispatcher;

/**
 * Streams one RDF dataset and writes its IRIs to sorted run files.
 *
 * <p>Subject and object IRIs are collected into a batch, where duplicates collapse. When the batch holds
 * {@code batchSize} distinct IRIs, it is sorted and written as one row group of one run file under
 * {@code <outDir>/runs/}. Duplicates across batches are kept here and removed later by the sort phase.
 *
 * <p>Predicate IRIs go into their own set, which is written once at the end as {@code <outDir>/predicates/
 * predicates.parquet}. Predicates repeat heavily, so a set that holds all of them stays small.
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

    /** What an extraction produced. */
    public record ExtractionSummary(long statements, List<Path> runs, int predicates, long rejectedIris) {
    }

    /**
     * Extracts one RDF file, plain or compressed, into {@code outDir}.
     *
     * @param input an RDF file, in any format and compression that RDFInputDispatcher recognises
     * @return counts and the run files written
     * @throws IOException if the input cannot be read or the output cannot be written
     */
    public ExtractionSummary extract(Path input) throws IOException {
        Path runsDir = outDir.resolve("runs");
        Files.createDirectories(runsDir);
        Files.createDirectories(outDir.resolve("predicates"));
        Collector collector = new Collector();
        // The file's own URI is the base, so same-document references such as rdf:about="" resolve.
        String base = input.toAbsolutePath().toUri().toString();
        try {
            try (InputStream in = Files.newInputStream(input)) {
                new RDFInputDispatcher(parserConfig).dispatch(in, input.getFileName().toString(), null,
                        (stream, sourceName, format) -> {
                            RDFParser parser = Rio.createParser(format, org.eclipse.rdf4j.model.impl.SimpleValueFactory.getInstance());
                            parser.setParserConfig(parserConfig);
                            parser.setRDFHandler(collector);
                            parser.parse(stream, base);
                        });
            }
            collector.finish();
        } catch (IOException | RuntimeException e) {
            // A failed extraction must not leave runs that a later sort would treat as complete.
            deleteRecursively(runsDir);
            throw e;
        }
        return new ExtractionSummary(collector.statements, collector.runs, collector.predicates.size(),
                collector.rejectedIris);
    }

    /** Holds the state of one extraction. */
    private final class Collector extends AbstractRDFHandler {

        /** Batch of distinct IRIs and their parts, keyed by IRI. */
        private final Map<String, IriParts> batch = new HashMap<>();
        private final Map<String, IriParts> predicates = new HashMap<>();
        private final List<Path> runs = new ArrayList<>();
        private long statements;
        private long rejectedIris;

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
            Path run = outDir.resolve("runs").resolve(String.format("run-%06d.parquet", runs.size() + 1));
            try (IriParquetWriter writer = new IriParquetWriter(run)) {
                for (String iri : sorted) {
                    writer.write(new IriRecord(iri, batch.get(iri)));
                }
                writer.endRowGroup();
            } catch (IOException e) {
                throw new RDFHandlerException("cannot write " + run, e);
            }
            runs.add(run);
            batch.clear();
        }

        /** Writes the last partial batch and the predicate file. */
        void finish() throws IOException {
            try {
                flushBatch();
            } catch (RDFHandlerException e) {
                throw unwrap(e);
            }
            List<String> sorted = new ArrayList<>(predicates.keySet());
            sorted.sort(null);
            try (IriParquetWriter writer = new IriParquetWriter(outDir.resolve("predicates").resolve("predicates.parquet"))) {
                for (String iri : sorted) {
                    writer.write(new IriRecord(iri, predicates.get(iri)));
                }
                writer.endRowGroup();
            }
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    private static IOException unwrap(RDFHandlerException e) {
        return e.getCause() instanceof IOException io ? io : new IOException(e);
    }
}

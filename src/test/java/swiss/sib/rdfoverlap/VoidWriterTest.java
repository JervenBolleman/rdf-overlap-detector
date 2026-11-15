package swiss.sib.rdfoverlap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.util.Models;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VoidWriterTest {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final String VOID = OverlapDetector.DEFAULT_VOID_BASE;
    private static final IRI VOID_DATASET = VF.createIRI(VoidWriter.VOID_NS, "Dataset");
    private static final IRI VOID_LINKSET = VF.createIRI(VoidWriter.VOID_NS, "Linkset");
    private static final IRI SUBSET = VF.createIRI(VoidWriter.VOID_NS, "subset");
    private static final IRI ENTITIES = VF.createIRI(VoidWriter.VOID_NS, "entities");
    private static final IRI URI_SPACE = VF.createIRI(VoidWriter.VOID_NS, "uriSpace");
    private static final IRI SUBJECTS_TARGET = VF.createIRI(VoidWriter.VOID_NS, "subjectsTarget");
    private static final IRI OBJECTS_TARGET = VF.createIRI(VoidWriter.VOID_NS, "objectsTarget");
    private static final IRI TRIPLES = VF.createIRI(VoidWriter.VOID_NS, "triples");

    @TempDir
    Path dir;

    private static Path dataset(Path root, String name, String... iris) throws IOException {
        Path irisFile = root.resolve(name).resolve("iris").resolve("iris.parquet");
        Files.createDirectories(irisFile.getParent());
        List<String> sorted = new ArrayList<>(List.of(iris));
        sorted.sort(null);
        try (IriParquetWriter writer = new IriParquetWriter(irisFile)) {
            for (String iri : sorted) {
                writer.write(IriParts.parse(iri));
            }
            writer.endRowGroup();
        }
        return root.resolve(name);
    }

    private static Model read(Path file) throws IOException {
        try (var reader = Files.newBufferedReader(file)) {
            return Rio.parse(reader, "", RDFFormat.TURTLE);
        }
    }

    private static long longValue(Model m, Resource subject, IRI predicate) {
        Literal literal = Models.objectLiteral(m.filter(subject, predicate, null)).orElseThrow();
        return literal.longValue();
    }

    private static IRI iri(String s) {
        return VF.createIRI(s);
    }

    @Test
    void describesDatasetsSubsetsAndLinksetInVoid() throws IOException {
        Path root = dir.resolve("data");
        Path a = dataset(root, "a", "http://ex/a/1", "http://ex/a/2", "http://ex/b/1");
        Path b = dataset(root, "b", "http://ex/a/2", "http://ex/a/3", "http://ex/c/1");

        new OverlapDetector().detect(List.of(a, b), dir.resolve("out"));
        Model m = read(dir.resolve("out").resolve("void.ttl"));

        IRI dsA = iri(VOID + "a");
        IRI dsB = iri(VOID + "b");
        assertTrue(m.contains(dsA, RDF.TYPE, VOID_DATASET));
        assertEquals(3, longValue(m, dsA, ENTITIES));
        assertEquals(3, longValue(m, dsB, ENTITIES));

        // One shared range, "http://ex/a/", with two IRIs in each dataset.
        IRI subsetA = iri(VOID + "a/range/0");
        assertTrue(m.contains(dsA, SUBSET, subsetA));
        assertTrue(m.contains(subsetA, URI_SPACE, VF.createLiteral("http://ex/a/")));
        assertEquals(2, longValue(m, subsetA, ENTITIES));

        IRI linkset = iri(VOID + "linkset/a__b");
        assertTrue(m.contains(linkset, RDF.TYPE, VOID_LINKSET));
        assertTrue(m.contains(linkset, SUBJECTS_TARGET, dsA));
        assertTrue(m.contains(linkset, OBJECTS_TARGET, dsB));
        assertEquals(1, longValue(m, linkset, TRIPLES));

        IRI rangeLinkset = iri(VOID + "linkset/a__b/range/0");
        assertTrue(m.contains(linkset, SUBSET, rangeLinkset));
        assertTrue(m.contains(rangeLinkset, SUBJECTS_TARGET, subsetA));
        assertTrue(m.contains(rangeLinkset, OBJECTS_TARGET, iri(VOID + "b/range/0")));
        assertEquals(1, longValue(m, rangeLinkset, TRIPLES));
    }

    @Test
    void datasetWithNoSharedRangeHasNoSubsets() throws IOException {
        Path root = dir.resolve("data");
        Path a = dataset(root, "a", "http://ex/a/1");
        Path b = dataset(root, "b", "http://other/x/1");

        new OverlapDetector().detect(List.of(a, b), dir.resolve("out"));
        Model m = read(dir.resolve("out").resolve("void.ttl"));

        assertTrue(m.contains(iri(VOID + "a"), RDF.TYPE, VOID_DATASET));
        assertFalse(m.contains(iri(VOID + "a"), SUBSET, null));
        assertEquals(0, longValue(m, iri(VOID + "linkset/a__b"), TRIPLES));
    }

    @Test
    void customBaseIsUsedForEveryIri() throws IOException {
        Path root = dir.resolve("data");
        Path a = dataset(root, "a", "http://ex/a/1");
        Path b = dataset(root, "b", "http://ex/a/1");

        new OverlapDetector().detect(List.of(a, b), dir.resolve("out"), "https://example.org/void/");
        Model m = read(dir.resolve("out").resolve("void.ttl"));

        assertTrue(m.contains(iri("https://example.org/void/a"), RDF.TYPE, VOID_DATASET));
        assertTrue(m.contains(iri("https://example.org/void/linkset/a__b"), RDF.TYPE, VOID_LINKSET));
    }

    @Test
    void datasetNamesThatNeedEscapingAreRejected() throws IOException {
        Path root = dir.resolve("data");
        Path a = dataset(root, "bad name", "http://ex/1");
        Path b = dataset(root, "b", "http://ex/1");

        assertThrows(IllegalArgumentException.class,
                () -> new OverlapDetector().detect(List.of(a, b), dir.resolve("out")));
    }
}

package swiss.sib.rdfoverlap;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;

/**
 * Writes the overlap results as a VoID description (https://www.w3.org/TR/void/), in Turtle.
 *
 * <p>Each dataset is a {@code void:Dataset}. Its {@code void:entities} is the number of distinct IRIs in it. Each
 * range the dataset shares with another dataset is a {@code void:subset} with a {@code void:uriSpace} equal to the
 * range key. That key is a string prefix of every IRI in the range, so the URI space is accurate.
 *
 * <p>Each pair is a {@code void:Linkset} from the first dataset to the second, with {@code void:subjectsTarget} and
 * {@code void:objectsTarget}. Its {@code void:triples} is the number of IRIs the two datasets share. A link here
 * means the same IRI occurs in both datasets. It does not mean a triple in either dataset, and the linkset has no
 * {@code void:linkPredicate}, because none is asserted. Each shared range is a sub-linkset with its own count.
 *
 * <p>Dataset and linkset IRIs are {@code <base><name>} and {@code <base>linkset/<first>__<second>}, and the subset
 * IRIs append {@code /range/<n>}.
 */
final class VoidWriter {

    static final String VOID_NS = "http://rdfs.org/ns/void#";

    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final IRI DATASET = VF.createIRI(VOID_NS, "Dataset");
    private static final IRI LINKSET = VF.createIRI(VOID_NS, "Linkset");
    private static final IRI SUBSET = VF.createIRI(VOID_NS, "subset");
    private static final IRI ENTITIES = VF.createIRI(VOID_NS, "entities");
    private static final IRI URI_SPACE = VF.createIRI(VOID_NS, "uriSpace");
    private static final IRI SUBJECTS_TARGET = VF.createIRI(VOID_NS, "subjectsTarget");
    private static final IRI OBJECTS_TARGET = VF.createIRI(VOID_NS, "objectsTarget");
    private static final IRI TRIPLES = VF.createIRI(VOID_NS, "triples");

    private VoidWriter() {
    }

    /**
     * Writes the VoID description of the datasets and the pairwise overlaps.
     *
     * @param file   the Turtle file to write
     * @param base   base IRI for dataset and linkset IRIs
     * @param names  dataset names, in the order of {@code totals}
     * @param totals distinct IRIs in each dataset
     * @param pairs  the results for every pair of datasets
     * @throws IOException if the file cannot be written
     */
    static void write(Path file, String base, List<String> names, List<Long> totals,
                      List<OverlapDetector.PairResult> pairs) throws IOException {
        Model model = new LinkedHashModel();
        model.setNamespace("void", VOID_NS);
        model.setNamespace("rdf", RDF.NAMESPACE);

        List<IRI> datasets = new ArrayList<>();
        for (String name : names) {
            datasets.add(VF.createIRI(base + name));
        }

        // Per dataset: range key -> IRIs of that dataset in the range, for the ranges it shares with another.
        List<TreeMap<RangeKey.Range, Long>> counts = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            counts.add(new TreeMap<>());
        }
        for (OverlapDetector.PairResult p : pairs) {
            int a = names.indexOf(p.summary().first());
            int b = names.indexOf(p.summary().second());
            for (OverlapDetector.SharedRange r : p.ranges()) {
                counts.get(a).put(r.range(), r.countFirst());
                counts.get(b).put(r.range(), r.countSecond());
            }
        }

        // Subset IRIs, numbered in range-key order within each dataset.
        List<Map<RangeKey.Range, IRI>> subsets = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            Map<RangeKey.Range, IRI> byKey = new TreeMap<>();
            int n = 0;
            for (RangeKey.Range key : counts.get(i).keySet()) {
                byKey.put(key, VF.createIRI(datasets.get(i) + "/range/" + n++));
            }
            subsets.add(byKey);
        }

        for (int i = 0; i < names.size(); i++) {
            IRI dataset = datasets.get(i);
            model.add(dataset, RDF.TYPE, DATASET);
            model.add(dataset, ENTITIES, VF.createLiteral(totals.get(i)));
            for (Map.Entry<RangeKey.Range, Long> range : counts.get(i).entrySet()) {
                IRI subset = subsets.get(i).get(range.getKey());
                model.add(dataset, SUBSET, subset);
                model.add(subset, RDF.TYPE, DATASET);
                model.add(subset, URI_SPACE, VF.createLiteral(range.getKey().toString()));
                model.add(subset, ENTITIES, VF.createLiteral(range.getValue()));
            }
        }

        for (OverlapDetector.PairResult p : pairs) {
            int a = names.indexOf(p.summary().first());
            int b = names.indexOf(p.summary().second());
            IRI linkset = VF.createIRI(base + "linkset/" + p.summary().first() + "__" + p.summary().second());
            model.add(linkset, RDF.TYPE, LINKSET);
            model.add(linkset, SUBJECTS_TARGET, datasets.get(a));
            model.add(linkset, OBJECTS_TARGET, datasets.get(b));
            model.add(linkset, TRIPLES, VF.createLiteral(p.summary().sharedIris()));
            int n = 0;
            for (OverlapDetector.SharedRange r : p.ranges()) {
                IRI sub = VF.createIRI(linkset + "/range/" + n++);
                model.add(linkset, SUBSET, sub);
                model.add(sub, RDF.TYPE, LINKSET);
                model.add(sub, SUBJECTS_TARGET, subsets.get(a).get(r.range()));
                model.add(sub, OBJECTS_TARGET, subsets.get(b).get(r.range()));
                model.add(sub, TRIPLES, VF.createLiteral(r.sharedIris()));
            }
        }

        try (Writer w = Files.newBufferedWriter(file)) {
            Rio.write(model, w, RDFFormat.TURTLE);
        }
    }
}

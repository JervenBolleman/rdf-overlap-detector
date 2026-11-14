package swiss.sib.rdfoverlap;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The resumable state of an extraction, kept in {@code <outDir>/state/manifest.txt}.
 *
 * <p>The manifest lists each input file that has been read completely, with its identity key, its counts, and the
 * file holding its predicates. It also lists the files of the shared merge tree, by level. Every file the manifest lists
 * exists on disk, and every file it does not list is either an orphan or a file of an unfinished input, so a resume
 * may delete it. The manifest is replaced atomically, so a crash leaves either the old state or the new one.
 *
 * <p>The format is one record per line, with tab-separated fields. Keys are URL-encoded, so they hold no tabs or
 * line breaks.
 */
final class ManifestStore {

    /** An input file that has been read completely. */
    record CompletedFile(String key, long statements, long rejectedIris, long batches, String predicatesFile) {
    }

    /**
     * The state.
     *
     * @param completed    the files read completely
     * @param sharedLevels the names of the shared tree's files, by level, relative to the runs directory
     */
    record Manifest(List<CompletedFile> completed, List<List<String>> sharedLevels) {

        static Manifest empty() {
            return new Manifest(List.of(), List.of());
        }
    }

    private final Path directory;

    ManifestStore(Path directory) {
        this.directory = directory;
    }

    private Path file() {
        return directory.resolve("manifest.txt");
    }

    /** The saved state, or an empty state when there is none. */
    Manifest load() throws IOException {
        if (!Files.exists(file())) {
            return Manifest.empty();
        }
        List<CompletedFile> completed = new ArrayList<>();
        List<List<String>> levels = new ArrayList<>();
        for (String line : Files.readAllLines(file(), StandardCharsets.UTF_8)) {
            String[] fields = line.split("\t", -1);
            switch (fields[0]) {
                case "C" -> completed.add(new CompletedFile(
                        URLDecoder.decode(fields[1], StandardCharsets.UTF_8),
                        Long.parseLong(fields[2]), Long.parseLong(fields[3]), Long.parseLong(fields[4]), fields[5]));
                case "L" -> {
                    int level = Integer.parseInt(fields[1]);
                    while (levels.size() <= level) {
                        levels.add(new ArrayList<>());
                    }
                    levels.get(level).add(fields[2]);
                }
                default -> throw new IOException("damaged state file " + file() + ": " + line);
            }
        }
        return new Manifest(completed, levels);
    }

    /** Replaces the saved state. The new state is written beside the old one, then moved over it. */
    void save(Manifest manifest) throws IOException {
        StringBuilder text = new StringBuilder();
        for (CompletedFile c : manifest.completed()) {
            text.append("C\t").append(URLEncoder.encode(c.key(), StandardCharsets.UTF_8)).append('\t')
                    .append(c.statements()).append('\t').append(c.rejectedIris()).append('\t')
                    .append(c.batches()).append('\t').append(c.predicatesFile()).append('\n');
        }
        for (int level = 0; level < manifest.sharedLevels().size(); level++) {
            for (String name : manifest.sharedLevels().get(level)) {
                text.append("L\t").append(level).append('\t').append(name).append('\n');
            }
        }
        Files.createDirectories(directory);
        Path temporary = directory.resolve("manifest.txt.tmp");
        Files.writeString(temporary, text, StandardCharsets.UTF_8);
        Files.move(temporary, file(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Removes the saved state, once the extraction has finished. */
    void delete() throws IOException {
        Files.deleteIfExists(file());
    }
}

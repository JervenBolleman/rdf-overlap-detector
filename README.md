# rdf-overlap-detector

Finds the IRI ranges that RDF datasets have in common. For each dataset it extracts every IRI in subject and object
position, sorts and deduplicates them, and then compares every pair of datasets by IRI range. The overlap is
written as parquet files and as a [VoID](https://www.w3.org/TR/void/) description.

## Requirements

- JDK 25, to build and run.
- Maven 3.9 or newer, to build.

## Build

```sh
mvn package
```

This writes one self-contained jar, `target/rdf-overlap-detector-0.1.0-SNAPSHOT.jar`, with all runtime dependencies
included. The shade plugin merges the `META-INF/services` files, so RDF4j finds all its parsers and writers. It
drops dependency signatures and per-jar `module-info` descriptors, which would be invalid once merged.

Run the tests with `mvn test`.

## Native image (experimental)

A GraalVM native executable starts without a JVM. It is an opt-in Maven profile, so `mvn package` is unchanged.

```sh
export GRAALVM_HOME=/path/to/graalvm-25
$GRAALVM_HOME/bin/gu install native-image   # only if native-image is not already installed
mvn -Pnative package
target/rdf-overlap-detector  help
```

Requirements:

- GraalVM for JDK 25, with `native-image`. The native build is run and tested on GraalVM 25.
- A C toolchain such as `gcc`.
- A writable `/tmp`. The `native-image` launcher writes its argument files there and ignores `TMPDIR`, `TMP` and
  `TEMP`.
- Network access during the build. The profile fetches GraalVM reachability metadata for libraries that need it.

Status: the native build works on GraalVM 25 when run locally. The `release` workflow's native jobs have not run yet,
because the repository is not on GitHub. Any `.zst` input that fails in a native binary is worth reporting with the
build log and the stack trace.

## Continuous integration and releases

Two GitHub Actions workflows live in `.github/workflows/`:

- **`test`** runs `mvn verify` on Ubuntu for every pull request, on each push to the pull request.
- **`release`** is started by hand from the Actions tab, with a version such as `1.0.0`. It builds and tests the
  uber jar, and builds native binaries for Linux (x64), macOS (arm64 and x64) and Windows (x64), with a smoke test on each.
  It then creates the GitHub release `v<version>` with all of them attached. The version is set only in the
  runner's copy of the pom, so the committed pom keeps `0.1.0-SNAPSHOT`.

The workflows have not run yet, because the repository is not on GitHub.

## Commands

```sh
java -jar target/rdf-overlap-detector-0.1.0-SNAPSHOT.jar <command> [arguments]
```

| Command | Arguments | What it does |
| --- | --- | --- |
| `extract` | `<input> <datasetDir>` | Reads one RDF file and writes its IRIs as sorted run files under `<datasetDir>/runs/`, and its predicates under `<datasetDir>/predicates/`. |
| `sort` | `<datasetDir>` | Merges the runs into `<datasetDir>/iris/iris.parquet`, sorted with no duplicates, then deletes `runs/`. |
| `overlap` | `[--void-base=IRI] <outDir> <datasetDir> <datasetDir> [...]` | Compares every pair of sorted datasets and writes the results to `<outDir>`. |
| `help` | | Prints the usage. |

The name of a dataset is the name of its directory. Use names made only of letters, digits, `.`, `_` and `-`.

Exit codes: `0` on success, `1` for a usage error, `3` when a step fails (for example, a parse error or a missing
`runs/` directory).

### A complete example

```sh
JAR=target/rdf-overlap-detector-0.1.0-SNAPSHOT.jar

java -jar $JAR extract rhea.rdf.gz work/rhea
java -jar $JAR sort work/rhea

java -jar $JAR extract BSBM.ttl.xz work/BSBM
java -jar $JAR sort work/BSBM

java -jar $JAR overlap --void-base=https://example.org/void/ results work/rhea work/BSBM
```

`overlap` prints one line per pair:

```
rhea BSBM ranges=10/92 sharedRanges=0 sharedIris=0
void description: results/void.ttl
```

Extract and sort each dataset once. After that you can compare any set of datasets without redoing them.

## Input formats

- **RDF syntaxes:** N-Triples, Turtle, RDF/XML and JSON-LD. The format is taken from the file name, and the
  extension can be followed by a compression suffix.
- **Compression:** `.gz`, `.xz` or `.zst` on top of any of the syntaxes above, for example `rhea.rdf.gz`.
  Uncompressed files also work.
- **Base IRI:** relative references are resolved against the file's own URI, so references such as
  `rdf:about=""` work.
- **Triples without IRIs:** blank nodes and literals are skipped. Named graph names are not collected.
  Predicates are kept in a separate file, not in the IRI set.

Malformed IRIs are counted as `rejectedIris` in the extract output and skipped. RDF4j rejects most malformed IRIs
at parse time, so the count is usually zero.

## Output

```
<datasetDir>/
  runs/                      temporary, removed by sort
  predicates/predicates.parquet
  iris/iris.parquet          sorted, distinct IRIs with their components

<outDir>/
  A__B.parquet               one row per shared range for each pair of datasets
  summary.csv                one line per pair
  void.ttl                   VoID description of the datasets and their overlaps
```

Each IRI row stores the full IRI and its RFC 3986 components: scheme, userinfo, host, port, path, query and fragment.
Components keep their exact text, with no percent-decoding, so equal IRIs always have equal rows.

A pair file has one row per shared range, with the columns `range`, `count_first`, `count_second` and `shared_iris`.

## Ranges

A range groups IRIs by their prefix. It is the scheme and authority, plus the first two path segments:

| IRI | Range |
| --- | --- |
| `http://h/a/b/c` | `http://h/a/b/` |
| `http://h/a/1` | `http://h/a/` |
| `http://h/x` | `http://h/` |
| `urn:isbn:1` | `urn:` |

The leaf segment (the last one) never counts towards the range. An IRI with no authority, such as `urn:`, is grouped
by its scheme alone. The depth is fixed at 2 in the command-line tool. The depth is a parameter of the library
class `OverlapDetector`.

## VoID

`overlap` writes `void.ttl` in Turtle.

- Each dataset is a `void:Dataset`. `void:entities` is the number of distinct IRIs in it.
- Each range that a dataset shares with another is a `void:subset`, with `void:uriSpace` set to the range key.
- Each pair is a `void:Linkset` from the first dataset to the second. `void:triples` is the number of IRIs the two
  share, and each shared range is a sub-linkset with its own count.

A link means that the same IRI occurs in both datasets. It is not a triple asserted in either dataset, so the
linksets have no `void:linkPredicate`.

Dataset and linkset IRIs start with the base given by `--void-base=`, which defaults to `urn:rdf-overlap:`. Pass a
namespace you control to get resolvable IRIs.

## Running on large files

- **Memory:** the extract and sort steps stream the data. The overlap step keeps one entry per range per dataset in
  memory. Use `-Xmx` to set the heap size, for example `java -Xmx4g -jar ...`.
- **Temporary directory:** the zstd and snappy libraries unpack native code into `java.io.tmpdir`. Hardwood writes
  zstd by default. If that directory is not writable, extraction fails with
  `Cannot unpack libzstd-jni`. Point it somewhere writable with `-Djava.io.tmpdir=/some/dir`.
- **XML limits:** the JDK's default cap on a single XML text node is 100,000 characters. The extractor raises
  `jdk.xml.maxGeneralEntitySizeLimit`, `jdk.xml.totalEntitySizeLimit` and `jdk.xml.entityReplacementLimit` to
  10,000,000, unless you have set them yourself.
- **Decompression limits:** RDF4j limits nested compression, 4 GiB of expanded bytes and a 200:1 expansion ratio.
  A single compression layer is not limited.

## Limitations

- Only the default range depth (2) and batch size (10,000) are available from the command line. Fan-in for merging
  is 64.
- Predicates are collected in memory. A dataset with very many distinct predicates would need more memory.
- The overlap results depend on the depth setting. Changing the depth means running `overlap` again, not `sort`.
- Results are tested on datasets up to about two million statements. Behaviour on much larger inputs has not been
  measured. See `PLAN.md` for the design and the measurements.

## Layout of the source

- `IriParts`, `RangeKey`: the RFC 3986 split and the range rule.
- `IriExtractor`: parses RDF and writes run files. `IriMerger`: sorts runs into one file per dataset.
- `OverlapDetector`: compares datasets. `VoidWriter`: writes the VoID description.
- `Main`: the command-line interface.
- `PLAN.md` has the design notes and the results of each phase.

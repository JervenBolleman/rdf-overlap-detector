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

## Native image

A GraalVM native executable starts without a JVM. It is an opt-in Maven profile, so `mvn package` is unchanged.

```sh
export GRAALVM_HOME=/path/to/graalvm-25
$GRAALVM_HOME/bin/gu install native-image   # only if native-image is not already installed
mvn -Pnative package
target/rdf-overlap-detector help
```

Requirements:

- GraalVM 25, with `native-image`.
- A C toolchain such as `gcc`.
- Network access during the build. The profile fetches GraalVM reachability metadata for libraries that need it.

zstd-jni's native library is copied into the image for each platform, and `NativeCodecs` loads it at run time. The
loader extracts it to the temp directory, or to `~/.rdf-overlap-detector/native` if the temp directory is noexec, and
then loads it. Gzip, xz and zstd input are decompressed by the libraries directly, not by reflection, so a native image
includes them.

Status: built and run on Linux x86_64 with GraalVM 25. The sample directory extracts, including the zstd, xz and gzip
samples, and `overlap` works on the output. The release workflow's macOS and Windows builds have not been
run yet, and neither has the noexec fallback.

## Continuous integration and releases

Two GitHub Actions workflows live in `.github/workflows/`:

- **`test`** runs `mvn verify` on Ubuntu for every pull request, on each push to the pull request.
- **`native-check`** builds the native binary on arm64 Linux, and runs the release smoke test on it, for every pull
  request and every push to `main`.
- **`release`** is started by hand from the Actions tab, with a version such as `1.0.0`. It builds and tests the
  uber jar, and builds native binaries for Linux (x64), macOS (arm64 and x64) and Windows (x64), with a smoke test on each.
  The version must be a semantic version (`MAJOR.MINOR.PATCH`, optionally with a pre-release such as `-rc.1`), must
  not be older than the pom's version, and must not already be tagged. The release then:
  - commits the release version to the pom, updates the jar names in this README to the release, and tags that
    commit `v<version>`;
  - commits the next patch snapshot to the pom on `main`, so `1.2.3` leaves `main` at `1.2.4-SNAPSHOT`, and names
    that snapshot in the README's jar names;
  - pushes the commits and the tag in one atomic push, so a `main` that has moved on fails the release cleanly;
  - creates the GitHub release `v<version>` with all the artifacts attached.

  The push to `main` needs the `github-actions` bot to be allowed to push there. If branch protection forbids it,
  the release fails before anything is published.

The workflows have not run yet, because the repository is not on GitHub.

## Commands

```sh
java -jar target/rdf-overlap-detector-0.1.0-SNAPSHOT.jar <command> [arguments]
```

| Command | Arguments | What it does |
| --- | --- | --- |
| `extract` | `[--resume] <input> <datasetDir>` | Reads RDF from a file, a directory, an http(s) or ssh URL, or a Metalink file, and writes one sorted, deduplicated IRI file to `<datasetDir>/iris/iris.parquet`. Runs are merged in the background while extraction continues. |
| `overlap` | `[--void-base=IRI] <outDir> <datasetDir> <datasetDir> [...]` | Compares every pair of sorted datasets and writes the results to `<outDir>`. |
| `help` | | Prints the usage. |

The name of a dataset is the name of its directory. Use names made only of letters, digits, `.`, `_` and `-`.

`--resume` continues an extraction that did not finish. The extraction records each file once it has been read
completely, in `<datasetDir>/state/`, together with the merged runs. A resumed run skips those files and reads the rest.
A file is identified by its path, size and modification time, or by its URL for a remote file, so a changed local file
is read again. A remote file cannot be checked for change, so it is trusted if its URL is the same. The state is removed
when the extraction finishes. A run that fails keeps its state, so it can be resumed. Only the output is absent until
the extraction finishes, so the partial dataset is never read as complete.

Progress is logged to stderr, with a timestamp, the level and the thread: `started` and `done` for each file, with the
time it took and how many files have started and finished out of the total, `skipped` for a file that is not RDF, and `restored` for a file taken from the saved state. A failure is
logged as an error with its stack trace. The log format is set in `simplelogger.properties`.

Exit codes: `0` on success, `1` for a usage error, `3` when a step fails (for example, a parse error on a single file
or a failed merge).

When the input is a directory, every regular file below it is tried, in sorted order. A file that does not parse as
RDF is skipped. Its name and the reason are printed to stderr as `skipped (not RDF): ...`, and extraction carries on.

A Metalink file (`.meta4` for Metalink 4, `.metalink` for Metalink 3) lists the files to read, each with its URLs and
checksums. It can be a local file or an http(s) URL. Relative URLs resolve against the Metalink file's own location.
Each file tries its URLs in priority order, so a mirror is used when the first one fails. The MD5, SHA-1, SHA-256 and
SHA-512 checksums are verified while the file streams through, and a file that does not match is skipped with the
reason `checksum mismatch`. A file that cannot be downloaded is skipped with its HTTP status.
Files under the output directory are left out, so the dataset directory can sit inside the input directory. Every
extraction replaces the output of the previous one.

### A complete example

```sh
JAR=target/rdf-overlap-detector-0.1.0-SNAPSHOT.jar

java -jar $JAR extract rhea.rdf.gz work/rhea

java -jar $JAR extract BSBM.ttl.xz work/BSBM

java -jar $JAR overlap --void-base=https://example.org/void/ results work/rhea work/BSBM
```

`overlap` prints one line per pair:

```
rhea BSBM ranges=10/92 sharedRanges=0 sharedIris=0
void description: results/void.ttl
```

Extract each dataset once. After that you can compare any set of datasets without redoing it.

## Input formats

- **RDF syntaxes:** N-Triples, Turtle, RDF/XML and JSON-LD. The format is taken from the file name, and the
  extension can be followed by a compression suffix.
- **Compression:** `.gz`, `.xz` or `.zst` on top of any of the syntaxes above, for example `rhea.rdf.gz`.
  Uncompressed files also work.
- **SSH:** `ssh://[user@]host[:port]/absolute/path` streams a remote file. The system `ssh` client runs `cat` on the
  host, so your `~/.ssh/config`, keys, agent, `known_hosts` and `ProxyJump` apply. Passwords are never asked for, so the
  host key and key-based login must already be set up. Only absolute paths are supported. A directory is listed with `find`,
  so it reads every file below it, as a local directory does. The remote host needs a POSIX shell, `cat` and `find`. A Metalink file can be read over ssh, and its entries can be ssh URLs.
- **Remote files:** an http(s) URL is streamed, not saved to disk first. The compression and the format come from
  the last segment of the URL, so `https://example.org/dump.nt.gz` works.
- **Base IRI:** relative references are resolved against the URI the file was read from, so references such as
  `rdf:about=""` work.
- **Triples without IRIs:** blank nodes and literals are skipped. Named graph names are not collected.
  Predicates are kept in a separate file, not in the IRI set.

A directory can mix RDF with other files, which are skipped as described above. A file that is RDF but broken is
skipped too, so check the `skipped` lines in the output if a file you expected is missing.

Malformed IRIs are counted as `rejectedIris` in the extract output and skipped. RDF4j rejects most malformed IRIs
at parse time, so the count is usually zero.

## Output

```
<datasetDir>/
  runs/                      temporary, merged into iris/ and removed
  predicates/predicates.parquet
  iris/iris.parquet          sorted, distinct IRIs with their components

<outDir>/
  A__B.parquet               one row per shared range for each pair of datasets
  summary.csv                one line per pair
  void.ttl                   VoID description of the datasets and their overlaps
```

Each IRI row stores its RFC 3986 components: scheme, userinfo, host, port, path, query and fragment. The IRI text is
rebuilt from them. An empty port is the default for the scheme, so it is not kept.
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

- **Memory and open files:** extraction streams the data. Runs are merged in a tree: exactly 16 files of one level are merged into one file of the next level. Merges run on background threads: one
  fewer than the CPU cores, or one on a single core. The extraction never has more than 768 files open. Each merge
  opens 17 files (16 runs and its output), so at most 44 merges run at once, whatever the core count. The overlap step keeps one entry per range per dataset in
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
- The overlap results depend on the depth setting. Changing the depth means running `overlap` again, not `extract`.
- Results are tested on datasets up to about two million statements. Behaviour on much larger inputs has not been
  measured. See `PLAN.md` for the design and the measurements.

## Layout of the source

- `IriParts`, `RangeKey`: the RFC 3986 split and the range rule.
- `IriExtractor`: parses RDF and writes run files. `IriMerger`: sorts runs into one file per dataset.
- `OverlapDetector`: compares datasets. `VoidWriter`: writes the VoID description.
- `Main`: the command-line interface.
- `PLAN.md` has the design notes and the results of each phase.

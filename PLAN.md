# PLAN: RDF dataset IRI overlap detector

Source: `TODO.md` in this directory. This plan follows its steps and adds the overlap detection step, which the TODO does not yet describe.

## Decisions

Answers from the planning questions:

| Topic | Decision |
| --- | --- |
| Scope | Extraction, sorting, and overlap detection. |
| Data size | Larger than memory. The sort uses an external merge, so memory stays bounded. |
| Compression | RDF4j 6.2.0-M2 release, which reads gzip, xz and zstd (phase 0 confirmed). No SNAPSHOT needed. |
| IRI parts | RFC 3986 components only: scheme, userinfo, host, port, path, query, fragment. |

Choices made here, to review:

- **Overlap definition.** The TODO asks for "IRI ranges" that appear in two datasets, but does not define the range. This plan defines a range as a run of sorted IRIs that share a prefix, where the prefix is `scheme://host/` plus a configurable number of leading path segments (default 2). Two datasets overlap on a range when both contain at least one IRI under the same prefix. Phase 6 also reports the exact shared IRI count per range.
- **Storage layout.** One directory per dataset, with `iris/` (sorted, deduplicated IRIs), `predicates/` (deduplicated predicate IRIs), and `runs/` (temporary sorted batches, deleted after the merge).
- **Project.** Maven, Java 25, a single module at first. Split into modules only if the CLI and library need separate release cycles.
- **Versions.** Hardwood `1.1.0.Beta2` as the TODO says (`1.1.0.Beta3` also exists, so check the changelog before upgrading). RDF4j `6.2.0-M2`, the latest milestone, which satisfies "RDF4j 6+".

## Phase 0: Spikes (done)

Spike code lives outside the repo, in the session scratchpad (`spike-hardwood/`, `spike-rdf4j/`). Nothing from the spikes is committed.

### Spike 1: Hardwood 1.1.0.Beta2 writer

Result: **passes**.

- The writer is public in `dev.hardwood.writer` (`ParquetFileWriter`, `RowWriter`, `WriterConfig`, `FileSchema.builder`). No separate writer library is needed.
- `ParquetFileWriter` and `RowWriter` are annotated `@Experimental`. Pin the exact version and wrap the writer behind our own small interface, so an API change touches one class.
- Round trip of 25,000 IRI rows with the planned columns (`iri`, scheme, nullable userinfo, host, nullable port, path, nullable query and fragment): every value read back matched, and nulls were correct.
- Row groups: `endRowGroup()` every 10,000 rows produced exactly 10,000, 10,000 and 5,000 rows, confirmed in the footer. This matches the batch plan in phase 4.
- Codecs that worked: UNCOMPRESSED, SNAPPY, ZSTD, GZIP. The file was about 59 KB with ZSTD versus 1.37 MB uncompressed for this sample.
- LZ4_RAW was not tested because `lz4-java` was not on the classpath. Add it only if LZ4 is wanted.
- Sandbox note: the zstd and snappy libraries extract native code into `java.io.tmpdir`. In this sandbox `/tmp` is read-only, so run with `-Djava.io.tmpdir=$TMPDIR`. This is an environment issue, not a Hardwood issue.

### Spike 2: RDF4j compression

Result: **no SNAPSHOT needed.** Use the release `6.2.0-M2`.

- RDF4j 6.2.0-M2 contains `org.eclipse.rdf4j.rio.helpers.RioCompression` and `RDFInputDispatcher`. They are in the release jar, so the SNAPSHOT is not required. This changes the decision from the planning round. The release is reproducible, and the snapshot is not.
- Tested with 200,000 N-Triples, using both `RioCompression.decompressIfDetected` and `RDFInputDispatcher.dispatch`. Both gave 200,000 statements for plain, `.gz`, `.xz` and `.zst`:
  - plain: 18.1 MB
  - gzip: 1.2 MB
  - xz: 211 KB
  - zstd: 402 KB
- The xz path uses Apache Commons Compress and `org.tukaani:xz` 1.12. The zstd path uses `zstd-jni`. The RDF4j pom pins commons-compress 1.28.0, xz 1.12 and zstd-jni 1.5.7-25. Those are the versions the spike used, so the fallback in the old plan is not needed.
- **Decompression limits matter.** `RDFInputDispatcher` enforces four limits by default:
  - 4 GiB aggregate expanded bytes (`org.eclipse.rdf4j.rio.loader.max_expanded_bytes`)
  - 200:1 expansion ratio, after a 1 MiB grace (`max_expansion_ratio`, `expansion_ratio_grace_bytes`)
  - nesting depth 8
  - 50,000 archive entries
- Measured behaviour: a single compression layer (`data.nt.xz`) was not limited even with the ratio cap set to 5 or the byte cap set to 1 MB. A two-layer file (gzip inside xz) was rejected once the limits were lowered. So the limits apply to nested layers only.
- **Consequence for phase 4:** a normal single-layer dump is not capped by these defaults. If a dump is wrapped in another archive, raise the 4 GiB and 200:1 limits. The limits are set on the `ParserConfig`, not through system properties. Set them explicitly in the extractor so behaviour does not depend on defaults.
- Use `RDFInputDispatcher` rather than `Rio.createParser` alone. It detects the format and the compression from the file name and content.

### Phase 0 exit

Both spikes ran. The plan's decisions change in two places:

1. Compression uses RDF4j 6.2.0-M2 directly. No SNAPSHOT, no separate Commons Compress wrapper.
2. Parquet writing uses Hardwood 1.1.0.Beta2 behind a thin wrapper, because the writer is experimental.

## Phase 1: Project skeleton (done)

Built as `pom.xml` with `src/main/java/org/example/rdfoverlap/Main.java`. The CLI dispatches `extract`, `sort` and `overlap`, and each reports "not implemented yet" with exit code 2. `SampleParsingTest` checks that 16 sample files (N-Triples, Turtle, RDF/XML and JSON-LD, each plain, gzip, xz and zstd) all parse to the same three statements through `RDFInputDispatcher`. `mvn test` passes with 21 tests. The group id is `swiss.sib`.

- `pom.xml`: Java 25 release, RDF4j BOM, Hardwood, Commons Compress (if the fallback is chosen), JUnit 5, and the Maven compiler and surefire plugins.
- Package `swiss.sib.rdfoverlap` (rename to the real group id before the first commit).
- A CLI entry point with subcommands `extract`, `sort`, and `overlap`, and a test resource directory with small RDF samples in each supported syntax (N-Triples, Turtle, RDF/XML, JSON-LD, each gzipped, xz-compressed, and zstd-compressed).

## Phase 2: IRI decomposition (done)

Implemented in `IriParts.java` (record) and tested in `IriPartsTest.java`. Decisions made while implementing:

- An IRI without a scheme is rejected with `IllegalArgumentException`. Relative references are not IRIs for this tool.
- A non-numeric port, or one that does not fit in an int32, is rejected. An empty port (`http://h:/`) is stored as null, the same as no port.
- An empty but present authority (`file:///x`) gives host `""`, not null. Absent components are null.
- The host keeps IPv6 brackets, so `[::1]` is stored with them.
- Nothing is percent-decoded or case-folded, including the scheme.
- Userinfo is split at the last `@`, and the port at the last `:` outside brackets.

41 tests pass across the suite.

- Parse each IRI with the RFC 3986 Appendix B regex, which gives the seven components. Use it instead of `java.net.URI`, which rejects some valid IRIs with non-ASCII characters.
- Store each component as it appears in the source, with no percent-decoding, so equal IRIs always produce equal rows.
- Blank nodes and literals are skipped. Only IRIs go into the parquet files.
- Tests: the RFC 3986 examples, IRIs with unicode, empty query or fragment (distinguish null from empty), and IPv6 hosts.

## Phase 3: Parquet schema (done)

Implemented as `IriSchema` (the schema and column index constants), `IriRecord` (IRI plus parts), `IriParquetWriter` (wraps Hardwood's experimental writer, with `endRowGroup()` per batch) and `IriParquetReader`. The predicate files use the same schema, so there is no separate predicate schema. Tests cover:

- A round trip of tricky IRIs. Null and empty query and fragment stay distinct, and so do absent and empty userinfo and host.
- Row-group boundaries at 10,000 rows per batch, giving 3 groups for 25,000 rows.
- The index constants matching the column order of the schema.

Verified with 44 passing tests.

IRI file (`iris/`), one row per IRI:

| Column | Type | Notes |
| --- | --- | --- |
| `iri` | string | Full IRI, the sort key. |
| `scheme` | string | Required. |
| `userinfo` | string, nullable | |
| `host` | string, nullable | |
| `port` | int32, nullable | |
| `path` | string | Empty string when absent. |
| `query` | string, nullable | Null when the `?` is absent. |
| `fragment` | string, nullable | Null when the `#` is absent. |

Predicate file (`predicates/`): one column `iri` plus the same components, so predicates can be matched the same way.

## Phase 4: Extraction (TODO steps 1 to 3) (done)

Implemented as `IriExtractor` and wired to `rdf-overlap-detector extract <input> <outDir>`. Output layout: `<outDir>/runs/run-NNNNNN.parquet` for the sorted batches, and `<outDir>/predicates/predicates.parquet` for the predicates. Decisions that differ from or add to the plan above:

- **Lazy flush.** A batch is written when a new distinct IRI arrives and the batch already holds `batchSize` IRIs. A batch flushed eagerly would re-add the object of the same triple, leaving a stray run of one IRI.
- **Predicates are held in memory and written once at the end**, not flushed on their own schedule. This is simpler, and distinct predicates are few. If a dataset has very many distinct predicates, this needs revisiting.
- **Sort order is `String.compareTo`.** The merge phase must use the same order.
- **Rejected IRIs are a backstop.** RDF4j rejects every malformed port tried (`:99999999999`, `:-1`, `:1x`), and it accepts an empty port, which `IriParts` also accepts. So `rejectedIris` should normally stay 0. Malformed IRIs are covered by `IriPartsTest`.
- **Decompression limits** are set explicitly on the parser config, to RDF4j's defaults (4 GiB expanded, 200:1 ratio). Phase 0 showed these only apply to nested compression.
- **Scope.** Subject and object IRIs are collected. Literals, blank nodes and named graph names are not. Datatype IRIs of literals are not collected.

Tests: all 16 samples give the same four IRIs and two predicates. Exactly 10,000 distinct IRIs give one run, and 10,001 give two. Each run is sorted and has one row group. Duplicates across batches are kept, and duplicates within a batch collapse. Blank nodes and literals are excluded. An empty port round-trips. 69 tests pass.

- Stream the RDF with RDF4j's `RDFParser` and a statement handler, so the whole file is never held in memory.
- Subject, predicate, and object IRIs go into the batch set. Predicates go into a separate set, which is deduplicated and flushed on its own schedule so it stays small.
- When the IRI set reaches 10,000 entries, sort it and write it as one row group to a run file in `runs/`. Clear the set.
- Pass the input through the compression decoder from phase 0 based on the file extension.
- Output: one sorted run file per batch, plus one predicate file per dataset.
- Tests: a small sample file yields the expected IRI count, duplicates across batches are kept at this stage, and batch boundaries are correct at exactly 10,000 and 10,001 IRIs.

## Phase 5: Sorting into one set per dataset (TODO step 4) (done)

Implemented as `IriMerger` and `RunCursor`, wired to `rdf-overlap-detector sort <datasetDir>`. Output: `<datasetDir>/iris/iris.parquet`. Decisions beyond the plan:

- **Fan-in 64 and 1,000,000 rows per output row group** by default. Both are constructor parameters.
- **Multi-pass merge.** While there are more runs than the fan-in, groups of at most fan-in runs are merged into `runs/pass-N/merged-*.parquet`. Each group's inputs are deleted once merged, so intermediate passes do not hold double the data.
- **Deduplication happens at every pass**, which is valid because the result is a set.
- **Verification before cleanup.** The output must be strictly increasing and its footer row count must match the rows written. Only then is `runs/` deleted.
- An empty dataset (no runs) gives an empty `iris.parquet`.

Tests: merge of overlapping runs with non-ASCII IRIs, a multi-pass merge (five runs, fan-in 2, two passes), output row-group boundaries (10 rows in groups of 3 gives 4), an empty dataset, cleanup after verification, and extract-then-sort on a compressed sample. 77 tests pass.

- K-way external merge over the sorted run files, using a priority queue on `iri`. Each run is read through its own Hardwood reader, one row group at a time, so memory is bounded by the number of runs times one batch.
- Remove duplicates during the merge, so the output `iris/` file contains each IRI once.
- Delete the `runs/` directory after the output file is verified.
- If the number of runs grows too large for one merge, do a multi-pass merge with a fan-in of 64. Keep the fan-in as a config value.
- Tests: duplicates across runs collapse, output stays sorted, and an input with more runs than the fan-in still merges correctly.

## Phase 6: Overlap detection (done, with the defaults)

Implemented as `RangeKey`, `OverlapDetector`, `OverlapParquetWriter` and `OverlapParquetReader`, wired to `rdf-overlap-detector overlap <outDir> <datasetDir>...`. Output: `<outDir>/A__B.parquet` for every pair, with one row per shared range (`range`, `count_first`, `count_second`, `shared_iris`), and `<outDir>/summary.csv` with one line per pair.

Defaults used: depth 2. A range is `scheme://authority/` plus the first two path segments. The key stops after the last directory slash, so the leaf segment is never in the key. An IRI with no authority gets its scheme as the key, for example `urn:`. The depth is a constructor parameter.

Decisions that depart from the plan:

- **Ranges are not contiguous in sorted order.** This was an error in my earlier reasoning. A key is a prefix of its IRI, but a shorter key can be a prefix of another range's key, so `http://h/a/b` (range `http://h/a/`) sits between `http://h/a/b-c/d` and `http://h/a/b/...`. So the counting does not merge-join on sorted prefixes. Each dataset's range counts are a map built in one pass over its sorted IRI file. The overlap is the intersection of the two maps. Shared IRIs come from a streaming merge of the two sorted IRI files.
- **Memory is one entry per range per dataset.** That is the expected bound, since there are far fewer ranges than IRIs. This is a limit. A dataset with very many distinct ranges would need the range maps spilled to disk.
- **Short IRIs get their own ranges.** At depth 2, `http://h/a/1` is in range `http://h/a/`, but `http://h/x` is in range `http://h/`. A dataset whose IRIs are all shallow gets coarse ranges, which is intended.

Tests: `RangeKey` examples at several depths, plus a check that the key is always a prefix of the IRI. Counts match a direct grouping on a set of nested ranges. Shared, one-sided and shared-range-without-shared-IRI cases. Every pair is summarised, a different depth changes the result, and duplicate or missing dataset names are rejected. The CLI runs end to end. 103 tests pass.

- Input: the sorted `iris/` files for two or more datasets.
- For each pair of datasets, do a merge-join on the sorted prefix. Each step produces a range key of `scheme://host/` plus the leading path segments (configurable, default 2), along with the count of IRIs from each dataset under that key.
- Output: one parquet file, `overlaps/<a>__<b>.parquet`, with the range key, the count in each dataset, and the number of exact IRIs shared. Also write a short summary CSV for quick inspection.
- Tests: two small datasets with a known overlap, a range present in only one dataset (no output row), and identical datasets.

## Phase 7: Validation and performance (done)

Run on five real datasets from local checkouts, using all three compressions. The harness lives in the session scratchpad, not the repo. Each dataset was extracted, sorted, and then checked by an independent validator. That validator reads the input with its own decompression (`GZIPInputStream`, `XZInputStream`, `ZstdInputStream`), parses with RDF4j's plain `RDFParser`, and builds the expected IRI and predicate sets in a hash set. It then compares them with the parquet output, checking that the output is sorted, has no duplicates, and that no runs remain.

| Dataset | Format | Compression | Statements | Distinct IRIs | Predicates | Extract | Sort | Peak RSS (extract / sort) | Result |
|---|---|---|---|---|---|---|---|---|---|
| BSBM | Turtle | xz | 1,000,312 | 148,549 | 40 | 9.7 s | 1.6 s | 319 / 385 MB | PASS |
| GO | RDF/XML | zstd | 392,668 | 82,484 | 10 | 2.8 s | 1.4 s | 268 / 299 MB | PASS |
| NextProt medium-expression | Turtle | none | 662,220 | 690,690 | 1 | 4.8 s | 3.4 s | 277 / 942 MB | PASS |
| test (N-Triples) | N-Triples | gzip | 1,946,413 | 449,850 | 270 | 6.8 s | 2.4 s | 286 / 515 MB | PASS |
| Rhea | RDF/XML | gzip | 1,472,914 | 235,418 | 67 | 6.2 s | 1.5 s | 310 / 361 MB | PASS |

The overlap across all five ran in 3.6 s. Rhea's 235,418 IRIs are all in the `test` dataset, across 10 shared ranges. The extract and sort ran with `-Xmx2g`. Peak RSS includes the JVM heap, so it overstates what is needed.

**Scale caveat.** The largest input is 1.9 million statements, about 16 MB compressed. This does not test the "larger than memory" requirement. Throughput was 150k to 300k statements per second. The sort's peak RSS was highest on medium-expression (942 MB), where 70 runs needed one intermediate merge pass before the final merge. The output row-group buffer (1,000,000 rows) is the likely cause, not measured.

**Batch size and fan-in were left unchanged.** Nothing in the measurements calls for a change, and at this scale the defaults are not stressed.

Bugs found by this phase, all fixed and covered by tests:

- **A failed extract left partial runs, and sort turned them into a "successful" output.** A failed extraction now deletes its `runs/` directory. `sort` refuses a dataset with no `runs/` directory.
- **Same-document references failed.** RDF/XML such as `rdf:about=""` could not be resolved against an empty base IRI. The extractor now uses the input file's URI as the base. The validator uses the same base.
- **The JDK's bundled `jaxp.properties` caps XML entity sizes and replacements at 100,000.** Rhea has a literal longer than that. The extractor now raises `maxGeneralEntitySizeLimit`, `totalEntitySizeLimit` and `entityReplacementLimit` to 10 million, unless the user has already set them. The entity-expansion count limit and RDF4j's decompression limits still apply.
- **A parse error was printed as a stack trace.** `extract` now reports `extract failed: ...` and exits with 3.

Harness note: a background run was killed when the tool call that started it ended, twice. Running each dataset in the foreground worked. The `out/` directory was also lost once, cause unknown. The inputs were intact, and every output was regenerated from them.

- Run on a representative sample of real datasets, with all three compression types.
- Check row counts from the parquet files against an independent count from RDF4j's own parser on the same input.
- Record peak heap and wall-clock time per phase. Adjust batch size (10,000 by default) and merge fan-in only if the measurements call for it.

## Open questions for review

1. Is the overlap prefix depth default of 2 path segments right for your datasets? Some IRI schemes encode everything in one segment, which would make the ranges too coarse.
2. Should the overlap output include exact IRIs as well as the range summary? That could be very large.
3. Do you want the extracted parquet files to keep the source dataset name and file path as columns, for traceability?

## VoID output (added after Phase 7)

`overlap` also writes `<outDir>/void.ttl` (Turtle, https://www.w3.org/TR/void/). Options:

- `--void-base=IRI` sets the base for dataset and linkset IRIs. The default is `urn:rdf-overlap:`.
- Each dataset is a `void:Dataset` with `void:entities` = its distinct IRIs. Each range it shares with another dataset is a `void:subset` with `void:uriSpace` = the range key.
- Each pair is a `void:Linkset` with `void:subjectsTarget` and `void:objectsTarget`. `void:triples` = the shared IRIs. Each shared range is a sub-linkset.
- A link means the same IRI occurs in both datasets. It is not a triple asserted in either dataset, so the linksets have no `void:linkPredicate`.
- Dataset names must match `[A-Za-z0-9._-]+`, because they become IRI path segments.

Checked on the Phase 7 datasets: the Rhea subset counts sum to its 235,418 IRIs, and the Rhea–test linkset has 235,418 triples. Covered by `VoidWriterTest` and a CLI test. 112 tests pass.

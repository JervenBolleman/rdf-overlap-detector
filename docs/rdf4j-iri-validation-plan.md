# Plan: faster IRI validation in RDF4j

Status: draft. Written for upstream: the change belongs in Eclipse RDF4j, not in this project. This project would
use the release once it contains the change.

## Problem

When an RDF parser reads an IRI and `BasicParserSettings.VERIFY_URI_SYNTAX` is on (the default), it checks the IRI
by constructing a `ParsedIRI`. That is the full RFC 3987 parser, which builds the scheme, userinfo, host, port, path,
query and fragment as substrings, decodes percent-encoding into new strings, and parses the port. The object is then
discarded, because `AbstractRDFParser.createURI` only needs to know whether the IRI is valid. The IRI is then created
by `ValueFactory.createIRI`, which checks only that a colon is present (`SimpleIRI.setIRIString`).

The cost is visible in a CPU profile of this project extracting 4 million N-Triples statements (about 8 million IRIs):

| Frame (inclusive share of CPU samples) | Share |
|---|---|
| `NTriplesParser.parseIRI` | 7.6% |
| `AbstractRDFParser.createURI` | 6.5% |
| `ParsedIRI.<init>` (validation only) | 6.0% |
| `ParsedIRI.parsePctEncoded` | 3.5% |
| `ParsedIRI.parseHost`, `parseScheme`, `parsePath`, `parseMember` | about 6% together |

These are inclusive shares of a whole extraction, so the validator is a sizeable part of parse time on IRI-heavy data.
The same cost applies to every RDF syntax, because all of them go through `createURI`.

## Goal

Make validation with `VERIFY_URI_SYNTAX` cheap, without changing what is accepted or rejected, and without changing the
error messages or their positions.

## Plan

### 1. Validation without building an object

Add a static validator that walks the string once with the same character classes and the same grammar, and throws the
same `URISyntaxException` as the constructor. It builds no components and no strings. `createURI` calls the validator,
and `new ParsedIRI(uri)` remains for callers that need the components.

The validator needs the same behaviour in every branch of `parse()`: the `//` authority form, the path-only forms, the
`jar:` scheme, the port digits, the query and fragment, and the percent-encoding rules. Those branches are the grammar,
so they are written once and shared by the validator and the parser where possible.

### 2. ASCII fast path

Most IRIs are ASCII. A lookup table indexed by character, with one bit per character class, makes the common case a
single table read per character. Non-ASCII characters and malformed percent-encodings fall through to the existing full
check, so the rules for them stay unchanged.

### 3. Remove the double validation where it is not needed

Check whether any caller validates the same IRI twice, for example once in the parser and once in the value factory with
a stricter check. Where one check is enough, remove the second.

### 4. Keep the public API

`ParsedIRI`, its constructors and its methods stay as they are. The validator is additive. `VERIFY_URI_SYNTAX` keeps its
meaning.

## Correctness

- **Differential testing.** A test runs the old constructor and the new validator on the same inputs, and requires the
  same accept or reject decision and the same error message. Inputs: the RFC 3986 and RFC 3987 examples, the existing
  `ParsedIRI` tests, random strings over an alphabet that includes the reserved characters, `%` sequences, brackets, and
  non-ASCII text, and the invalid IRIs from the W3C RDF test suites.
- **Existing tests.** The whole RDF4j parser test suite must pass unchanged. It covers the rejection of invalid IRIs in
  each syntax.
- **Positions.** Error positions reported by the parsers must not change, since users rely on them to find bad input.

## Benchmarks

- **Micro-benchmark.** JMH, in the existing benchmark module. Measure nanoseconds per IRI for three sets: short ASCII
  IRIs, long IRIs with a query and fragment, and IRIs with percent-encoding. Report allocation per IRI with JFR.
- **Parser throughput.** N-Triples and Turtle parsing of a large IRI-heavy dump, with `VERIFY_URI_SYNTAX` on and off,
  so the overhead of the check is visible on its own.
- **Target.** No allocation in the validation path, and validation no longer a visible frame in a profile of the parse.
  The numbers are to be measured. They are not claimed here.

## Upstream process

1. Open an issue that describes the cost and links the profile. Ask whether the maintainers want the change before any
   code is written.
2. Agree the design, in particular that `ParsedIRI` keeps its API, and that the validator and the parser share one
   grammar.
3. Send the change as a pull request, with the differential test and the benchmarks. Check the contribution rules of the
   project before submitting. They are not verified here.
4. Expect review of the grammar sharing. That is the part most likely to be questioned, because it touches every IRI
   the parsers read.

## Until the change is released

This project keeps `VERIFY_URI_SYNTAX` on. Turning it off would let invalid IRIs into the data. `IriParts` rejects only
a missing scheme and a malformed port, while RDF4j rejects spaces, bad characters and bad percent-encoding. So the
validation has to stay. The cost can be reduced here only by the parsing changes in section 5 below.

## 5. Options inside this project (not upstream)

- Parse N-Triples with our own IRI reader, which validates the subset of the grammar that matters, and hand the parser
  an already-created IRI. That needs care to keep the same accept and reject decisions, so it is a last resort.

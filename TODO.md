We want to create a detector for how rdf datasets really overlap. The key is that we want to detect IRI ranges that are in two datasets.
To do this we will create one parquet file per dataset, containing all IRIs we have found in that dataset.
The columns will be the full decompsition of the IRIs into their parts (e.g. scheme host port etc).
We will do this in Java21 with a pom.xml for mainting the build.
To write parquet we will use hardwood.dev 1.1.0.Beta2 to read the RDF we will use Eclipse RDF4j 6+.
To improve the compaction of the parquet we will gather upto 10'000 IRIs into a set, sort it and then write such a batch into the parquet file.
As predicates are very repretative we will have a separate set for these to reduce duplication as soon as possible.
Aftwerwards we have a second process to sort all the IRIs and generate a sorted set in one parquet file per dataset.
We will want to decompress on the fly files compressed in xz and zstd next to gz.
This might require either writing or own code or look into a recent RDF4j SNAPSHOT as that might have this new feature build in.

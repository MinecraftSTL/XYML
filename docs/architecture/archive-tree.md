# Archive tree lifecycle

ZIP and TAR wrappers defer directory-node construction until the first hierarchy request. A successful hierarchy is cached; a failed initialization never publishes its partially built root.

ZIP point queries validate all entry names without building directory nodes. They then use the reader index or a normalized alias. Safe virtual-root queries may start with `/`; normal misses return null without building a tree.

Validation rejects traversal, absolute entry names, duplicate normalized files and file/directory conflicts. Entry paths are limited to 4096 UTF-16 code units and 256 components; validation accepts at most 1,000,000 entries. Empty collections allocate no child map until construction needs one.

`getRoot`, `getEntry` and `getDirectory` propagate `IOException`, including deferred validation and closure failures. Initializing and closing one wrapper are mutually excluded; this does not add a general concurrent-I/O guarantee to the underlying archive reader.

Closing a ZIP wrapper with `closeReader=false` does not close the borrowed reader. Wrapper reads still fail after closure. TAR wrappers own their reader and any supplied decompressed temporary archive, and retain cleanup retry after I/O failure.

The benchmark compares validated point lookup with forced full-tree materialization in the same implementation and JVM. It is not a measurement of the previous binary, a live launcher profile or a whole-process allocation claim.

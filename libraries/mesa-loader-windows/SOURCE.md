# mesa-loader-windows upstream provenance

- Repository: <https://github.com/HMCL-dev/mesa-loader-windows>
- Tag: `26.0.4`
- Commit: `fc8a412503ad47e989fd8dfc673b6d501ac8be9e`
- Acquired: 2026-08-16
- Network path: GitHub was accessed through the configured Windows system proxy.
- License: Apache License 2.0; the unmodified upstream text is preserved in `LICENSE`.

## Snapshot contents

The snapshot imports upstream `src/`, `.gitignore`, `README.md`, `LICENSE`, `build.gradle.kts`, and
`settings.gradle.kts` from the locked commit. The tag does not contain tests.

Imported text files are normalized to the repository's LF convention. Redundant trailing blank lines in the
upstream README, settings script, and Java source are removed so the snapshot passes repository whitespace checks.

The upstream `.git/` directory and history, nested Gradle Wrapper directory and scripts, generated output, and
caches are intentionally omitted. Standalone publication, signing, download-plugin, and external 7z configuration
still present in the imported build script is removed when the project is integrated into the XYML root build.

## XYML modifications

The import commit contains no source or namespace changes. The integration commit renames the package to
`space.minecraftstl.xyml.library.mesa`, adopts the shared publication version while preserving Java 6 loader
bytecode, builds three architecture classifiers, verifies their native contents, and enforces the explicit decision
that XYML continues consuming the upstream runtime coordinates from `natives.json` instead of embedding these large
native artifacts. Modified upstream Java retains its Apache-2.0 header and carries a MinecraftSTL modification
notice.

The locked `mmozeiko/build-mesa` 26.0.4 release archives use both the ARM64 branch filter and multi-stream BCJ2;
Apache Commons Compress cannot decode the latter. To avoid a system 7-Zip or JNI build prerequisite, Gradle consumes
the three upstream `org.glavo:mesa-loader-windows:26.0.4` classifier JARs as native payload inputs, verifies the
existing `natives.json` SHA-1 and size metadata, and uses `zipTree` to rebuild the namespaced classifiers. The
approximately 128.86 MiB inputs and outputs remain ignored build artifacts and are not checked into or embedded in
XYML.

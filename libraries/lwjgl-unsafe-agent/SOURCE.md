# lwjgl-unsafe-agent upstream provenance

- Repository: <https://github.com/HMCL-dev/lwjgl-unsafe-agent>
- Tag: `2.0`
- Commit: `6755ad42e444d472a5a849bbe85128e05b6442e5`
- Acquired: 2026-08-16
- Network path: GitHub was accessed through the configured Windows system proxy.
- License: Apache License 2.0, as declared by the source header, README, and published POM metadata.

## Snapshot contents

The snapshot imports upstream `src/`, `.gitignore`, `README.md`, `build.gradle.kts`, and `settings.gradle.kts` from the
locked commit. The tag does not contain a standalone license file, so `LICENSE` supplies the unmodified canonical
Apache License 2.0 text also used by the locked HMCL-dev HelloNBT project.

Imported text files are normalized to the repository's LF convention. The redundant trailing blank line in the
upstream README is removed so the snapshot passes the repository's whitespace checks.

The upstream `.git/` directory and history, `.github/` workflow, nested Gradle Wrapper directory and scripts,
`jitpack.yml`, generated output, and caches are intentionally omitted. Publication, signing, and standalone-build
configuration still present in the imported build script is removed when the project is integrated into the XYML
root build.

## XYML modifications

The import commit contains no source or namespace changes. The integration commit renames the package to
`space.minecraftstl.xyml.library.lwjgl`, isolates Java 25 to this project, generates version metadata, rewrites the
agent manifest, embeds the independently built project JAR into XYML, and adds process-level transformation
verification. Modified upstream Java retains its Apache-2.0 header and carries a MinecraftSTL modification notice.

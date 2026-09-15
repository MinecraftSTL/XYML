# HelloNBT upstream provenance

This upstream snapshot is maintained locally as XoyzNBT.

- Repository: <https://github.com/HMCL-dev/HelloNBT>
- Tag: `0.4.0`
- Commit: `240743139834f8253d1b90b4cb75353285f9dfd5`
- Acquired: 2026-08-16
- Network path: GitHub was accessed through the configured Windows system proxy.
- License: the repository-level license is Apache License 2.0 and its unmodified text is preserved in `LICENSE`.
  Fourteen production files and two test files in this tag retain upstream GPL-3.0-or-later file headers; those
  headers are preserved and the published POM records the file-level exception instead of silently relicensing them.

## Snapshot contents

The snapshot imports upstream `src/`, `docs/`, `buildSrc/`, `.gitattributes`, `.gitignore`, `README.md`,
`CHANGELOG.md`, `build.gradle.kts`, `settings.gradle.kts`, and `LICENSE` from the locked commit.

The upstream `.git/` directory and history, `.github/` workflows, nested Gradle Wrapper directory and scripts,
`jitpack.yml`, generated output, and caches are intentionally omitted. Publication, signing, and standalone-build
configuration still present in the imported build script is removed when the project is integrated into the XYML
root build.

## XYML modifications

The XYML integration and subsequent local maintenance perform the following changes:

- renames the local fork, Gradle project, CLI, and artifact from HelloNBT to XoyzNBT while preserving the upstream
  identity in provenance records;
- renames module and packages from `org.glavo.nbt` to `space.minecraftstl.xyml.library.nbt` without a compatibility
  facade;
- adds a MinecraftSTL modification notice after every preserved upstream Java header;
- replaces the standalone build, nested `buildSrc`, publication, and signing configuration with the XYML root Gradle
  build and local Maven publication;
- publishes `space.minecraftstl.xyml:xoyz-nbt` at the shared XYML release version while recording upstream `0.4.0`
  in the JAR manifest;
- switches XYMLCore and application consumers to the renamed public API and adds module, package, bytecode, artifact,
  license, and serialization verification.

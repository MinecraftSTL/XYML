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

This import commit contains no source or namespace changes. The integration commit records the package rename,
shared publication version, Java compatibility, Gradle-managed Mesa archive acquisition and extraction, three
architecture classifiers, archive-content verification, and the explicit decision that XYML continues consuming
the upstream runtime coordinates from `natives.json` instead of embedding these large native artifacts.

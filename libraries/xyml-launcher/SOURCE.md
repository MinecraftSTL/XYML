# HMCLauncher upstream provenance

- Repository: <https://github.com/HMCL-dev/HMCLauncher>
- Tag: `3.7.0.1`
- Commit: `da0e0c3b4f01fa0edbaf575d9631f093daf70cd1`
- Acquired: 2026-08-16
- Network path: GitHub was accessed through the configured Windows system proxy.
- License: GNU General Public License version 3 with the two Section 7 additional terms stated in `README.md`;
  the unmodified upstream license and terms are preserved.

## Snapshot contents

The snapshot imports upstream `HMCL/`, `.clang-format`, `.gitignore`, `CMakeLists.txt`, `HMCL.ico`, `README.md`, and
`LICENSE` from the locked commit. The tag does not contain automated tests.

Imported text files are normalized to the repository's LF convention. The upstream `.git/` directory and history,
GitHub release workflow, and the standalone `publish/` project with its nested Gradle Wrapper, download, signing,
and Sonatype configuration are intentionally omitted. The XYML root Gradle build replaces that publishing project.

## XYML modifications

The import commit contains no product or source renaming. The integration commit renames the native product to
XYMLLauncher, preserves the original author copyright display, adopts XYML-specific environment variables and Java
runtime paths, builds with CMake and Microsoft Visual C++ on Windows, packages the independently built executable in
XYML, and adds source-fingerprint, checksum, resource-metadata, appended-JAR, environment, argument, working-directory,
and exit-code verification.

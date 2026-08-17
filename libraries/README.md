# Bundled XYML libraries

This directory contains XYML-maintained source forks of selected HMCL-dev projects that are built in the same Git
repository. Each library remains an independent build result and retains its upstream license and copyright notices.
No directory under `libraries/` is a Git submodule or contains upstream Git history.

## Versioning

The XYML application and the four bundled library projects use the shared `xymlReleaseVersion` value resolved by the
root Gradle build. Existing projects that do not opt into this convention, including `XYMLCore`, retain their current
version semantics. Upstream tag versions are provenance metadata and do not replace the XYML publication version.

The Gradle runtime and the default Java project toolchain use Java 17. Only `lwjgl-unsafe-agent` overrides that
default with a Java 25 toolchain. XYMLBoot, the Minecraft bootstrap libraries, and the Mesa loader retain their
existing Java 8 or lower bytecode targets.

## Snapshot provenance

Every imported project must contain a `SOURCE.md` with all of the following fields:

- upstream repository URL;
- immutable upstream tag and commit SHA;
- acquisition date and a statement that GitHub was accessed through the Windows system proxy;
- upstream license and the path to its preserved license text;
- imported and intentionally omitted paths;
- XYML modifications, including namespace, build, product, and resource changes;
- any checked-in binary fallback, together with its source fingerprint and cryptographic checksum.

Source snapshots include upstream source code, tests, documentation, and license material. Nested Gradle wrappers,
publication/signing configuration, release workflows, generated output, caches, and complete upstream history are
excluded. Modified files retain their upstream headers and receive a clear XYML modification notice where the
upstream license or additional terms require one.

## Locked upstreams

| Local project | Upstream | Tag | Commit | License |
| --- | --- | --- | --- | --- |
| `hello-nbt` | <https://github.com/HMCL-dev/HelloNBT> | `0.4.0` | `240743139834f8253d1b90b4cb75353285f9dfd5` | Apache-2.0 |
| `lwjgl-unsafe-agent` | <https://github.com/HMCL-dev/lwjgl-unsafe-agent> | `2.0` | `6755ad42e444d472a5a849bbe85128e05b6442e5` | Apache-2.0 |
| `mesa-loader-windows` | <https://github.com/HMCL-dev/mesa-loader-windows> | `26.0.4` | `fc8a412503ad47e989fd8dfc673b6d501ac8be9e` | Apache-2.0 |
| `XYMLL` | <https://github.com/HMCL-dev/HMCLauncher> | `3.7.0.1` | `da0e0c3b4f01fa0edbaf575d9631f093daf70cd1` | GPL-3.0-only with upstream additional terms |

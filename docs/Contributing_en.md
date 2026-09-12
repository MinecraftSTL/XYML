# Contributing Guide

<!-- #BEGIN LANGUAGE_SWITCHER -->
中文 ([简体](Contributing.md), [繁體](Contributing_zh_Hant.md)) | **English**
<!-- #END LANGUAGE_SWITCHER -->

Before integrating HMCL changes, read the [Upstream Merge Guide](UpstreamMerge_en.md).

## Build XYML

### Requirements

Building the complete XYML repository requires both JDK 17 and JDK 25. You can download them here:
[Download Liberica JDK](https://bell-sw.com/pages/downloads/#jdk-25-lts). Use JDK 17 as the Gradle runtime by pointing
`JAVA_HOME` and IntelliJ IDEA's Gradle JVM to it. Make only JDK 25 discoverable to
[Gradle's toolchain support](https://docs.gradle.org/current/userguide/toolchains.html). The root build defaults all
Java projects to Java 17, and only `lwjgl-unsafe-agent` overrides that default with a Java 25 toolchain. The boot and
Minecraft helper modules retain their Java 8 targets, and the Mesa loader retains its older bytecode target.

On Windows, building the native `XYMLL` launcher also requires CMake 3.16 or newer, Visual Studio 2022 Build Tools
with the MSVC x86/x64 C++ tools, and a Windows SDK. The MinGW toolchain is not supported. Builds on other operating
systems verify and use the checked-in executable produced from the same source snapshot.

After installing the JDKs, make sure the `JAVA_HOME` environment variable points to the JDK 17 directory.
You can check the JDK version that `JAVA_HOME` points to like this:

<details>
<summary>Windows</summary>

PowerShell:

```
PS > & "$env:JAVA_HOME/bin/java.exe" -version
openjdk version "17.0.8" 2023-07-18 LTS
OpenJDK Runtime Environment (build 17.0.8+7-LTS)
OpenJDK 64-Bit Server VM (build 17.0.8+7-LTS, mixed mode, sharing)
```

</details>

<details>
<summary>Linux/FreeBSD</summary>

```
> $JAVA_HOME/bin/java -version
openjdk version "17.0.8" 2023-07-18 LTS
OpenJDK Runtime Environment (build 17.0.8+7-LTS)
OpenJDK 64-Bit Server VM (build 17.0.8+7-LTS, mixed mode, sharing)
```

</details>

<details>
<summary>macOS</summary>

```
> /usr/libexec/java_home -v 17 --exec java -version
openjdk version "17.0.8" 2023-07-18 LTS
OpenJDK Runtime Environment (build 17.0.8+7-LTS)
OpenJDK 64-Bit Server VM (build 17.0.8+7-LTS, mixed mode, sharing)
```

</details>

### Get XYML Source Code

- You can get the latest source code via [Git](https://git-scm.com/downloads):
  ```shell
  git clone https://github.com/MinecraftSTL/XYML.git
  cd XYML
  ```
- You can manually download a specific version of the source code from the [GitHub Release page](https://github.com/MinecraftSTL/XYML/releases).

### Build XYML

To build XYML, switch to the root directory of the XYML project and run the following command:

```shell
./gradlew clean :build
```

The built XYML program files are located in the `XYML/build/libs` subdirectory under the project root.
The root `:build` task assembles and packages the current checkout without invoking `check` or test tasks. Run `:test`
separately when you need the test suite.

### IDEA Gradle Workflows

After importing the repository as a Gradle project, open the Gradle tool window and expand
`XYML > Tasks > stl`. The group contains these entry points:

| Task | Behavior |
| --- | --- |
| `buildMain` | Builds the tip of the local `main` branch in an isolated worktree. |
| `buildBeta` | Builds the tip of the local `beta` branch in an isolated worktree. |
| `buildAlpha` | Builds the tip of the local `alpha` branch in an isolated worktree. |
| `buildDev` | Builds the tip of the local `dev` branch in an isolated worktree. |
| `build` | Assembles and packages the current checkout in place, including its uncommitted changes, without running tests. |
| `test` | Tests the current checkout using the same branch and version inference as `build`. |
| `clean` | Cleans only the current checkout without inspecting or fetching any branch. |
| `run` | Always rebuilds `XYML`, `XYMLCore`, and `XYMLBoot`, then runs the current checkout artifact. |

The `build`, `test`, and `run` tasks always use the current repository root, including on `main`, `beta`, `alpha`, and
`dev` checkouts. None of these tasks switches branches or delegates to a channel task. Without CI version inputs, the current
branch and `HEAD` topology determine the artifact version; uncommitted changes are included in the artifact but do
not increment the version. When invoking Gradle from a shell, keep the leading `:` (`:build` or `:test`) to target the
root task exactly. IntelliJ's Gradle Tooling API may send a bare `build`; the root build script normalizes that
aggregate invocation to package-only subproject builds as well.

Every `run` invocation disables up-to-date and build-cache reuse for tasks in `XYML`, `XYMLCore`, and `XYMLBoot`.
This includes Java compilation, generated language data, processed resources, and the final `shadowJar`. XoyzNBT and
XoyzMCP are handled separately: a successful root `build` records an integrity-checked library snapshot, and `run`
prefers that snapshot until the next successful root `build`. If no complete snapshot is available, or `clean run` is
requested, both libraries are built into a temporary directory with Gradle reuse disabled and are removed after the
launcher JAR is assembled; this fallback never updates the snapshot. A combined `:build run`, `:test run`, or
`:check run` invocation uses the current project outputs instead. Other project dependencies retain their existing
reuse behavior, so an unchanged native source may still reuse the XYMLL executable.

`run` always rebuilds and selects the current `XYML/build/libs` application artifact; it never reuses an application
JAR recorded by a previous root `:build`. It does not write the root result marker or start a second Wrapper process.

The subproject-level task is named `:XYML:runCurrent`; it is intentionally not named `run`, so Gradle does not select
a second launcher process together with the root workflow.

The four channel tasks read only the local `main`, `beta`, `alpha`, and `dev` refs. They perform no fetch or other
online Git operation, and build the selected local branch tip in a temporary detached worktree without switching the
current IDEA checkout. Successful channel artifacts are copied to `build/libs/<branch>` together with
`build-info.properties`; the current-checkout `build` artifact remains in `XYML/build/libs`.

On Windows, the Gradle Wrapper and nested channel builds allow Gradle distribution and dependency downloads to use
the enabled Windows system proxy.

## Debug Options

> [!WARNING]
> This document describes XYML's internal features, which we do not guarantee to be stable and may be modified or removed at any time.
>
> Please use these features with caution, as improper use may cause XYML to behave abnormally or even crash.

XYML provides a series of debug options to control the behavior of the launcher.

These options can be specified via environment variables or JVM parameters. If both are present, JVM parameters will override the environment variable settings.

| Environment Variable        | JVM Parameter                                | Function                                                  | Default Value                                                                                               | Additional Notes          |
|-----------------------------|----------------------------------------------|-----------------------------------------------------------|-------------------------------------------------------------------------------------------------------------|---------------------------|
| `XYML_JAVA_HOME`            |                                              | Specifies the Java used to launch XYML                    |                                                                                                             | Only effective for exe/sh |
| `XYML_JAVA_OPTS`            |                                              | Specifies the default JVM parameters when launching XYML  |                                                                                                             | Only effective for exe/sh |
| `XYML_FORCE_GPU`            |                                              | Specifies whether to force GPU-accelerated rendering      | `false`                                                                                                     |                           |
| `XYML_ANIMATION_FRAME_RATE` |                                              | Specifies the animation frame rate of XYML                | Matches display refresh rates of 90 Hz or higher; otherwise `60`                                            | Overridden by `-Dxyml.swing.animationFrameDelayMillis` |
| `XYML_LANGUAGE`             |                                              | Specifies the default language of XYML                    | Uses the system default language                                                                            |                           |
| `XYML_UI_SCALE`             |                                              | Specifies the UI scaling for XYML                         | Uses the system's current scaling                                                                           | Supports scale factor (1.5), percentage (150%), or DPI (144dpi).                          |
| `XYML_SKIP_OFFLINE_USERNAME_CHECK` |                                      | Disables illegal offline username checks                    | `false`                                                                                                     | Set to `true`; logs a warning and may prevent joining servers or crash the game. |
|                             | `-Dxyml.dir=<path>`                          | Specifies the current data folder of XYML                 | `./.xyml`                                                                                                   |                           |
|                             | `-Dxyml.home=<path>`                         | Specifies the user data folder of XYML                    | Windows: `%APPDATA%\.xyml`<br>Linux/BSD: `$XDG_DATA_HOME/xyml`<br>macOS: `~Library/Application Support/xyml` |                           |
|                             | `-Dxyml.swing.animationFrameDelayMillis=<milliseconds>` | Specifies the Swing animation timer delay in milliseconds | Matches display refresh rates of 90 Hz or higher; otherwise `16`                                            | Must be a positive integer |
|                             | `-Dxyml.self_integrity_check.disable=true`   | Disables self-integrity checks during updates             |                                                                                                             |                           |
|                             | `-Dxyml.bmclapi.override=<url>`              | Specifies the API Root for BMCLAPI                        | `https://bmclapi2.bangbang93.com`                                                                           |                           |
|                             | `-Dxyml.discoapi.override=<url>`             | Specifies the API Root for foojay Disco API               | `https://api.foojay.io/disco/v3.0`                                                                          |                           |
|                             | `-Dxyml.update_source.override=<url>`        | Specifies the channel update-source template for XYML     | `https://github.com/MinecraftSTL/XYML/releases/download/release-channels/xyml-update-{channel}.json`                                            | `{channel}` is replaced before the request |
|                             | `-Dxyml.authlibinjector.location=<path>`     | Specifies the location of the authlib-injector JAR file   | Uses the built-in authlib-injector                                                                          |                           |
|                             | `-Dxyml.native.encoding=<encoding>`          | Specifies the native encoding                             | Uses the system's native encoding                                                                           |                           |
|                             | `-Dxyml.microsoft.auth.id=<App ID>`          | Specifies the Microsoft OAuth App ID                      | Uses the built-in Microsoft OAuth App ID                                                                    |                           |
|                             | `-Dxyml.curseforge.apikey=<Api Key>`         | Specifies the CurseForge API key                          | Uses the built-in CurseForge API key                                                                        |                           |
|                             | `-Dxyml.native.backend=<auto/jna/none>`      | Specifies the native backend used by XYML                 | `auto`                                                                                                      |                           |
|                             | `-Dxyml.hardware.fastfetch=<true/false>`     | Specifies whether to use fastfetch for hardware detection | `true`                                                                                                      |                           |

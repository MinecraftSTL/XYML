# 贡献指南

<!-- #BEGIN LANGUAGE_SWITCHER -->
**中文** (**简体**, [繁體](Contributing_zh_Hant.md)) | [English](Contributing_en.md)
<!-- #END LANGUAGE_SWITCHER -->

合并 HMCL 上游更改前，请先阅读[上游合并指南](UpstreamMerge.md)。

## 构建 XYML

### 环境需求

构建完整的 XYML 仓库需要同时安装 JDK 17 和 JDK 25。你可以从此处下载它们：[Download Liberica JDK](https://bell-sw.com/pages/downloads/#jdk-25-lts)。
请将 `JAVA_HOME` 和 IntelliJ IDEA 的 Gradle JVM 指向 JDK 17，仅需确保 [Gradle 工具链](https://docs.gradle.org/current/userguide/toolchains.html)能够发现 JDK 25。
根构建默认让所有 Java 项目继承 Java 17，只有 `lwjgl-unsafe-agent` 单独覆盖为 Java 25 工具链；
启动模块和 Minecraft 辅助模块继续以 Java 8 为目标，Mesa 加载器也继续保留更低的字节码目标。

在 Windows 上构建原生 `XYMLL` 启动器还需要 CMake 3.16 或更高版本、带 MSVC x86/x64 C++ 工具的
Visual Studio 2022 Build Tools，以及 Windows SDK。不支持 MinGW 工具链。其他操作系统会验证并使用仓库中
由同一份源码构建的可执行文件。

安装 JDK 后，请确保 `JAVA_HOME` 环境变量指向 JDK 17 目录。
你可以这样查看 `JAVA_HOME` 指向的 JDK 版本:

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

### 获取 XYML 源码

- 通过 [Git](https://git-scm.com/downloads) 可以获取最新源码:
  ```shell
  git clone https://github.com/MinecraftSTL/XYML.git
  cd XYML
  ```
- 从 [GitHub Release 页面](https://github.com/MinecraftSTL/XYML/releases)可以手动下载特定版本的源码。

### 构建 XYML

想要构建 XYML，请切换到 XYML 项目的根目录下，并执行以下命令:

```shell
./gradlew clean :build
```

构建出的 XYML 程序文件位于根目录下的 `XYML/build/libs` 子目录中。
根 `:build` 任务只负责按当前工作树组装和打包，不会调用 `check` 或测试任务；需要测试时请单独运行 `:test`。

### IDEA Gradle 构建流程

将仓库作为 Gradle 项目导入后，打开 Gradle 工具窗口并展开 `XYML > Tasks > stl`。该分类包含以下入口：

| 任务 | 行为 |
| --- | --- |
| `buildMain` | 在隔离工作树中构建本地 `main` 分支尖端。 |
| `buildBeta` | 在隔离工作树中构建本地 `beta` 分支尖端。 |
| `buildAlpha` | 在隔离工作树中构建本地 `alpha` 分支尖端。 |
| `buildDev` | 在隔离工作树中构建本地 `dev` 分支尖端。 |
| `build` | 按当前工作树组装并打包，包括未提交改动，但不运行测试。 |
| `test` | 使用与 `build` 相同的分支和版本解析规则测试当前工作树。 |
| `clean` | 只清理当前工作树，不检查或拉取任何分支。 |
| `run` | 始终重新构建 `XYML`、`XYMLCore` 和 `XYMLBoot`，然后运行当前工作树制品。 |
| `releasePromoteAlpha` | 把本地 `dev` 以双亲 `--no-ff` 合并晋升到 `alpha`，不推送。 |
| `releasePromoteBeta` | 把本地 `alpha` 以双亲 `--no-ff` 合并晋升到 `beta`，不推送。 |
| `releasePromoteMain` | 把本地 `beta` 晋升到 `main` 并完成 `main -> beta -> alpha -> dev` 反向同步，不推送。需 `-Pxyml.release.stableIncrement`（`major`、`minor` 或 `patch`）。 |

`releasePromoteAlpha`、`releasePromoteBeta`、`releasePromoteMain` 只读取本地 `main`、`beta`、`alpha`、`dev` 引用：先建立临时工作区，逐级生成双亲 `--no-ff` 合并，在合并提交创建前跑发布门禁，最后用一次事务原子更新本地引用，并输出推送与回退命令但不推送。可选属性：`-Pxyml.release.verify=standard` 或 `full`、`-Pxyml.release.skipTests=true`、`-Pxyml.release.dryRun=true`。

即使当前签出的是 `main`、`beta`、`alpha` 或 `dev`，`build`、`test` 和 `run` 也始终使用当前仓库根目录；这些任务都不会切换分支或委托渠道任务。
没有 CI 版本输入时，制品版本由当前分支与 `HEAD` 拓扑决定；未提交改动会进入制品，但不会使版本号递增。从命令行调用时建议保留任务名前的 `:`（`:build` 或 `:test`）以精确指向根任务。IntelliJ 的 Gradle Tooling API 可能会发送裸 `build`；根构建脚本也会将这种聚合调用规范化为只组装子项目。

每次调用 `run` 都会禁止 `XYML`、`XYMLCore` 和 `XYMLBoot` 中的任务复用最新输出或构建缓存，包括 Java 编译、语言数据生成、
资源处理和最终 `shadowJar`。XoyzNBT 和 XoyzMCP 使用单独的规则：成功的根 `build` 会登记一份经过完整性校验的库快照，
`run` 会优先使用该快照，直到下一次根 `build` 成功。若不存在完整快照，或执行 `clean run`，两个库会在禁用 Gradle 复用的
临时目录中构建，并在启动器 JAR 组装完成后删除；这种临时回退不会更新快照。同一次调用中包含 `:build run`、`:test run` 或 `:check run`
时则使用当前项目输出。其他项目依赖保留原有复用规则，因此原生源码未变化时仍可复用已有的 XYMLL 可执行文件。

`run` 始终重新构建并选择当前 `XYML/build/libs` 中的应用制品，不会复用之前根 `:build` 记录的应用 JAR、写入根结果清单或
启动第二个 Wrapper。
子项目任务改名为 `:XYML:runCurrent`，不再使用 `run`，以免 Gradle 执行根工作流时同时选中第二个启动器进程。

四个渠道任务只读取本地 `main`、`beta`、`alpha` 和 `dev` 引用，不会执行拉取或其他在线 Git 操作。任务会在临时的游离 worktree
中构建所选本地分支尖端，不会切换 IDEA 当前工作树。成功的渠道构建产物会连同 `build-info.properties` 复制到
`build/libs/<branch>`；当前工作树的 `build` 产物仍位于 `XYML/build/libs`。

在 Windows 上，Gradle Wrapper 和嵌套渠道构建允许 Gradle 发行包及依赖下载使用已启用的 Windows 系统代理。

## 调试选项

> [!WARNING]
> 本文介绍的是 XYML 的内部功能，我们不保证这些功能的稳定性，并且随时可能修改或删除这些功能。
>
> 使用这些功能时请务必小心，错误地使用这些功能可能会导致 XYML 行为异常甚至崩溃。

XYML 提供了一系列调试选项，用于控制启动器的行为。

这些选项可以通过环境变量或 JVM 参数指定。如果两者同时存在，那么 JVM 参数会覆盖环境变量的设置。

| 环境变量                        | JVM 参数                                       | 功能                             | 默认值                                                                                                         | 额外说明         |
|-----------------------------|----------------------------------------------|--------------------------------|-------------------------------------------------------------------------------------------------------------|--------------|
| `XYML_JAVA_HOME`            |                                              | 指定用于启动 XYML 的 Java             |                                                                                                             | 仅对 exe/sh 生效 |
| `XYML_JAVA_OPTS`            |                                              | 指定启动 XYML 时的默认 JVM 参数          |                                                                                                             | 仅对 exe/sh 生效 |
| `XYML_FORCE_GPU`            |                                              | 指定是否强制使用 GPU 加速渲染              | `false`                                                                                                     |
| `XYML_ANIMATION_FRAME_RATE` |                                              | 指定 XYML 的动画帧率                  | 自动匹配 90 Hz 及以上的显示器刷新率，否则为 `60`                                                                    | 会被 `-Dxyml.swing.animationFrameDelayMillis` 覆盖 |
| `XYML_LANGUAGE`             |                                              | 指定 XYML 的默认语言                  | 使用系统默认语言                                                                                                    |
| `XYML_UI_SCALE`             |                                              | 指定 XYML 的 UI 缩放比例                 | 遵循系统当前的缩放比例                                                                                       | 支持倍数 (1.5)、百分比 (150%) 或 DPI (144dpi) |
| `XYML_SKIP_OFFLINE_USERNAME_CHECK` |                                      | 完全跳过非法离线用户名检查                 | `false`                                                                                                      | 设为 `true` 后启用，会记录警告，可能导致无法加入服务器或游戏崩溃。 |
|                             | `-Dxyml.dir=<path>`                          | 指定 XYML 的当前数据文件夹               | `./.xyml`                                                                                                   |              |
|                             | `-Dxyml.home=<path>`                         | 指定 XYML 的用户数据文件夹               | Windows: `%APPDATA%\.xyml`<br>Linux/BSD: `$XDG_DATA_HOME/xyml`<br>macOS: `~Library/Application Support/xyml` |              |
|                             | `-Dxyml.swing.animationFrameDelayMillis=<milliseconds>` | 指定 Swing 动画计时器的毫秒延迟            | 自动匹配 90 Hz 及以上的显示器刷新率，否则为 `16`                                                                    | 必须为正整数 |
|                             | `-Dxyml.self_integrity_check.disable=true`   | 检查更新时不检查本体完整性                  |                                                                                                             |              |
|                             | `-Dxyml.bmclapi.override=<url>`              | 指定 BMCLAPI 的 API Root          | `https://bmclapi2.bangbang93.com`                                                                           |              |
|                             | `-Dxyml.discoapi.override=<url>`             | 指定 foojay Disco API 的 API Root | `https://api.foojay.io/disco/v3.0`                                                                          |
|                             | `-Dxyml.update_source.override=<url>`        | 指定 XYML 分渠道更新源模板            | `https://github.com/MinecraftSTL/XYML/releases/download/release-channels/xyml-update-{channel}.json`                                            | 请求前会替换 `{channel}` |
|                             | `-Dxyml.authlibinjector.location=<path>`     | 指定 authlib-injector JAR 文件的位置  | 使用 XYML 内嵌的 authlib-injector                                                                                |              |
|                             | `-Dxyml.native.encoding=<encoding>`          | 指定原生编码                         | 使用系统的本机编码                                                                                                   |              |
|                             | `-Dxyml.microsoft.auth.id=<App ID>`          | 指定 Microsoft OAuth App ID      | 使用 XYML 内置的 Microsoft OAuth App ID                                                                          |              |
|                             | `-Dxyml.curseforge.apikey=<Api Key>`         | 指定 CurseForge API 密钥           | 使用 XYML 内置的 CurseForge API 密钥                                                                               |              |
|                             | `-Dxyml.native.backend=<auto/jna/none>`      | 指定XYML使用的本机后端                  | `auto`                                                                                                      |
|                             | `-Dxyml.hardware.fastfetch=<true/false>`     | 指定是否使用 fastfetch 检测硬件信息        | `true`                                                                                                      |

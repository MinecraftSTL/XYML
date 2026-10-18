# 上游合并指南

<!-- #BEGIN LANGUAGE_SWITCHER -->
**中文** (**简体**, [繁體](UpstreamMerge_zh_Hant.md)) | [English](UpstreamMerge_en.md)
<!-- #END LANGUAGE_SWITCHER -->

本文是 XYML 维护者合并 HMCL 上游更改时的检查清单。简体中文版本是本指南的基准；英文和繁体中文版本应与本文保持相同的章节、命令、路径和约束。

## 适用范围与当前差异

XYML 是从 HMCL 演进而来的独立项目。`origin` 指向 XYML，`upstream` 指向 HMCL-dev/HMCL。两棵树已经发生架构和产品边界分化，不能把上游文件当作同构项目直接覆盖到 XYML。

每次同步都要记录本地 `dev`/`HEAD`、所用 `upstream/main` 的完整 SHA、共同祖先、双方独有提交数和待引入补丁统计。使用缓存引用时记录其提交时间和来源，不把它称为远端最新状态。

下文命令应在每次实际同步时重新执行。三点语法查看的是从共同祖先到上游的待引入补丁；最终树对比只用于发现架构、API 与产品边界差异，不能代替待引入范围分析，也不能作为整文件覆盖依据。

## 合并前检查

### 检查工作树和 ref

先在没有未提交改动的集成分支上工作。当前 `dev` 可能已被另一个 linked worktree 占用，不能为创建分支而切换或重置那个工作树。

```shell
git status --short --branch
git worktree list --porcelain
git branch -vv
git remote -v
git show-ref
git rev-parse HEAD
git rev-parse upstream/main
git show -s --format="%H%n%ad%n%s" --date=iso upstream/main
git rev-parse -q --verify MERGE_HEAD
git diff --name-only --diff-filter=U
```

确认以下条件：

- 工作树干净，没有 `MERGE_HEAD`、rebase 或 cherry-pick 状态。
- 目标分支没有在另一个 linked worktree 中以未提交状态占用；若已占用，改用独立集成分支和工作树。
- `origin`、`upstream` URL 和完整的 `upstream/main` SHA 已记录。
- 不把本地 `dev`、发布分支或用户未提交改动直接作为合并目标。

如果使用缓存的上游 ref，额外记录来源和时间：

```shell
git show -s --format="%H %ci %s" upstream/main
git reflog show --date=iso upstream/main
```

不要在没有明确授权时执行 `git fetch`、`git push` 或修改共享远端 ref。

### 计算待引入范围

确认远端和 ref 后，再记录差异和重复提交：

```shell
git merge-base HEAD upstream/main
git rev-list --left-right --count HEAD...upstream/main
git log --cherry-pick --left-right --oneline HEAD...upstream/main
git diff --shortstat HEAD...upstream/main
git diff --name-status --find-renames HEAD...upstream/main
git log --oneline --reverse HEAD..upstream/main
```

只有得到明确授权时才执行网络操作，例如 `git fetch upstream main`。若采用本地缓存的 `upstream/main`，应在记录中写明 ref 的完整 SHA，并且本流程不隐式 fetch 或 push。先检查 `git log --cherry-pick` 和补丁内容，再决定是完整合并、选择性移植还是跳过；不要仅按文件名判断重复。为每个上游提交标记 `direct review`、`manual adaptation`、`product decision` 或 `skip`，并记录对应的 XYML 路径、处理理由和测试。

## 上游变更分类

按行为而不是仅按文件名分类；每次同步都将实际待引入的提交登记为 `direct review`、`manual adaptation`、`product decision` 或 `skip`，记录上游 SHA、本地对应入口、适配/跳过理由和测试结果。

| 类别 | 检查范围 | XYML 的处理重点 |
| --- | --- | --- |
| 核心逻辑 | 认证、下载、完整性、加载器、Archive/ZIP、实例与整合包 | 比对当前调用方、资源声明、错误处理和边界测试。 |
| 数据模型/API | record、序列化、仓库/布局/快照、设置与账户 | 核对公开契约、字段名、schema、所有权与兼容决策。 |
| 手工适配 | JavaFX 控件/状态、任务呈现、导航、主题、更新提示 | 移植到本地 Swing、observable、任务及服务入口，禁止恢复 JavaFX 依赖。 |
| 产品决策 | 功能删除、供应方能力、运行时、发布与品牌 | 默认保留本地功能和渠道；涉及外部行为先确认。 |
| 文档与本地化 | 文档语言、文件名、宏、链接、I18N 键 | 文档按本地语言规则映射，资源按键合并并保持三语同步。 |

## 不能直接接受的文件组

上游路径使用 `HMCL`、`HMCLCore` 和 `HMCLBoot`，本地路径使用 `XYML`、`XYMLCore` 和 `XYMLBoot`。上游提交中的路径必须先映射到本地模块、包和测试后才能应用。先建立映射，再逐文件移植；不要通过选择整个目录的 `ours` 或 `theirs` 来隐藏差异。

## XYML 专属适配规则

### 命名空间、资源和构建

逐项建立映射表，而不是全局替换：

| 上游 | XYML |
| --- | --- |
| `HMCL` | `XYML` |
| `HMCLCore` | `XYMLCore` |
| `HMCLBoot` | `XYMLBoot` |
| `org.jackhuang.hmcl` | `space.minecraftstl.xyml` |

还要检查反射字符串、服务加载器文件、主类、资源目录、系统属性、序列化名称和清单。可用 `git log --follow` 追踪历史路径，但不要按路径批量移动文件。

### UI、可观察状态和线程迁移

- JavaFX 控件、属性、绑定和可观察集合不能直接套用。将业务行为映射到 `XYML/src/main/java/space/minecraftstl/xyml/ui/swing`，Core 状态沿用 `XYMLCore` 的 `observable` 契约；保留具体事件载荷、通知顺序和订阅释放。
- 后台 I/O 沿用 `Schedulers.io()`，UI continuation 使用已安装的 `Schedulers.ui()`，并核实 Swing EDT 归属；不要恢复 `Schedulers.javafx()`、`FXUtils` 或 JavaFX 类型。
- 核对页面缓存、导航、对话框关闭和异步结果失效边界。上游删除的 UI 文件仍可能包含应移植的通用逻辑，不能仅因本地没有同名文件就跳过整个提交。
- 主题、图片和颜色沿用现有工具包中立的 Core 数据与 Swing 边界转换。架构约束见 [Swing UI 迁移](architecture/swing-ui-migration.md)。

### Task 执行、资源和生命周期迁移

- 上游任务和取消回调必须适配本地 `Task`、`TaskExecutor`、`TaskExecutionRegistry` 和 `TaskResource`，不能只解决签名冲突或恢复旧任务对话框。
- 核对读写路径、实例、游戏目录、缓存与共享资源声明；沿用资源仲裁和清理 API，不用全局资源掩盖缺失的精确声明，也不把未知资源擅自标成无资源的编排阶段。
- `start()` 不代表工作已完成，也不保证立即进入运行态。保留 `WAITING`、运行、取消请求和最终状态的区别，核对重试、进度、日志、订阅及清理失败的可见性。
- 用户可见任务通过现有 `TaskLaunchController` 接入；自动打开任务页选择“进行中”，手动进入则保留既有选项卡。关闭发起页面不能取消已交由任务系统拥有的安装任务。
- 补充资源冲突/并行、等待中取消、异常及中断清理、页面关闭后继续执行、状态与进度发布测试；不要以任务已提交或 UI 已关闭作为执行成功的依据。

### 仓库、安装和远程附加内容入口

- 上游仓库、实例、布局和快照 API 先与 `XYMLGameRepository`、`GameRepository`、`GameRepositorySnapshot` 及实际调用方逐项比对；同名快照不代表字段、所有权和刷新事件完全兼容。
- `GameRepositoryDraft` 只覆盖显式加入草稿的文件和参与者；已有安装、重命名、删除、整合包与修复入口不会自动获得整操作回滚或跨重启恢复。约束见 [Repository draft API](architecture/repository-drafts.md)。
- 游戏安装映射到本地 `game/install` 服务和任务工厂，远程内容映射到现有 `addon` 仓库及 Swing 下载/更新入口；保留仓库实例、目标实例、运行目录、加载器和资源类型的明确上下文。
- 核对 CurseForge/Modrinth 单例、`RemoteAddon.Type`、分页、推荐/测试版本、依赖选择、更新和文件操作失败反馈。不要因上游复用组件而扩大供应方能力，或丢失本地排序、筛选、去重和并行安装隔离。

### 设置、账户和持久化边界

- 双方都使用分离的设置/状态和 JSON schema，但不能据此判定格式与加载策略一致。逐项核对 `SettingsManager`、`GameSettings`、`docs/schemas`、文件位置、schema 版本、未知字段和不可写状态。
- 本地账户元数据与私密数据分开存储；检查本地/用户作用域、账户 ID、认证工厂、令牌刷新和游戏档案缓存。不得将私密字段写入普通配置、日志、任务快照或导出资料。
- 本地实例配置迁移策略位于 `InstanceConfigMigrationPolicy` 并由设置预设持有；它不是上游 `LegacyConfigMigrator`、`LegacyGameSettingsMigrator` 或 `MigrationReceipt` 的等价实现，不能直接引入 HMCL 旧文件扫描和自动转换。
- 如果确需改变持久化格式、公共 API 或外部调用契约，先列明独立转换、主程序边界适配、明确不兼容三种方案及影响，取得确认后再实施。未经确认不删除旧读取、不添加迁移器，也不默认 HMCL 文件就是 XYML 的旧数据。

### 主题、启动和自更新

- 主题变更映射到本地 `theme` 模型、主题包仓库及 Swing 外观设置，保留主题引用、亮度偏好、背景和包读取边界；不要恢复 JavaFX CSS、MonetFX 或上游主题管理器。
- 启动、错误提示与更新变更分别检查 Swing 启动入口、`UpdateApplier`、`UpdateStartupResult`、`UpdateChannel` 和 `XYMLDownloadTask`；上游 JavaFX 失败时回退 Swing 的实现不能整段搬入 Swing-only 启动流程。
- 保留离线启动、更新制品完整性、替换/重启参数以及 XYML 下载源、应用 ID、`.xyml` 和 `xyml.*`；上游版本号、更新端点与发布渠道不是本地默认值。

### 本地功能和安全边界

- 核对存档、资源包、光影包、原理图、模组、整合包导出和实例配置迁移的现有入口。上游新增、重命名或删除页面时，先判断本地是否已有不同实现；MultiMC 导出仍是本地功能，不能因上游删除而一并移除。
- 崩溃分析、日志、修复任务和 Java 运行时管理有本地服务及 UI；通用修复应落到现有入口，不恢复上游页面或假设 GUI 与 MCP 修复共享所有状态和回滚范围。
- `XYMLCore`/`XYML` 的 `mcp` 与应用 `nbt`、`libraries/xoyz-mcp`、`libraries/xoyz-nbt` 属于本地边界；保留鉴权、脱敏、删除确认、路径限制、有界解析和事务性写入，不用上游缺少这些模块作为删除理由。

### 内置库、构建和代码契约

- 本地 `settings.gradle.kts` 还包含 `xoyz-nbt`、`xoyz-mcp`、`XYMLL`、`lwjgl-unsafe-agent`、`mesa-loader-windows`。按 `libraries/README.md` 和导入库的 `SOURCE.md` 审查源码、资源、许可证、固定 SHA 与身份测试；不能改回上游依赖坐标或覆盖本地修改。
- 应用/Core/buildSrc 使用 Java 17；Boot 和 Minecraft 引导库的生产代码保持 Java 8，`lwjgl-unsafe-agent` 使用 Java 25 工具链。核对实际 `build.gradle.kts`、Wrapper、版本目录、fat JAR 与 `verifyOfflineUiArtifact`，不以构建 JVM 版本替代目标字节码检查。
- 开发变更基于当前 `dev`，验证后以 `--no-ff` 集成回 `dev`；发布遵循 `dev -> alpha -> beta -> main`。保留 `config/project.properties` 的 `stableVersion`、版本解析和发布任务；合并上游不是发布授权。
- 移植的首方 Java 声明遵循当前 `AGENTS.md`：完整许可证头、`@NotNullByDefault`、精确 `@Nullable`、集合所有权注解和 `///` 文档。保留嵌入式第三方的许可证和局部风格，不关闭质量检查。

### 文档默认语言和宏迁移

- 上游无语言后缀的文档是英文，简体中文使用 `_zh_Hans.md`；XYML 无后缀文档是简体中文，英文使用 `_en.md`，繁体中文使用 `_zh_Hant.md`。应按内容语言映射，不能把上游无后缀文件直接覆盖到本地同名文件。
- 同时映射相对链接、锚点、`BLOCK`、`COPY` 来源与 `LANGUAGE_SWITCHER`；不能只改文件名。检查 `buildSrc` 的 `DocumentLocale`、文档树和生成器，保留简体中文优先的语言顺序及缺失译文回退。
- 无后缀的 `I18N.properties` 仍是英文默认资源，与文档规则不同；资源键、占位符和转义逐项合并。详细规则见 [本地化指南](Localization.md#文档与本地化资源)。
- 修改任一 `docs/**/*.md` 后运行 `updateDocuments`，检查全部重写差异、各语言链接和复制块；不能手工修补生成输出来绕过源宏或语言映射错误。

### 必须保留的本地边界

- `XYML` 是 Swing-only；`XYMLCore` 保持 toolkit-neutral，界面行为应落在现有 `XYML/ui/swing` 边界。上游 JavaFX/JFoenix 代码只能作为行为参考。
- 保留 `xoyz-nbt`、`xoyz-mcp`、`XYMLL`、`lwjgl-unsafe-agent`、`mesa-loader-windows` 等本地库，以及导入库的 `SOURCE.md`、许可证、上游 SHA 和身份测试。
- 检查 `XYMLTransformerDiscoveryService`、`XYMLMultiMCBootstrap`、服务描述符、系统属性、制品名和 `HMCLMultiMCBootstrap-1.0.jar` 资源引用。
- 保留 XYML 的版本解析、四渠道发布流程、离线制品校验、MCP 鉴权与脱敏、stdio 边界、NBT 有界/事务性编辑、任务资源所有权和现有目录筛选。
- 构建脚本、工作流、Jenkins、版本属性和发布配置不能整文件替换；特别检查 Java 17/25/8 工具链和 `stableVersion` 契约。

### 本地行为契约

解决上游冲突时不得删除或弱化以下 XYML 契约：

- MCP bearer 鉴权、敏感数据脱敏和本地 stdio 传输边界。
- 有界、严格、事务性的 NBT 解析与编辑。
- 任务资源锁、所有权、取消、重试和并发更新规则。
- 崩溃分析、诊断输出和离线行为。
- Swing 目录、模组和版本筛选，以及去重和排序行为。

### 本地化和数据兼容

合并前后都要用旧配置、旧实例清单、旧模组包和旧缓存做 round-trip 检查。record 化或字段改名时检查 Gson 字段名、旧数据、导入的实例/模组包和反射调用方，并在需要时保留兼容访问器或迁移路径；改变类路径顺序、压缩读取选项或下载完整性检查时，验证错误处理、重复条目、父目录和边界路径。

I18N 必须按键合并，并同步 `I18N.properties`、`I18N_zh_CN.properties` 和 `I18N_zh.properties`：保留格式化占位符、Properties 转义、换行和唯一键，单独审查品牌文案，不用上游 HMCL 文案覆盖本地品牌。还要检查 classpath 顺序、服务描述符、清单、嵌入式 JAR 和离线缓存，使打包名称仍符合 XYML 启动流程。文档宏、相对链接和三语文件也属于本地化契约。

## 冲突解决流程

1. 从目标快照创建并进入集成分支，并确认目标分支没有被其他工作树占用，例如：

   ```shell
   git switch -c <integration-branch> dev
   ```

2. 记录已评估的上游提交。需要保留拓扑时，在确认分类结果后使用：

   ```shell
   git merge --no-ff upstream/main -m "merge(upstream): integrate <range>"
   ```

   对不兼容的 UI 或产品变更，采用有记录的选择性移植或跳过；不要盲目 cherry-pick，也不要对整个目录使用 `ours`/`theirs`。

3. 按本地模块和职责边界逐文件解决冲突，必要时同步改写路径、包名、资源和测试。不要用整个目录的 `ours` 或 `theirs` 隐藏差异。

4. 每次解决冲突后检查：

   ```shell
   rg -n -uu "<<<<<<<|=======|>>>>>>>" .
   rg -n -uu "org\.jackhuang\.hmcl|HMCLCore|HMCLBoot|/HMCL|\\HMCL|JavaFX|javafx|JFoenix|MonetFX|OpenJFX|hmcl\.|\.hmcl" XYML XYMLCore XYMLBoot libraries minecraft
   git diff --check
   git diff --name-only --diff-filter=U
   ```

   然后逐项检查服务描述符、`META-INF`、资源索引、清单中的主类、系统属性、制品名及序列化字段。不要用一次全局搜索替代逐文件审查。

5. 如果真实合并正在进行且无法继续，只能在该合并状态下使用 `git merge --abort`；不要用破坏性重置或整树回滚来掩盖未解决的冲突。

## 验证门槛

### 文档与本地化

修改本指南或任一 `Contributing` 文档后，从仓库根目录运行：

```shell
.\gradlew.bat updateDocuments
```

检查三份指南和三份 `Contributing` 文档中的语言切换器、相对链接、标题和生成差异；不要手工改写生成的 `LANGUAGE_SWITCHER` 内容。

### 代码与资源

按变更类别运行最小测试集：认证和账户登录、CurseForge 下载与完整性、游戏依赖/JAR 同步、Archive/ZIP 路径安全、实例父级和循环引用、启动器构建、modpack 导出，以及受影响的 Swing/EDT 行为。具体检查 Archive/ZIP 的重复条目、父目录、编码和大小边界；实例缺失父级与循环引用；record 序列化和旧数据 round-trip；本地库身份测试；以及受影响 Swing 控件在 EDT 上的行为和布局。修改本地库或服务组件时运行对应身份测试。

还应按实际触及的差异增加以下验证，不以编译通过替代行为测试：

- Task 资源等待/取消、清理失败、执行注册表发布，以及自动/手动导航和页面关闭后的所有权。
- 仓库快照隔离、草稿冲突/失败恢复及未加入草稿的操作边界；安装和远程内容的实例/供应方/类型上下文。
- 设置与账户 schema、未知字段、不可写状态、私密数据分离和旧数据 round-trip；未经授权不运行真实用户数据迁移。
- 主题包读取、离线启动、更新完整性与替换参数，以及受影响的本地功能、MCP/NBT 和内置库身份检查。

### 仓库检查

文档或源码变更完成后运行：

```shell
.\gradlew.bat checkstyle checkTranslations --no-daemon --parallel --stacktrace
.\gradlew.bat build --no-daemon --parallel
.\gradlew.bat :test --no-daemon --parallel
```

使用仓库 Gradle Wrapper 和模块要求的 JDK 17/JDK 25，并优先使用已记录的本地缓存。若完整构建受 JDK、原生工具、网络、权限、离线缓存或平台限制，运行最强的可行替代检查，并在交付记录中把代码失败、环境跳过和未执行项目分开说明。UI 或构建配置有改动时，还要运行 `verifyOfflineUiArtifact` 或同等离线制品检查，确认没有重新引入 JavaFX/JFoenix 条目。

修改 `docs/**/*.md` 后，必须审查 `updateDocuments` 重写的全部差异，确认语言切换器和相对链接只指向存在的语言文件。由于 `.github/workflows/gradle.yml` 使用 `paths-ignore: '**.md'`，文档-only PR 可能不会触发 Java CI，不能把它当作本地验证的替代品。

### 最终 Git 验证

```shell
git diff --check
git status --short --branch
git show -s --format="%H %P %ci %s" HEAD
git merge-base --is-ancestor upstream/main HEAD
```

## 最终交付检查

- `git diff --check` 通过，搜索不到冲突标记或不应出现的 HMCL/JavaFX 残留。
- 若采用完整合并，`git merge-base --is-ancestor upstream/main HEAD` 成功，且 `git show -s --format=%P HEAD` 显示两个父节点；若选择性移植，保留未成为祖先的原因和提交映射。
- `git status --short` 干净，分支、上游 SHA、生成文档和测试结果已记录。
- 不提交 `build/`、`.gradle*`、IDE 配置、运行数据或本地配置；不隐式 push。

## 完成标准

- 只包含已审查的上游提交、必要的 XYML 适配和对应测试或文档。
- 不存在冲突标记、过时 HMCL 路径、JavaFX/JFoenix 依赖或错误的 `.hmcl`/`hmcl.*` 配置。
- 已运行并记录 `updateDocuments`、`git diff --check`、本地化检查、相关测试和最强可行的完整构建。
- 合并或选择性移植记录保留上游 SHA、处理结论和跳过原因；发布分支仍遵循 `dev -> alpha -> beta -> main` 的 `--no-ff` 拓扑。

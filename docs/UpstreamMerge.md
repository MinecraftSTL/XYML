# 上游合并指南

<!-- #BEGIN LANGUAGE_SWITCHER -->
**中文** (**简体**, [繁體](UpstreamMerge_zh_Hant.md)) | [English](UpstreamMerge_en.md)
<!-- #END LANGUAGE_SWITCHER -->

本文是 XYML 维护者合并 HMCL 上游更改时的检查清单。简体中文版本是本指南的基准；英文和繁体中文版本应与本文保持相同的章节、命令、路径和约束。

## 适用范围与当前差异

XYML 是从 HMCL 演进而来的独立项目。`origin` 指向 XYML，`upstream` 指向 HMCL-dev/HMCL。两棵树已经发生架构和产品边界分化，不能把上游文件当作同构项目直接覆盖到 XYML。

计划勘察基准（2026-09-12）如下，数字只用于说明如何记录一次同步，不是永久版本号：

| 项目 | 基准值 |
| --- | --- |
| 当前 `HEAD` | `9e48f059cf02c2ec96a071448356099840a69a9b` |
| `upstream/main` | `df52bc6e81e2e1116c131483dfb9996fdb7b2b10` |
| 共同祖先 | `b4549b6f68b99bb5fa6d69f94fffffcee0dc36c1` |
| `HEAD...upstream/main` 提交计数 | 本地独有 `528`，上游独有 `26` |
| 待引入差异（`git diff HEAD...upstream/main`） | 76 个文件，新增 1878 行，删除 1124 行 |

上游引用可能在勘察后前移；例如本次编写时已观察到新的上游提交。因此每次实际同步都必须重新记录以下命令的输出。三点语法查看的是从共同祖先到上游的待引入补丁；直接比较两棵最终树会把历史重命名和 XYML 的独立实现混在一起，结果不能直接用于合并决策。

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

把 `git log --reverse HEAD..upstream/main` 得到的每个提交登记到下表的一类，并在登记中写出 XYML 对应路径、保留或跳过的理由以及测试。

| 类别 | 当前上游变化 | XYML 的处理重点 |
| --- | --- | --- |
| 核心逻辑，可优先评估 | Yggdrasil token 每次登录校验（`9d9fc7838`）；CurseForge 重试、哈希和完整性（`e287f672f`）；客户端 JAR 同步（`24702dc5a`） | 映射到现有 `XYMLCore` 任务、仓库和导出器。 |
|  | Forge/Cleanroom 启动修复（`513f53bc0`、`0ad180c08`）；默认构建取消与归档/ZIP 处理（`b2d4d3685`、`9c031c5ac`、`e2d3ac847`） | 保留任务资源锁、离线行为、取消/重试和安全边界。 |
|  | 实例父级清理（`1a258a255`）；mrpack 禁用文件和依赖更新（`1da8c8d6a`、`0f4152406`）；`.gitignore` 的 `*.jfr`（`3742be0aa`） | 为每个行为补充或运行针对性测试。 |
| 数据模型/API | `OptiFineVersion`（`0e3455434`）和 `CurseMetaMod`（`1acfc5500`）改为 record | 检查 Gson 注解、旧 JSON/清单和构造器。 |
|  |  | 确认序列化字段名、调用方和向后兼容，避免机械替换 getter。 |
| 需要手工适配的 UI | 资源包图标缓存、主题、搜索排序、加载器标签和长名称（`886a25a93`、`450504ced`、`8767cc0e9`、`7331f619c`、`7a8f93164`） | 只移植行为到现有 Swing 控件或 toolkit-neutral Core。 |
|  | 加载器重置、树单元格、图标对话框、工具栏和进度控件（`ce51dc4dd`、`b97e7fbd5`、`7f578be08`、`cbc6daf3d`、`ea232b097`） | 禁止恢复 JavaFX、JFoenix、MonetFX、OpenJFX 下载器、module flags 或运行时补丁。 |
| 必须作产品决策 | 上游删除 MultiMC 导出（`df52bc6e8`） | XYML 当前仍公开支持 MultiMC，默认保留。 |
|  |  | 若决定删除，必须同时评审 Core 任务、工厂、UI、I18N、资源和测试，不能只接受上游删除。 |
| 品牌和本地化 | 上游将 Hello Minecraft Launcher 缩写为 HMCL（`1428183e3`） | 不得覆盖 XYML 品牌、`.xyml`、`xyml.*` 或应用 ID。 |
|  |  | 语言键逐项合并并同步英文、简体中文、繁体中文，保留占位符和转义。 |

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

### 必须保留的本地边界

- `XYML` 是 Swing-only；`XYMLCore` 保持 toolkit-neutral，界面行为应落在现有 `XYML/ui/swing` 边界。上游 JavaFX/JFoenix 代码只能作为行为参考。
- 保留 `xoyz-nbt`、`xoyz-mcp`、`XYMLL`、`lwjgl-unsafe-agent`、`mesa-loader-windows` 等本地库，以及每个库的 `SOURCE.md`、许可证、上游 SHA 和身份测试。
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
   git switch -c <integration-branch>
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

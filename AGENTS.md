# XYML 编码代理指南

## 沟通、适用范围与优先级

- 与用户沟通时使用简体中文。
- 先遵循用户当前任务的明确要求，再遵循本文件；实现细节以适用模块的 `build.gradle.kts`、`.editorconfig`、Checkstyle 配置和 CI 为准。
- **本文件的 Java 规则适用于所有新建或实质修改的首方 Java 声明，包括生产代码、测试、嵌套类型、record、enum 和 interface。** 不要因为主干中仍有旧风格代码而降低新代码标准。
- 不要为了统一历史风格批量重写未触及的文件。修改既有声明时，将该声明提升到本规范；保留周围未触及的遗留代码，除非任务明确要求迁移。
- 不得通过关闭、放宽或绕过 Checkstyle、测试、翻译检查或构建任务来让变更通过验证。

- 这些规则约束代理新写或实质修改的代码，不用于批量审查未触及的历史代码。

> **交付前硬性检查：先确认目标模块的 Java 兼容级别；首方 Java 变更必须同时满足许可证头、nullability、不可变性、`///` 文档和 Checkstyle。**

## 稳定版发布与版本号

- 用户要求发布到稳定版时，必须确认用户希望自增主版本号、次版本号还是补丁版本号；若当前请求尚未明确，必须先询问用户，再修改版本、提交、合并、推送或触发发布。用户已明确自增位或完整目标版本号时，以该信息作为确认结果，不要重复询问。
- 自增选定的版本位后，必须将所有更低位清零。例如，`1.2.3` 自增主版本号后为 `2.0.0`，自增次版本号后为 `1.3.0`，自增补丁版本号后为 `1.2.4`。
- 稳定版目标版本必须写入 `config/project.properties` 的 `stableVersion`，并按发布分支模型同步到所有发布分支；发布前验证构建解析出的稳定版与目标版本完全一致。

## 仓库边界与兼容性

| 范围 | 生产代码兼容性 | 说明 |
| --- | --- | --- |
| `XYML/`、`XYMLCore/`、`buildSrc/` | Java 17 | 可以使用 Java 17 语言和 API。 |
| `XYMLBoot/src/main/` | Java 8 | `options.release = 8`；不得引入 Java 9+ API 或 Java 17 语法。 |
| `minecraft/libraries/XYMLTransformerDiscoveryService/`、`minecraft/libraries/XYMLMultiMCBootstrap/` | Java 8 | 同样必须保持 Java 8 字节码和 API 兼容性。 |

- 使用仓库 Gradle Wrapper，并以 JDK 17 或更高版本运行构建。不要依赖系统安装的 Gradle。
- 测试使用 JUnit Jupiter 和 JUnit Platform，放在受影响模块的 `src/test/java`，测试类命名为 `*Test`。
- `space.minecraftstl.xyml` 是首方主命名空间。新代码应放在现有模块和子系统的恰当包中，不要为了方便跨模块复制实现。
- 下列区域是嵌入式第三方或遗留实现：`XYML/src/main/java/com/jfoenix/**`、
  `XYML/src/main/java/space/minecraftstl/xyml/ui/image/apng/**`、`XYML/src/main/java/space/minecraftstl/xyml/ui/skin/**`。
  **除非任务明确指向这些目录，不得顺手格式化、补许可证头、补注解或迁移文档。** 必须修补时采用最小改动，保留其原有许可证和局部风格。

## 文件格式与排版

- 除 `.bat` 外，所有常规文本文件使用 UTF-8 和 LF。Java 使用 4 个空格缩进、不得使用 Tab、行宽以 120 列为目标；
  不要为长 URL、字符串字面量或已有稳定布局做机械换行。
- `.editorconfig` 的通用项写有 `insert_final_newline = false`，但 Checkstyle 强制文件末尾换行。**对受 Checkstyle 覆盖的 Java 文件，以 Checkstyle 和 CI 为准，必须保留末尾换行。**
- Java 使用同行左花括号、常规空格规则和单一空行分隔 package、import、顶层类型、构造器和方法；不保留连续多个空行。不要强制给单语句控制流补花括号，保持邻近代码的风格。
- 保留已有合理的换行和局部布局。只有在确有必要时使用成对的 `// @formatter:off` 与 `// @formatter:on`，并把范围压到最小。
- 导入遵循 `.editorconfig`：普通导入在前，`javax.*` 与 `java.*` 单独分组，静态导入最后并单独分组。删除无用、冗余和非法导入；不要基于个人偏好批量改为或移除通配符导入。
- Kotlin/Gradle Kotlin DSL 遵循 Kotlin 官方风格和仓库 `.editorconfig`：4 空格、无尾随逗号、保持现有多行参数布局。YAML、JSON 使用 2 空格；Markdown 最大 200 列；`.properties` 使用 `key=value` 且等号两侧无空格；`.bat` 必须 CRLF。

## Java 必须遵守的规则

### 许可证与结构

- 除现有 Checkstyle 豁免外，首方 Java 文件必须带 `config/checkstyle/license-header.txt` 所定义的**完整 GPLv3-or-later 头**。
  新文件从相邻首方文件精确复制该头；已有头必须保留。不得缩写、改写或猜测版权归属或年份。
- 每个文件只能有一个顶层类型，文件名必须与外层类型一致。只含静态工具成员的类必须为 `final` 且拥有私有构造器。
- 使用标准数组声明形式；含 type-use 注解的数组写作 `String @Unmodifiable []`，不要写成变量名后置的数组形式。
- 方法最多 12 个参数，`throws` 最多 5 个。需要更多输入时优先引入有语义的值对象、record 或参数对象。
- 单个 Java 文件最多 2000 行，除非配置中已有针对该文件的豁免。不要用巨型类堆积不相关职责。

### Nullability

- **每个新增或实质修改的首方类、接口、enum、record 及需要独立默认契约的嵌套类型都必须标注 JetBrains `@NotNullByDefault`。**
- **任何可能为 `null` 的字段、参数、返回值、局部变量、数组元素位置和泛型实参都必须在精确的 type-use 位置显式标注 `@Nullable`。绝不允许隐式 nullability。**
- 例如使用 `@Nullable String value`、`List<@Nullable String>`、`ObjectProperty<@Nullable Path>`、`Map<String, @Nullable Value>` 和 `String @Nullable []`，而不是只靠注释或调用方猜测。
- `@UnknownNullability` 仅可用于无法由当前 API 表达的泛型/外部互操作契约，并须保持与既有 API 一致；**不得把它当作逃避已知 nullable 契约的替代品。** 能确定为可空时必须使用 `@Nullable`。
- 目标模块若尚未声明 JetBrains Annotations 依赖，先在同一范围内补充正确的 `compileOnly` 依赖，再引入这些注解；不得因模块为 Java 8 而省略 nullability 要求。

### 不可变性与所有权

- **不可变数组、集合和快照必须显式标注 `@Unmodifiable`；暴露底层仍会变化、但调用方不得修改的视图必须标注 `@UnmodifiableView`。** 嵌套集合的相应层级也要标注。
- 注解不提供运行时保护。返回或保存集合前，按所有权使用 `List.copyOf`、`Map.copyOf`、不可变包装或防御性复制；数组不得直接泄漏可变内部状态。
- record 适合稳定的值对象和参数组合；record 组件的 nullability 与不可变性同样必须明确。

### 文档与命名

- **每个新增或实质修改的类、字段、构造器和方法必须有 `///` Markdown 风格 Javadoc。** 修改一个既有 `/** */` 声明时可以将该声明转换为 `///`；不得只为风格一致性迁移整文件。
- 文档通常使用英语，并与相邻首方源码的语言一致。准确描述行为、输入约束、null 语义、返回值、副作用和异常；适用时补充 `@param`、`@return`、`@throws`。`@deprecated` 必须解释替代方案，`@inheritDoc` 必须配合 `@Override`。
- 复杂控制流、并发边界、格式兼容或非直观推导使用简短实现注释；不要为显而易见的赋值或调用添加空泛注释。
- 类型使用 PascalCase，方法和字段使用 camelCase，常量使用 UPPER_SNAKE_CASE。名称必须表达业务含义，避免无语义缩写。

### 正确性与 Checkstyle

- 不得使用 `finalize`、空语句、非末尾 `default`、无意的 `switch` fall-through、`String` 的 `==` 比较，或只实现 `equals`/`hashCode` 其中之一。
- 直接返回布尔表达式，不要写多余的 `if`/`else` 布尔转发；不要新增只接受特定类型、却没有正确覆盖 `equals(Object)` 的协变 `equals`。
- 异常类型必须保持不可变。不得吞掉异常；空 `catch` 仅可在确有意图时使用变量名 `expected` 或 `ignore`，并优先记录原因、恢复或抛出带上下文的异常。
- 所有面向稳定文本的 `String.toLowerCase()` 和 `String.toUpperCase()` 必须传入 `Locale.ROOT`。
- 不要引入冗余修饰符。长整数字面量使用大写 `L`。
- Checkstyle 抑制仅在无法避免且有明确理由时使用 `//CHECKSTYLE:OFF` 与匹配的 `//CHECKSTYLE:ON`；范围必须最小，不能用来掩盖新增代码的问题。

## JavaFX、异步与任务

- 不得阻塞 JavaFX Application Thread。I/O 或后台计算沿用 `Schedulers.io()`；需要更新 UI 的 continuation 使用 `Schedulers.javafx()`。
- 回调可能已在 FX 线程时，使用 `FXUtils.runInFX`；需要断言线程上下文时使用 `FXUtils.checkFxUserThread()`。不要自行猜测线程上下文。
- 新异步流程优先沿用项目 `Task` 的 `runAsync`、`supplyAsync` 和 continuation 链。仓库仍有少量专用线程管理代码，因此不要把“禁止 `new Thread`”写成绝对规则，但新代码不得无故绕过现有调度器。

## 国际化、资源与文档

- 面向用户的新文本应走现有 `I18n`/资源键机制，不要在 UI 中随意硬编码可见文本。
- 新增或改动 `I18N` 用户可见键时，**必须同步英语 `I18N.properties`、简体中文 `I18N_zh_CN.properties` 和繁体中文 `I18N_zh.properties`，并保留格式化占位符和转义语义。** `checkTranslations` 只会单向校验简体中文已有键，不能以任务通过替代三语同步检查。
- `.properties` 的键必须唯一。简体中文使用“账户”而非“帐户”、“其他”而非“其它”，中文括号使用 `（`、`）`；
  繁体中文也使用全角中文括号；文言使用“線”“為”“啓”，不使用“綫”“爲”“啟”。
- `docs/**/*.md` 使用 `<!-- #BEGIN ... -->` / `<!-- #END ... -->` 宏时，保持 `BLOCK`、`LANGUAGE_SWITCHER`、`COPY` 及其属性的正确关系。修改本地化文档、语言切换器、被 `COPY` 的英文块、宏属性或宏中的相对链接后，必须检查生成结果。

## 验证与提交卫生

- Java 或 `.properties` 变更后，运行 CI 同款检查：

  ```powershell
  .\gradlew.bat checkstyle checkTranslations --no-daemon --parallel --stacktrace
  ```

  在 Unix-like 环境使用 `./gradlew checkstyle checkTranslations --no-daemon --parallel --stacktrace`。
- 为行为变更补充受影响模块的 JUnit Jupiter 测试，并运行最小相关测试集。非文档代码完成前，尽量运行：

  ```powershell
  .\gradlew.bat build --no-daemon --parallel
  ```

- **修改任一 `docs/**/*.md` 后运行 `./gradlew updateDocuments`**（Windows 可用 `.\gradlew.bat updateDocuments`），并检查它重写的全部文档差异。
- 提交前检查 `git diff --check` 和 `git status --short`。不要提交 `build/`、`.gradle*`、IDE 配置、运行数据、本地 `gradle.properties` 或其他 `.gitignore` 覆盖的生成物。
- 若完整验证受环境、平台或网络限制，运行最强的相关替代检查，并在交付说明中如实说明未执行项目和原因。

# XYML AI MCP Server

XYML AI MCP Server 是一个 Java 17 的本地 stdio 服务。协议传输、工具注册、崩溃分析适配和操作契约位于 `XYMLCore`；必须依赖应用配置与已初始化游戏仓库的实现和入口位于现有 `XYML` 模块。它只复用已有的实例、设置、模组和启动服务，不监听端口，也不提供通用文件操作接口。

## 启动

在仓库根目录运行：

```powershell
$env:GRADLE_USER_HOME = (Resolve-Path .gradle-user-home).Path
.\gradlew.bat :XYML:shadowJar --no-daemon
$mcpJar = Get-ChildItem .\XYML\build\libs\XYML-*.jar | Sort-Object LastWriteTime -Descending | Select-Object -First 1
java -cp $mcpJar.FullName space.minecraftstl.xyml.ai.Main
```

生产环境建议把 `XYML/build/libs` 中生成的 XYML 启动器 JAR 复制到固定位置，并让 agent 以该 JAR 作为 classpath 启动 `space.minecraftstl.xyml.ai.Main`。stdout 仅用于 MCP JSON-RPC；启动器诊断日志写入 XYML 自己的日志位置，错误信息写入 stderr。

Claude Desktop 配置示例（Windows 路径需要按实际 checkout 调整）：

```json
{
  "mcpServers": {
    "xyml": {
      "command": "C:/Program Files/Java/jdk-17/bin/java.exe",
      "args": [
        "-cp",
        "E:/Stl/Proj/XYML/XYML/build/libs/XYML-1.0.1.0.0.SNAPSHOT.jar",
        "space.minecraftstl.xyml.ai.Main"
      ]
    }
  }
}
```

示例中的 JDK 路径、checkout 路径和 JAR 版本名需要按实际安装位置及构建版本调整，classpath 应指向单个当前版本的启动器 JAR。

## 工具

所有结果同时提供 MCP `structuredContent` 和 JSON 文本。`[L1]` 为只读诊断；`[L2]` 为实例设置或模组操作；`[L3]` 为启动测试进程控制。启动、停止、启动状态查询和删除模组要求参数 `confirmed: true`，否则服务返回 MCP 错误而不会调用 XYML。工具执行与资源读取调度到 `Schedulers.io()`，不会占用 UI 线程。

只读工具：`list_instances`、`get_instance_settings`、`get_mods_directory`、`get_logs`、`analyze_crash`、`list_java_runtimes`、`list_local_mods`。

设置工具：`set_java_version`、`set_memory`、`set_jvm_options`、`set_window_options`。

模组工具：`enable_mod`、`disable_mod`、`remove_mods`。

启动测试工具：`launch_game`、`stop_game`、`get_launch_status`。状态查询本身只读，但按启动测试策略同样要求 `confirmed: true`。

## Resources

- `xyml://instances/{instance_id}/logs/latest.log`：最新日志。
- `xyml://instances/{instance_id}/crash-reports/`：崩溃报告目录中的报告 URI 列表。
- `xyml://instances/{instance_id}/crash-reports/{report_name}`：直接读取一份崩溃报告。

agent 需要修改模组内容时，使用 `get_mods_directory` 获取绝对路径后自行完成文件操作；MCP 不逆向 jar、不解析字节码，也不新增启动器原本没有的通用文件管理能力。

## 验证

`XYMLCore` 中的 JUnit Jupiter 测试覆盖 17 个工具注册、3 个资源模板、确认门禁、JSON 文本结果、CrashReportAnalyzer 结构化输出和 stdio `initialize`/`tools/list` 握手。构建时使用仓库 Gradle Wrapper；Windows 若用户级 Gradle 锁不可写，可将 `GRADLE_USER_HOME` 指向仓库内缓存目录。

真实环境仍需项目负责人验证：使用目标 AI agent 完成 stdio `initialize`/`tools/list` 握手，并在隔离实例中确认设置写入、模组启停/删除以及游戏启动和停止行为。

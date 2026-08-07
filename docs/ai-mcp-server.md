# XYML AI MCP Server

`XYMLAIServer` 是一个 Java 17 的本地 MCP Server。它通过官方 Java SDK 使用 stdio 传输，复用 `XYMLCore`/`XYML` 已有的实例、设置、下载、模组和启动服务，不监听端口，也不提供通用文件操作接口。

## 启动

在仓库根目录运行：

```powershell
$env:GRADLE_USER_HOME = (Resolve-Path .gradle-user-home).Path
.\gradlew.bat :XYMLAIServer:installDist --no-daemon
& .\XYMLAIServer\build\install\XYMLAIServer\bin\XYMLAIServer.bat
```

生产环境建议把 `build/install/XYMLAIServer` 复制到固定位置，并让 agent 直接执行其中的 `bin/XYMLAIServer.bat`。stdout 仅用于 MCP JSON-RPC；启动器诊断日志写入 XYML 自己的日志位置，错误信息写入 stderr。

Claude Desktop 配置示例（Windows 路径需要按实际 checkout 调整）：

```json
{
  "mcpServers": {
    "xyml": {
      "command": "E:/Stl/Proj/XYML/XYMLAIServer/build/install/XYMLAIServer/bin/XYMLAIServer.bat",
      "args": []
    }
  }
}
```

## 工具

所有结果同时提供 MCP `structuredContent` 和可读文本。`[L1]` 为只读诊断；`[L2]` 为低风险设置或模组操作；`[L3]` 为下载安装、实例创建或进程控制。高影响工具，以及删除模组，都要求参数 `confirmed: true`，否则服务返回 MCP 错误而不会调用 XYML。

只读工具：`list_instances`、`get_instance_settings`、`get_mods_directory`、`get_logs`、`analyze_crash`、`list_java_runtimes`、`list_local_mods`、`search_addons`、`get_addon_versions`、`get_addon_categories`、`get_remote_version_by_local_file`、`list_remote_game_versions`、`list_modloader_versions`。

设置工具：`set_java_version`、`set_memory`、`set_jvm_options`、`set_window_options`。

模组工具：`install_addon`、`install_local_addon`、`enable_mod`、`disable_mod`、`remove_mods`。

高影响工具：`install_game_version`、`install_modloader`、`create_instance`、`install_local_modpack`、`launch_game`、`stop_game`、`get_launch_status`。状态查询本身只读，但按启动测试策略同样要求 `confirmed: true`。

## Resources

- `xyml://instances/{instance_id}/logs/latest.log`：最新日志。
- `xyml://instances/{instance_id}/crash-reports/`：崩溃报告目录中的最新一份报告。

agent 需要修改模组内容时，使用 `get_mods_directory` 获取绝对路径后自行完成文件操作；MCP 不逆向 jar、不解析字节码，也不新增启动器原本没有的通用文件管理能力。

## 验证

`XYMLAIServer` 包含 5 个 JUnit Jupiter 测试，覆盖 29 个工具注册、资源模板、确认门禁、CrashReportAnalyzer 结构化输出和 stdio `initialize`/`tools/list` 握手。构建时使用仓库 Gradle Wrapper；Windows 若用户级 Gradle 锁不可写，可将 `GRADLE_USER_HOME` 指向仓库内缓存目录。

真实环境仍需项目负责人验证：使用目标 AI agent 完成 stdio `initialize`/`tools/list` 握手，确认账户、下载源和代理配置可用，并在隔离实例中确认安装、启动和停止行为。`install_modloader` 当前沿用 XYML 的 `GameBuilder` 构建流程，实际版本组合应由负责人在真实下载源上验收。

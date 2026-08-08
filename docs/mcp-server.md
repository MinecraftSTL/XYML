# XYML MCP Server

MCP 服务器默认关闭。启用后，XYML 在启动时绑定本机回环地址 `127.0.0.1`，默认端口为 `23968`，唯一端点为 `POST /mcp`。开关和监听端口位于启动器设置中的“MCP 服务器”页，修改后在下次启动 XYML 时生效。

协议传输、工具注册、崩溃分析适配和操作契约位于现有 `XYMLCore` 模块；依赖应用配置与已初始化游戏仓库的实现位于现有 `XYML` 模块。实现只复用已有的实例、设置、模组和启动服务，不提供通用文件操作接口。

## 协议范围

服务只实现以下 JSON-RPC 2.0 子集：

- `initialize`：完成握手，能力协商只声明 `tools`。
- `tools/list`：列出 XYML 工具。
- `tools/call`：调用一个 XYML 工具。

请求使用 JSON，响应使用 `text/event-stream`，每次响应包含一个 `data:` 事件。带 `id` 的请求返回 `result` 或 `error`；不带 `id` 的 notification 不返回 JSON-RPC 消息。服务不实现或声明 resources、prompts 及其他 MCP 方法。

## 连接

先在启动器设置中启用 MCP 服务器并重启 XYML。MCP 客户端使用 HTTP/SSE 地址连接：

```json
{
  "mcpServers": {
    "xyml": {
      "url": "http://127.0.0.1:23968/mcp"
    }
  }
}
```

不同客户端的 URL 字段名称可能不同，应以目标客户端的 MCP 配置格式为准。端口若在 XYML 设置中修改，配置 URL 也必须同步修改。

可使用以下请求检查握手：

```powershell
$body = '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}'
Invoke-WebRequest -Method Post -Uri http://127.0.0.1:23968/mcp `
    -ContentType application/json -Body $body
```

## 工具

所有结果同时提供 MCP `structuredContent` 和 JSON 文本。`[L1]` 为只读诊断；`[L2]` 为实例设置或模组操作；`[L3]` 为启动测试进程控制。启动、停止、启动状态查询和删除模组要求参数 `confirmed: true`，否则服务返回 MCP 错误而不会调用 XYML。工具执行调度到 `Schedulers.io()`，不会占用 UI 线程。

只读工具：`list_instances`、`get_instance_settings`、`get_mods_directory`、`get_logs`、`analyze_crash`、`list_java_runtimes`、`list_local_mods`。

设置工具：`set_java_version`、`set_memory`、`set_jvm_options`、`set_window_options`。

模组工具：`enable_mod`、`disable_mod`、`remove_mods`。

启动测试工具：`launch_game`、`stop_game`、`get_launch_status`。状态查询本身只读，但按启动测试策略同样要求 `confirmed: true`。

需要修改模组内容时，使用 `get_mods_directory` 获取绝对路径后自行完成文件操作；MCP 不逆向 jar、不解析字节码，也不新增启动器原本没有的通用文件管理能力。

## 验证

`XYMLCore` 中的 JUnit Jupiter 测试覆盖 17 个工具注册、确认门禁、CrashReportAnalyzer 结构化输出，以及 HTTP/SSE `initialize`、`tools/list`、`tools/call` 和 notification 行为。构建时使用仓库 Gradle Wrapper；Windows 若用户级 Gradle 锁不可写，可将 `GRADLE_USER_HOME` 指向仓库内缓存目录。

真实环境仍需项目负责人验证：使用目标 MCP 客户端完成 `initialize`、`tools/list` 握手，并在隔离实例中确认设置写入、模组启停/删除以及游戏启动和停止行为。

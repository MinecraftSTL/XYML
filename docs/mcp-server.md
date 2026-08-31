# XYML MCP Server

MCP 服务器默认关闭。启用后，XYML 在启动时绑定本机回环地址 `127.0.0.1`，默认端口为 `23968`，唯一端点为 `/mcp`，客户端消息使用 POST。开关和监听端口位于启动器设置中的“MCP 服务器”页，修改会立即保存，监听器将在重启后读取新配置；页面会提供“立即重启”按钮。

协议传输、工具注册、崩溃分析适配和操作契约位于现有 `XYMLCore` 模块；依赖应用配置与已初始化游戏仓库的实现位于现有 `XYML` 模块。实现只复用已有的实例、设置、模组和启动服务，不提供通用文件操作接口。

## 协议范围

服务通过单一 `/mcp` 端点实现 Streamable HTTP，客户端消息使用 POST，并实现以下 JSON-RPC 2.0 子集：

初始化版本协商支持 `2025-11-25`、`2025-06-18` 和 `2025-03-26`；未支持的客户端版本会回退到当前版本
`2025-11-25`，后续请求以响应中的版本头为准。

- `initialize`：完成握手，协商协议版本并签发 `Mcp-Session-Id`，声明 `tools`、`resources` 和 `prompts` 能力。
- `tools/list`：列出 XYML 工具。
- `tools/call`：调用一个 XYML 工具。
- `resources/list`：列出当前实例的日志和崩溃报告目录资源。
- `resources/templates/list`：列出可读取单个崩溃报告的 URI 模板。
- `resources/read`：读取日志、崩溃报告目录或单个崩溃报告。
- `prompts/list`：列出提示模板。
- `prompts/get`：展开提示模板。

POST 请求必须使用 `Content-Type: application/json`，`Accept` 必须同时声明 `application/json` 和
`text/event-stream`，服务端再协商响应格式。单条 JSON-RPC 响应默认返回 `application/json`；只有
客户端明确拒绝 JSON，或将来一次响应包含多条消息时，才返回包含 `event: message` 和 `data:` 的 SSE
事件。SSE 在这里仅作为 Streamable HTTP 的流式响应格式，不提供传统的 `/sse` 端点。

初始化响应会返回 `Mcp-Session-Id` 和 `MCP-Protocol-Version`，后续请求必须携带这两个请求头。带 `id`
的请求返回 `result` 或 `error`；不带 `id` 的 notification 返回 HTTP 202。服务端没有主动推送消息，
因此携带有效会话的 `GET /mcp` 返回 HTTP 405，并通过 `Allow: POST` 表明当前只支持 POST 消息交换。
客户端可以携带会话和协议版本请求 `DELETE /mcp` 终止会话；成功时返回 HTTP 200，之后该会话标识不再有效。
传输层错误始终返回 JSON。

## 连接

先在启动器设置中启用 MCP 服务器并重启 XYML。MCP 客户端选择 Streamable HTTP，并使用以下地址连接：

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

可使用以下请求检查握手并保存会话头：

```powershell
$headers = @{
    "Accept" = "application/json, text/event-stream"
    "Content-Type" = "application/json"
}
$body = '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}'
$initialize = Invoke-WebRequest -Method Post -Uri http://127.0.0.1:23968/mcp `
    -Headers $headers -Body $body
$headers["Mcp-Session-Id"] = $initialize.Headers["Mcp-Session-Id"]
$headers["MCP-Protocol-Version"] = $initialize.Headers["MCP-Protocol-Version"]
```

## 工具

所有结果同时提供 MCP `structuredContent` 和 JSON 文本。`[L1]` 为只读诊断；`[L2]` 为实例设置或模组操作；`[L3]` 为启动测试进程控制。启动、停止、启动状态查询和删除模组要求参数 `confirmed: true`，否则服务返回 MCP 错误而不会调用 XYML。工具执行调度到 `Schedulers.io()`，不会占用 UI 线程。

只读工具：`list_instances`、`get_instance_settings`、`get_mods_directory`、`analyze_crash`、`list_java_runtimes`、`list_local_mods`。

设置工具：`set_java_version`、`set_memory`、`set_jvm_options`、`set_window_options`。

模组工具：`enable_mod`、`disable_mod`、`remove_mods`。

启动测试工具：`launch_game`、`stop_game`、`get_launch_status`。状态查询本身只读，但按启动测试策略同样要求 `confirmed: true`。

需要修改模组内容时，使用 `get_mods_directory` 获取绝对路径后自行完成文件操作；MCP 不逆向 jar、不解析字节码，也不新增启动器原本没有的通用文件管理能力。

## 资源和提示

日志和崩溃报告是只读资源，不再作为 `get_logs` 工具返回。资源 URI 使用以下形式：

- `xyml://instances/{instance_id}/logs/latest.log`：最新游戏日志全文。
- `xyml://instances/{instance_id}/crash-reports/`：崩溃报告目录中的直接文件名列表。
- `xyml://instances/{instance_id}/crash-reports/{report_name}`：单个崩溃报告文本。

`diagnose_crash` 提示模板接收 `instance_id`，用于组织日志和崩溃报告诊断流程；实际诊断仍通过 `analyze_crash` 工具完成。

## 验证

`XYMLCore` 中的 JUnit Jupiter 测试覆盖 16 个工具注册、确认门禁、CrashReportAnalyzer 结构化输出，以及
Streamable HTTP 的初始化协商、会话校验、JSON/SSE 响应选择、工具、资源、提示和 notification 行为。
构建时使用仓库 Gradle Wrapper；Windows 若用户级 Gradle 锁不可写，可将 `GRADLE_USER_HOME` 指向仓库内缓存目录。

真实环境仍需项目负责人验证：使用目标 MCP 客户端完成 `initialize`、`tools/list` 握手，并在隔离实例中确认设置写入、模组启停/删除以及游戏启动和停止行为。

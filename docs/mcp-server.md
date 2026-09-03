# XYML MCP Server

MCP 服务器默认关闭。启用后，XYML 在启动时绑定本机回环地址 `127.0.0.1`，默认端口为 `23968`，唯一端点为 `/mcp`，客户端消息使用 POST。开关和监听端口位于启动器设置中的“MCP 服务器”页，修改会立即保存，监听器将在重启后读取新配置；页面会提供“立即重启”按钮。

通用 JSON-RPC、Streamable HTTP、会话和能力提供接口位于独立的 `XoyzMCP` 库；工具注册、崩溃分析适配和操作契约位于 `XYMLCore` 模块，依赖应用配置与已初始化游戏仓库的实现位于 `XYML` 模块。实现只复用已有的实例、设置、模组和启动服务，不提供通用文件操作接口。

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

初始化响应会返回 `Mcp-Session-Id` 和 `MCP-Protocol-Version`，后续请求必须携带这两个请求头。为兼容旧客户端，
缺少版本头时按 `2025-03-26` 处理，因此只有协商为该版本的会话能够省略它。带 `id` 的请求返回 `result` 或
`error`；不带 `id` 的 notification 返回 HTTP 202。服务端没有主动推送消息，
因此携带有效会话的 `GET /mcp` 返回 HTTP 405，并通过 `Allow: POST, DELETE` 表明当前只支持 POST 消息交换与 DELETE 会话终止。
客户端可以携带会话和协议版本请求 `DELETE /mcp` 终止会话；成功时返回 HTTP 200，之后该会话标识不再有效。
传输层错误始终返回 JSON。会话默认在连续一小时没有活动后过期，有效请求会刷新活动时间；服务器最多保留 256 个会话，
达到上限时淘汰最久未活动的会话。

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
$body = @'
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "protocolVersion": "2025-11-25",
    "capabilities": {},
    "clientInfo": {
      "name": "xyml-smoke-test",
      "version": "1.0.0"
    }
  }
}
'@
$initialize = Invoke-WebRequest -Method Post -Uri http://127.0.0.1:23968/mcp `
    -Headers $headers -Body $body
$headers["Mcp-Session-Id"] = $initialize.Headers["Mcp-Session-Id"]
$headers["MCP-Protocol-Version"] = $initialize.Headers["MCP-Protocol-Version"]
```

## 工具

服务器共注册 19 个工具。所有结果同时提供 MCP `structuredContent` 和 JSON 文本。`[L1]` 为只读诊断；`[L2]` 为实例管理、实例设置或模组操作；`[L3]` 为启动测试进程控制。工具执行调度到 `Schedulers.io()`，不会占用 UI 线程。

只读工具：`list_instances`、`get_instance_settings`、`get_mods_directory`、`analyze_crash`、`list_java_runtimes`、`list_local_mods`。

`analyze_crash` 会分别分析日志和已解析的崩溃报告，再按 `CrashReportAnalyzer.Rule` 去重；同一规则同时命中时保留报告证据，并在 `sources` 中列出全部来源。结果还包含 `crash_report_source`（`explicit`、`referenced`、`embedded` 或 `none`）以及非致命的 `warnings`。客户端传入的 `log_text` 不会触发其中路径的文件读取；需要指定报告时，应把当前实例 `crash-reports` 目录中的直接文件名传给 `crash_report_path`。

实例管理工具：`rename_instance`、`duplicate_instance`、`delete_instance`。`duplicate_instance` 默认不复制存档，可通过 `copy_saves` 控制。由 MCP 启动且仍在运行的实例必须先调用 `stop_game`，才能执行这三项生命周期操作。

设置工具：`set_java_version`、`set_memory`、`set_jvm_options`、`set_window_options`。四项工具均支持 `inherit: true`，用于删除对应的实例级覆盖并恢复继承设置。

模组工具：`enable_mod`、`disable_mod`、`remove_mods`。

启动测试工具：`launch_game`、`stop_game`、`get_launch_status`。前两项会直接执行，但工具描述会建议客户端在调用前征得用户确认。

所有工具 schema 均不包含 `confirmed` 参数，非删除类操作会直接执行。`delete_instance` 和 `remove_mods`
分别受启动器设置页中的“删除实例前要求启动器确认”和“删除模组前要求启动器确认”选项控制。两个选项均默认开启且即时生效；
开启时对应操作会弹出确认对话框，关闭时仅对应的删除操作直接执行。用户取消确认时不会删除目标。旧版的统一删除确认设置会在读取时迁移到这两个选项，
已经单独保存的新选项优先。确认对话框也允许用户关闭当前删除类别的后续确认；该选择仅在用户同时确认本次删除时生效，取消本次删除不会更改设置。

需要修改模组内容时，使用 `get_mods_directory` 获取绝对路径后自行完成文件操作；MCP 不逆向 jar、不解析字节码，也不新增启动器原本没有的通用文件管理能力。

## 资源和提示

日志和崩溃报告是只读资源，不再作为 `get_logs` 工具返回。资源 URI 使用以下形式：

- `xyml://instances/{instance_id}/logs/latest.log`：最新游戏日志全文。
- `xyml://instances/{instance_id}/crash-reports/`：崩溃报告目录中的直接文件名列表。
- `xyml://instances/{instance_id}/crash-reports/{report_name}`：单个崩溃报告文本。

`diagnose_crash` 提示模板接收 `instance_id`，要求先读取日志和崩溃报告目录资源，再将选中的直接报告文件名交给 `analyze_crash` 完成合并诊断。

## 验证

`XoyzMCP` 中的 JUnit Jupiter 测试覆盖 Streamable HTTP 的初始化协商、会话校验、JSON/SSE 响应选择、
工具、资源、提示和 notification 行为；`XYMLCore` 测试覆盖 19 个工具注册、参数 schema、业务接线和崩溃分析结构化输出。
构建时使用仓库 Gradle Wrapper；Windows 若用户级 Gradle 锁不可写，可将 `GRADLE_USER_HOME` 指向仓库内缓存目录。

真实环境仍需项目负责人验证：使用目标 MCP 客户端完成 `initialize`、`tools/list` 握手，并在隔离实例中确认设置写入、模组启停/删除以及游戏启动和停止行为。

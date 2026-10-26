// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

/// XoyzMCP provides MCP JSON-RPC handling and loopback Streamable HTTP transport.
module space.minecraftstl.xyml.library.mcp {
    requires com.google.gson;
    requires nanohttpd;
    requires static org.jetbrains.annotations;

    exports space.minecraftstl.xyml.library.mcp;
}

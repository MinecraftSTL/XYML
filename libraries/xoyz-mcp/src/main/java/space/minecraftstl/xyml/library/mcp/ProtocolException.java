// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.library.mcp;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Internal JSON-RPC error carrying an explicit protocol code.
@NotNullByDefault
final class ProtocolException extends RuntimeException {
    /// JSON-RPC error code.
    private final int code;

    /// Creates one protocol error.
    ///
    /// @param code JSON-RPC error code
    /// @param message stable error description
    ProtocolException(int code, String message) {
        super(Objects.requireNonNull(message, "message"));
        this.code = code;
    }

    /// Returns the JSON-RPC error code.
    ///
    /// @return error code
    int code() {
        return code;
    }
}

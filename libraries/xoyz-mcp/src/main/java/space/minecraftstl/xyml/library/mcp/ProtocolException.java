/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
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

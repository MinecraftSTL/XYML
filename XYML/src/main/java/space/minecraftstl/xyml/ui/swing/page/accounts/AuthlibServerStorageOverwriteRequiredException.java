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
package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Signals that mutating one authlib-injector server requires explicit backup-and-overwrite consent.
@NotNullByDefault
public final class AuthlibServerStorageOverwriteRequiredException extends IllegalStateException {
    /// Stable configured URL associated with the rejected mutation.
    private final String serverUrl;

    /// Creates a read-only storage signal for one exact configured server URL.
    ///
    /// @param serverUrl stable configured URL
    /// @param message localized explanation of the incompatible storage version
    AuthlibServerStorageOverwriteRequiredException(String serverUrl, String message) {
        super(Objects.requireNonNull(message, "message"));
        this.serverUrl = Objects.requireNonNull(serverUrl, "serverUrl");
    }

    /// Returns the configured URL associated with the rejected mutation.
    ///
    /// @return stable configured server URL
    public String serverUrl() {
        return serverUrl;
    }
}

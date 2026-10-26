// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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

// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Plain presentation-safe description of one configured authlib-injector server.
///
/// @param url normalized server URL used as the stable workflow identifier
/// @param displayName localized or server-provided display name
/// @param displayAddress compact host, port, and path suitable for presentation
/// @param emailUsernameRequired whether the server requires an email-shaped username
@NotNullByDefault
public record AuthlibServerOption(
        String url,
        String displayName,
        String displayAddress,
        boolean emailUsernameRequired) {
    /// Validates one immutable server option.
    public AuthlibServerOption {
        url = Objects.requireNonNull(url, "url");
        displayName = Objects.requireNonNull(displayName, "displayName");
        displayAddress = Objects.requireNonNull(displayAddress, "displayAddress");
        if (url.isBlank()) {
            throw new IllegalArgumentException("Authlib-injector server URL cannot be blank");
        }
        if (displayAddress.isBlank()) {
            throw new IllegalArgumentException("Authlib-injector server display address cannot be blank");
        }
    }
}

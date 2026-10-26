// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Credential-free progress notice emitted by an account creation gateway.
///
/// @param kind notice kind
/// @param location browser or verification URI when applicable
/// @param code device code when applicable
/// @param detail non-sensitive factory progress text when applicable
@NotNullByDefault
public record AccountCreationNotice(
        Kind kind,
        @Nullable String location,
        @Nullable String code,
        @Nullable String detail) {
    /// Notice kinds understood by the native Swing dialog.
    @NotNullByDefault
    public enum Kind {
        /// Authentication work has started on the caller-owned executor.
        AUTHENTICATING,

        /// Microsoft authorization requires opening a browser URL.
        BROWSER_AUTHORIZATION,

        /// Microsoft device authorization requires a URI and short code.
        DEVICE_AUTHORIZATION,

        /// Microsoft device authorization completed and profile loading continues.
        AUTHORIZATION_COMPLETED,

        /// Account files are being backed up or updated.
        WRITING_STORAGE
    }

    /// Validates notice payloads and rejects credentials in arbitrary fields by construction.
    public AccountCreationNotice {
        kind = Objects.requireNonNull(kind, "kind");
        switch (kind) {
            case AUTHENTICATING, AUTHORIZATION_COMPLETED, WRITING_STORAGE -> {
                if (location != null || code != null) {
                    throw new IllegalArgumentException("This notice kind cannot contain authorization data");
                }
            }
            case BROWSER_AUTHORIZATION -> {
                requireText(location, "Browser authorization location");
                if (code != null) {
                    throw new IllegalArgumentException("Browser authorization cannot contain a device code");
                }
            }
            case DEVICE_AUTHORIZATION -> {
                requireText(location, "Device authorization location");
                requireText(code, "Device authorization code");
            }
        }
    }

    /// Creates an authentication-start notice.
    ///
    /// @return authentication-start notice
    public static AccountCreationNotice authenticating() {
        return new AccountCreationNotice(Kind.AUTHENTICATING, null, null, null);
    }

    /// Creates a browser authorization notice.
    ///
    /// @param location authorization URL
    /// @return browser authorization notice
    public static AccountCreationNotice browserAuthorization(String location) {
        return new AccountCreationNotice(Kind.BROWSER_AUTHORIZATION, location, null, null);
    }

    /// Creates a device authorization notice.
    ///
    /// @param location verification URI
    /// @param code short user code
    /// @return device authorization notice
    public static AccountCreationNotice deviceAuthorization(String location, String code) {
        return new AccountCreationNotice(Kind.DEVICE_AUTHORIZATION, location, code, null);
    }

    /// Creates an authorization-completed notice.
    ///
    /// @return authorization-completed notice
    public static AccountCreationNotice authorizationCompleted() {
        return new AccountCreationNotice(Kind.AUTHORIZATION_COMPLETED, null, null, null);
    }

    /// Creates a storage-write notice.
    ///
    /// @return storage-write notice
    public static AccountCreationNotice writingStorage() {
        return new AccountCreationNotice(Kind.WRITING_STORAGE, null, null, null);
    }

    /// Requires one notice field to contain visible text.
    ///
    /// @param value field value
    /// @param label diagnostic label
    private static void requireText(@Nullable String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " cannot be blank");
        }
    }
}

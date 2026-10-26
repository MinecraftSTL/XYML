// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Credential-free OAuth or persistence progress for account reauthentication.
///
/// @param kind notice kind
/// @param location browser or device verification URI when applicable
/// @param code device code when applicable
@NotNullByDefault
public record AccountReauthenticationNotice(
        Kind kind,
        @Nullable String location,
        @Nullable String code) {
    /// Reauthentication notice kinds.
    @NotNullByDefault
    public enum Kind {
        /// Network authentication has started.
        AUTHENTICATING,

        /// A system-browser authorization location is available.
        BROWSER_AUTHORIZATION,

        /// A device verification URI and code are available.
        DEVICE_AUTHORIZATION,

        /// Device authorization finished and profile loading continues.
        AUTHORIZATION_COMPLETED,

        /// Changed private account data is being persisted.
        PERSISTING
    }

    /// Validates one notice payload.
    public AccountReauthenticationNotice {
        kind = Objects.requireNonNull(kind, "kind");
        switch (kind) {
            case AUTHENTICATING, AUTHORIZATION_COMPLETED, PERSISTING -> {
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
    public static AccountReauthenticationNotice authenticating() {
        return new AccountReauthenticationNotice(Kind.AUTHENTICATING, null, null);
    }

    /// Creates a browser-authorization notice.
    ///
    /// @param location authorization location
    /// @return browser notice
    public static AccountReauthenticationNotice browserAuthorization(String location) {
        return new AccountReauthenticationNotice(Kind.BROWSER_AUTHORIZATION, location, null);
    }

    /// Creates a device-authorization notice.
    ///
    /// @param location verification URI
    /// @param code device code
    /// @return device notice
    public static AccountReauthenticationNotice deviceAuthorization(String location, String code) {
        return new AccountReauthenticationNotice(Kind.DEVICE_AUTHORIZATION, location, code);
    }

    /// Creates an authorization-completed notice.
    ///
    /// @return completion notice
    public static AccountReauthenticationNotice authorizationCompleted() {
        return new AccountReauthenticationNotice(Kind.AUTHORIZATION_COMPLETED, null, null);
    }

    /// Creates a persistence notice.
    ///
    /// @return persistence notice
    public static AccountReauthenticationNotice persisting() {
        return new AccountReauthenticationNotice(Kind.PERSISTING, null, null);
    }

    /// Requires visible text in one authorization field.
    ///
    /// @param value field value
    /// @param label diagnostic label
    private static void requireText(@Nullable String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " cannot be blank");
        }
    }
}

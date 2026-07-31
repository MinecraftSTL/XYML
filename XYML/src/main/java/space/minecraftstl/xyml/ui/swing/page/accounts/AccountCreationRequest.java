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
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/// Immutable input for one add-account operation.
///
/// Only fields belonging to [#method()] may be non-null. Password text exists only for the
/// lifetime of the owning dialog and operation and must never be logged or published as progress.
///
/// @param method authentication method
/// @param username offline or authlib-injector username, otherwise null
/// @param password authlib-injector password, otherwise null
/// @param offlineUuid explicit offline UUID, or null to derive it from the username
/// @param authlibServerUrl configured authlib-injector server URL, otherwise null
/// @param microsoftLoginMode Microsoft grant mode, otherwise null
/// @param portable whether the account belongs in workspace-local account files
@NotNullByDefault
public record AccountCreationRequest(
        AccountCreationMethod method,
        @Nullable String username,
        @Nullable String password,
        @Nullable UUID offlineUuid,
        @Nullable String authlibServerUrl,
        @Nullable MicrosoftAccountLoginMode microsoftLoginMode,
        boolean portable) {
    /// Validates method-specific request invariants.
    public AccountCreationRequest {
        method = Objects.requireNonNull(method, "method");
        switch (method) {
            case OFFLINE -> {
                requireText(username, "Offline username");
                requireNull(password, "Offline password");
                requireNull(authlibServerUrl, "Offline authlib-injector server");
                requireNull(microsoftLoginMode, "Offline Microsoft login mode");
            }
            case MICROSOFT -> {
                requireNull(username, "Microsoft username");
                requireNull(password, "Microsoft password");
                requireNull(offlineUuid, "Microsoft offline UUID");
                requireNull(authlibServerUrl, "Microsoft authlib-injector server");
                Objects.requireNonNull(microsoftLoginMode, "microsoftLoginMode");
            }
            case AUTHLIB_INJECTOR -> {
                requireText(username, "Authlib-injector username");
                requireText(password, "Authlib-injector password");
                requireText(authlibServerUrl, "Authlib-injector server URL");
                requireNull(offlineUuid, "Authlib-injector offline UUID");
                requireNull(microsoftLoginMode, "Authlib-injector Microsoft login mode");
            }
        }
    }

    /// Creates a validated offline request.
    ///
    /// @param username offline profile name
    /// @param offlineUuid explicit UUID, or null to derive one
    /// @param portable whether to store the account in workspace-local files
    /// @return immutable request
    public static AccountCreationRequest offline(
            String username,
            @Nullable UUID offlineUuid,
            boolean portable) {
        return new AccountCreationRequest(
                AccountCreationMethod.OFFLINE,
                username,
                null,
                offlineUuid,
                null,
                null,
                portable);
    }

    /// Creates a validated Microsoft request.
    ///
    /// @param loginMode OAuth grant mode
    /// @param portable whether to store the account in workspace-local files
    /// @return immutable request
    public static AccountCreationRequest microsoft(
            MicrosoftAccountLoginMode loginMode,
            boolean portable) {
        return new AccountCreationRequest(
                AccountCreationMethod.MICROSOFT,
                null,
                null,
                null,
                null,
                loginMode,
                portable);
    }

    /// Creates a validated authlib-injector request.
    ///
    /// @param serverUrl configured server URL
    /// @param username authentication username
    /// @param password authentication password
    /// @param portable whether to store the account in workspace-local files
    /// @return immutable request
    public static AccountCreationRequest authlibInjector(
            String serverUrl,
            String username,
            String password,
            boolean portable) {
        return new AccountCreationRequest(
                AccountCreationMethod.AUTHLIB_INJECTOR,
                username,
                password,
                null,
                serverUrl,
                null,
                portable);
    }

    /// Requires a non-null, non-blank method-specific text field.
    ///
    /// @param value field value
    /// @param label diagnostic label
    private static void requireText(@Nullable String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " cannot be blank");
        }
    }

    /// Requires an irrelevant method-specific field to remain null.
    ///
    /// @param value field value
    /// @param label diagnostic label
    private static void requireNull(@Nullable Object value, String label) {
        if (value != null) {
            throw new IllegalArgumentException(label + " must be null");
        }
    }
}

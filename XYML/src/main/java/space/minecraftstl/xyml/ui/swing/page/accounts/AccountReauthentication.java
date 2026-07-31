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
import space.minecraftstl.xyml.auth.AuthInfo;

import java.util.concurrent.CompletionStage;

/// Asynchronously reauthenticates one persisted account after credential expiry.
@NotNullByDefault
public interface AccountReauthentication extends AutoCloseable {
    /// Starts reauthentication by stable account ID and returns without blocking the caller.
    ///
    /// The returned stage may be cancelled. Implementations allow only one active operation.
    ///
    /// @param accountId stable persisted account identifier
    /// @return cancellable authentication completion
    CompletionStage<AuthInfo> reauthenticate(String accountId);

    /// Cancels active authentication and releases every owned prompt and OAuth subscription.
    @Override
    void close();
}

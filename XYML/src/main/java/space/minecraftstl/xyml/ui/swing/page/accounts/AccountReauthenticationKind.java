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

/// Credential-expiry recovery mechanisms understood by the native workflow.
@NotNullByDefault
public enum AccountReauthenticationKind {
    /// Requests a password for an existing classic account.
    CLASSIC_PASSWORD,

    /// Runs the account's interactive OAuth credential-expiry method.
    OAUTH_DEVICE_CODE,

    /// Repeats the account's ordinary login without an additional credential prompt.
    DIRECT
}

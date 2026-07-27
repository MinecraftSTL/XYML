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

import java.nio.file.Path;
import java.util.concurrent.CompletionStage;

/// Starts one explicit online-account skin upload after local validation has succeeded.
@FunctionalInterface
@NotNullByDefault
interface AccountSkinUploadCommand {
    /// Uploads the selected validated PNG through the currently selected account.
    ///
    /// @param skinFile normalized local PNG path
    /// @param slim whether the decoded skin uses slim arms
    /// @return completion after reauthentication, provider upload, and final refresh
    CompletionStage<Void> upload(Path skinFile, boolean slim);
}

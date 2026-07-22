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

/// Localizable visible and accessible text for the account-selection page.
///
/// @param pageTitle page heading
/// @param addAction add-account command label
/// @param emptyText text shown when no account exists
@NotNullByDefault
public record AccountsStrings(String pageTitle, String addAction, String emptyText) {
    /// Validates localized account-page text.
    public AccountsStrings {
        Objects.requireNonNull(pageTitle, "pageTitle");
        Objects.requireNonNull(addAction, "addAction");
        Objects.requireNonNull(emptyText, "emptyText");
    }
}

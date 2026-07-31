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
package space.minecraftstl.xyml.ui.swing.page.settings.theme;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.theme.ThemeReference;

import java.util.List;
import java.util.Objects;

/// Immutable visible state for local theme-pack management.
///
/// @param items current search result in deterministic presentation order
/// @param totalItemCount complete loaded inventory size before search filtering
/// @param query current normalized user query
/// @param status inventory lifecycle status
/// @param operation exclusive active mutation
/// @param appliedTheme exact currently applied theme, or `null` when unavailable
/// @param failureMessage latest factual failure, or `null`
/// @param contentRevision monotonic revision of the viewport data source
@NotNullByDefault
public record ThemePackManagementSnapshot(
        @Unmodifiable List<ThemePackItem> items,
        int totalItemCount,
        String query,
        ThemePackManagementStatus status,
        ThemePackManagementOperation operation,
        @Nullable ThemeReference appliedTheme,
        @Nullable String failureMessage,
        long contentRevision) {
    /// Defensively copies items and validates counts and lifecycle values.
    public ThemePackManagementSnapshot {
        items = List.copyOf(items);
        if (totalItemCount < items.size() || totalItemCount < 0) {
            throw new IllegalArgumentException("totalItemCount must contain the filtered items");
        }
        query = Objects.requireNonNull(query, "query");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(operation, "operation");
        failureMessage = failureMessage == null || failureMessage.isBlank() ? null : failureMessage.trim();
        if (contentRevision < 0L) {
            throw new IllegalArgumentException("contentRevision must not be negative");
        }
        if (status == ThemePackManagementStatus.CLOSED && operation != ThemePackManagementOperation.NONE) {
            throw new IllegalArgumentException("A closed inventory cannot retain an active operation");
        }
    }

    /// Returns whether refresh, import, selection, and mutation controls must remain disabled.
    ///
    /// @return whether an inventory request or mutation is active
    public boolean busy() {
        return status == ThemePackManagementStatus.LOADING
                || operation != ThemePackManagementOperation.NONE;
    }
}

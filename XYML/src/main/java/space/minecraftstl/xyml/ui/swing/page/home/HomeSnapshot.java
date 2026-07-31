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
package space.minecraftstl.xyml.ui.swing.page.home;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Immutable launcher-home state independent of a desktop toolkit.
///
/// Empty account or instance text represents a missing selection without a nullable UI contract.
///
/// @param accountName selected account display name, or empty when no account is selected
/// @param accountDetail short account provider or state text
/// @param instanceName selected instance display name, or empty when no instance is selected
/// @param instanceDetail short selected-instance version or loader text
/// @param statusText current launch readiness or operation status
/// @param launchEnabled whether the current selections can be launched
/// @param launching whether a launch command is currently active
/// @param selectionCommandsEnabled whether selection commands are available
@NotNullByDefault
public record HomeSnapshot(
        String accountName,
        String accountDetail,
        String instanceName,
        String instanceDetail,
        String statusText,
        boolean launchEnabled,
        boolean launching,
        boolean selectionCommandsEnabled) {
    /// Validates one home snapshot.
    public HomeSnapshot {
        Objects.requireNonNull(accountName, "accountName");
        Objects.requireNonNull(accountDetail, "accountDetail");
        Objects.requireNonNull(instanceName, "instanceName");
        Objects.requireNonNull(instanceDetail, "instanceDetail");
        Objects.requireNonNull(statusText, "statusText");
        if (launching && launchEnabled) {
            throw new IllegalArgumentException("launching state cannot accept another launch command");
        }
    }
}

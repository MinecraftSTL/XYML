// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.home;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Localizable launch readiness text for [LauncherHomeModel].
///
/// @param readyStatus status when both selections exist
/// @param missingAccountStatus status when no account is selected
/// @param missingInstanceStatus status when no instance is selected
/// @param exportingLaunchScriptStatus status while one script export owns the current selection
@NotNullByDefault
public record HomeStatusStrings(
        String readyStatus,
        String missingAccountStatus,
        String missingInstanceStatus,
        String exportingLaunchScriptStatus) {
    /// Validates localized status text.
    public HomeStatusStrings {
        Objects.requireNonNull(readyStatus, "readyStatus");
        Objects.requireNonNull(missingAccountStatus, "missingAccountStatus");
        Objects.requireNonNull(missingInstanceStatus, "missingInstanceStatus");
        Objects.requireNonNull(exportingLaunchScriptStatus, "exportingLaunchScriptStatus");
    }
}

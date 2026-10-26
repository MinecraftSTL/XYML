// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Localizable outer-shell text for one schematic instance-management view.
///
/// @param returnAction command label returning to the instance list
/// @param returnTooltip accessible description of the return command
/// @param loadingText schematic-root resolution text
/// @param failureTitle schematic-root resolution failure heading
/// @param retryAction failed-resolution retry command label
@NotNullByDefault
public record SchematicInstanceManagementStrings(
        String returnAction,
        String returnTooltip,
        String loadingText,
        String failureTitle,
        String retryAction) {
    /// Validates every injected localized value.
    public SchematicInstanceManagementStrings {
        Objects.requireNonNull(returnAction, "returnAction");
        Objects.requireNonNull(returnTooltip, "returnTooltip");
        Objects.requireNonNull(loadingText, "loadingText");
        Objects.requireNonNull(failureTitle, "failureTitle");
        Objects.requireNonNull(retryAction, "retryAction");
    }
}

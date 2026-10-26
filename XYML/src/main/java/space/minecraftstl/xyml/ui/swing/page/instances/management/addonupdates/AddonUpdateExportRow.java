// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management.addonupdates;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Immutable values written for one actionable add-on update in an exported CSV list.
///
/// @param fileName local add-on file name
/// @param currentVersion currently installed version
/// @param targetVersion compatible target version
/// @param source remote catalog display name
@NotNullByDefault
record AddonUpdateExportRow(
        String fileName,
        String currentVersion,
        String targetVersion,
        String source) {
    /// Validates every exported cell before background file I/O begins.
    AddonUpdateExportRow {
        fileName = Objects.requireNonNull(fileName, "fileName");
        currentVersion = Objects.requireNonNull(currentVersion, "currentVersion");
        targetVersion = Objects.requireNonNull(targetVersion, "targetVersion");
        source = Objects.requireNonNull(source, "source");
    }
}

// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management.installers;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.ui.swing.page.downloads.loaders.GameLoaderKind;

import java.util.Objects;

/// Immutable description of one recognized loader or loader-adjacent component installed by an instance.
///
/// The structure status lets the Swing installer surface distinguish an explicit XYML patch from a loader
/// discovered in third-party metadata before offering destructive actions.
///
/// @param kind recognized loader catalog kind
/// @param version detected installed version text
/// @param status certainty of the detected library mapping
@NotNullByDefault
public record InstanceInstallerEntry(
        GameLoaderKind kind,
        String version,
        InstallerStructureStatus status) {
    /// Validates one immutable recognized installer entry.
    public InstanceInstallerEntry {
        kind = Objects.requireNonNull(kind, "kind");
        version = Objects.requireNonNull(version, "version");
        status = Objects.requireNonNull(status, "status");
        if (version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
    }
}

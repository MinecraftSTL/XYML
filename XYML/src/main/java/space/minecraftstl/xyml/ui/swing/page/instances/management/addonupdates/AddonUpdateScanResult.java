// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management.addonupdates;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Objects;

/// Immutable outcome of one explicit installed-add-on update scan.
///
/// @param installedCount number of locally discovered Mods and resource packs
/// @param updates compatible newer artifacts found by the scan
/// @param failures add-ons for which every usable remote lookup failed
@NotNullByDefault
public record AddonUpdateScanResult(
        int installedCount,
        @Unmodifiable List<AddonUpdateItem> updates,
        @Unmodifiable List<AddonUpdateCheckFailure> failures) {
    /// Defensively copies result lists and rejects an impossible installed count.
    public AddonUpdateScanResult {
        if (installedCount < 0) {
            throw new IllegalArgumentException("installedCount must not be negative");
        }
        updates = List.copyOf(Objects.requireNonNull(updates, "updates"));
        failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
    }
}

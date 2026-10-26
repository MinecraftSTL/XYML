// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management.addonupdates;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;

/// Blocking boundary for one explicitly requested add-on update scan.
///
/// Implementations must not be called from the EDT because both installed-file discovery and
/// remote add-on metadata lookup can block.
@NotNullByDefault
interface AddonUpdateScanAccess {
    /// Discovers installed add-ons and checks their available compatible remote updates.
    ///
    /// @return immutable scan outcome
    /// @throws IOException when the local instance cannot be read as a whole
    AddonUpdateScanResult scan() throws IOException;
}

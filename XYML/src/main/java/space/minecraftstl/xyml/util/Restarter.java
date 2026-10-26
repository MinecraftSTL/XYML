// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.util;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.Metadata;
import space.minecraftstl.xyml.upgrade.UpdateApplier;

import java.io.IOException;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Restarts the current launcher through the toolkit-neutral update process launcher.
///
/// @author Glavo
@NotNullByDefault
public final class Restarter {
    /// Restart the current application.
    public static void restartSelf() throws IOException {
        LOG.info("Restarting XYML");
        if (Metadata.PACKAGED) {
            UpdateApplier.startPackagedApplication();
        } else {
            UpdateApplier.startJava(UpdateApplier.currentApplicationLocation());
        }
    }

    /// Prevents construction of the restart utility.
    private Restarter() {
    }
}

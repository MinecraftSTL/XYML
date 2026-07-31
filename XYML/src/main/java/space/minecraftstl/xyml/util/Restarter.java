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

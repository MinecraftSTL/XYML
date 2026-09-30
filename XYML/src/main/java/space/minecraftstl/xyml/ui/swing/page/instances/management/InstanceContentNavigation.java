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
package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.ui.swing.page.downloads.DownloadPageTarget;

import java.util.Objects;

/// Supplies cross-page content navigation from an instance-management action.
@FunctionalInterface
@NotNullByDefault
public interface InstanceContentNavigation {
    /// Opens the requested category in the download center.
    ///
    /// @param target requested download category
    void openDownloads(DownloadPageTarget target);

    /// Opens one download category and prefills it from an explicit instance context.
    ///
    /// Implementations that model no instance context retain the plain category transition.
    ///
    /// @param target requested download category
    /// @param instanceId instance whose analyzed context should prefill the category
    default void openInstanceDownloads(DownloadPageTarget target, GameInstanceID instanceId) {
        Objects.requireNonNull(instanceId, "instanceId");
        openDownloads(Objects.requireNonNull(target, "target"));
    }

    /// Returns a disabled navigation boundary for headless construction.
    ///
    /// @return navigation accepting requests without performing a shell transition
    static InstanceContentNavigation disabled() {
        return target -> { };
    }
}

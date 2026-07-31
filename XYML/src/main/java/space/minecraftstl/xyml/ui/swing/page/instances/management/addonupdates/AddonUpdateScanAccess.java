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

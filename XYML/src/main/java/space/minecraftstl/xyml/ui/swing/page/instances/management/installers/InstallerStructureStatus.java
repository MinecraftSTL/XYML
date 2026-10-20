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
package space.minecraftstl.xyml.ui.swing.page.instances.management.installers;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.game.GameComponentAnalyzer;

import java.util.Objects;

/// Structure certainty of one detected installer or third-party library row.
///
/// The value mirrors Core's explicit-patch versus discovered-library distinction: an explicit patch is
/// [CLEAR] because its structure can be mutated safely, while a library discovered in resolved metadata
/// is [JUST_EXISTED]. [UNSURE] is reserved for rows whose structure could not be attributed to either source.
@NotNullByDefault
public enum InstallerStructureStatus {
    /// The row comes from an explicit version patch and can be mutated safely.
    CLEAR,

    /// The row was discovered in resolved metadata and may have been installed by another launcher.
    JUST_EXISTED,

    /// The row structure could not be attributed to an explicit patch or a discovered library.
    UNSURE;

    /// Maps one Core component mark to the presentation status.
    ///
    /// @param mark Core component mark
    /// @return [CLEAR] for an explicit patch, otherwise [JUST_EXISTED]
    public static InstallerStructureStatus fromMark(GameComponentAnalyzer.Mark mark) {
        return Objects.requireNonNull(mark, "mark").clear() ? CLEAR : JUST_EXISTED;
    }
}

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
package space.minecraftstl.xyml.ui.swing.page.mods;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/// Reports one Mod source whose current on-disk conflict still needs a user decision.
@NotNullByDefault
final class ModImportConflictException extends IOException {
    /// Conflicting normalized source path.
    private final Path source;

    /// Creates one immutable conflict failure.
    ///
    /// @param source conflicting import source
    ModImportConflictException(Path source) {
        super("A Mod with the same local name already exists: "
                + Objects.requireNonNull(source, "source").getFileName());
        this.source = source.toAbsolutePath().normalize();
    }

    /// Returns the source requiring a conflict decision.
    ///
    /// @return normalized source path
    Path source() {
        return source;
    }
}

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
package space.minecraftstl.xyml.ui.swing.page.schematics;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.util.io.FileUtils;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Checks one direct-child name on the existing I/O scheduler, never probing paths on the EDT.
@NotNullByDefault
final class SchematicDirectoryNameValidation {
    /// Prevents construction of this stateless validator.
    private SchematicDirectoryNameValidation() {
    }

    /// Returns null for a valid absent child, otherwise a fixed localized diagnostic.
    static CompletionStage<@Nullable String> validate(Path directory, String name) {
        return CompletableFuture.supplyAsync(() -> {
            if (name.isBlank()) return i18n("schematics.create_directory.failed.empty_name");
            if (name.length() > 255 || !name.equals(name.strip()) || !FileUtils.isNameValid(name)
                    || name.equals(".") || name.equals("..") || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
                return i18n("schematics.create_directory.failed.invalid_name");
            }
            Path child = directory.resolve(name).normalize();
            if (!directory.equals(child.getParent())) return i18n("schematics.create_directory.failed.invalid_name");
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                return i18n("schematics.create_directory.failed");
            }
            return Files.exists(child, LinkOption.NOFOLLOW_LINKS)
                    ? i18n("schematics.create_directory.failed.already_exists") : null;
        }, Schedulers.io());
    }
}

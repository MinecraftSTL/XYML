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
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;
import space.minecraftstl.xyml.util.io.DeletionMode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Headless shallow scanning and captured-identity deletion using the schematic browser's storage boundary.
@NotNullByDefault
public final class SchematicDeletionAccess {
    /// Managed schematic root.
    private final Path root;
    /// Existing no-follow mutation implementation.
    private final FileSystemSchematicMutationIo mutations;
    /// Entries captured before the deletion confirmation opens.
    private final Map<Path, DefaultSchematicBrowserModel.DiscoveredEntry> captured = new HashMap<>();

    /// Creates one operation-scoped storage boundary.
    /// @param root managed schematic root
    public SchematicDeletionAccess(Path root) {
        this.root = root.toAbsolutePath().normalize();
        mutations = new FileSystemSchematicMutationIo(this.root);
    }

    /// Lists the immediate supported children without following directory links.
    /// @param directory managed directory to list
    /// @return immutable paths of folders and supported schematic files
    /// @throws IOException when the directory is unsafe or unreadable
    public @Unmodifiable List<Path> paths(Path directory) throws IOException {
        List<DefaultSchematicBrowserModel.DiscoveredEntry> entries = DefaultSchematicBrowserModel.scanDirectory(
                root, directory, new LoadCancellation());
        entries.forEach(entry -> captured.put(entry.path(), entry));
        return entries.stream().map(DefaultSchematicBrowserModel.DiscoveredEntry::path).toList();
    }

    /// Captures a current supported child and its no-follow identity before confirmation.
    /// @param path target under the managed root
    /// @throws IOException when the target is absent or unsupported
    public void capture(Path path) throws IOException {
        if (!paths(Objects.requireNonNull(path.getParent(), "parent")).contains(path)) {
            throw new IOException("Unknown schematic entry: " + path);
        }
    }

    /// Deletes a captured entry, retaining the browser's isolation and recursive no-follow protections.
    /// @param path captured target
    /// @param mode deletion behavior
    /// @throws IOException when identity changed or the operation fails
    public void delete(Path path, DeletionMode mode) throws IOException {
        DefaultSchematicBrowserModel.DiscoveredEntry entry = Objects.requireNonNull(captured.get(path), "entry");
        mutations.delete(Objects.requireNonNull(path.getParent(), "parent"), entry, mode, new LoadCancellation());
    }
}

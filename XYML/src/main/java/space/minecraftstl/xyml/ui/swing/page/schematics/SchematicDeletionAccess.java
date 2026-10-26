// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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

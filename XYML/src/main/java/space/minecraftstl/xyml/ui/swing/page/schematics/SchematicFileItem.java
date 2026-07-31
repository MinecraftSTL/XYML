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
import space.minecraftstl.xyml.schematic.LitematicFile;

import java.nio.file.Path;
import java.util.Objects;

/// Immutable Litematic row whose metadata is either parsed or explicitly unreadable.
///
/// The contained core metadata exposes preview pixels through defensive copies and therefore does
/// not leak Swing, JavaFX, AWT image, or mutable preview state into this browser boundary.
@NotNullByDefault
public final class SchematicFileItem implements SchematicBrowserItem {
    /// Exact source file path.
    private final Path path;

    /// Stable source file name.
    private final String fileName;

    /// Parsed toolkit-neutral core metadata, or null when parsing failed.
    private final @Nullable LitematicFile metadata;

    /// Parse failure text, or null when metadata is available.
    private final @Nullable String failureMessage;

    /// Creates one parsed or unreadable file row.
    ///
    /// Exactly one of metadata and failureMessage must be present.
    ///
    /// @param path exact source path
    /// @param fileName stable source file name
    /// @param metadata parsed metadata, or null on failure
    /// @param failureMessage parse failure text, or null on success
    public SchematicFileItem(
            Path path,
            String fileName,
            @Nullable LitematicFile metadata,
            @Nullable String failureMessage) {
        this.path = Objects.requireNonNull(path, "path");
        this.fileName = Objects.requireNonNull(fileName, "fileName");
        if ((metadata == null) == (failureMessage == null)) {
            throw new IllegalArgumentException("Exactly one of metadata and failureMessage must be present");
        }
        this.metadata = metadata;
        this.failureMessage = failureMessage;
    }

    /// Returns the exact source file path.
    ///
    /// @return source file path
    @Override
    public Path path() {
        return path;
    }

    /// Returns the stable source file name.
    ///
    /// @return source file name
    @Override
    public String fileName() {
        return fileName;
    }

    /// Returns parsed core metadata or null when the source file was unreadable.
    ///
    /// @return parsed metadata, or null
    public @Nullable LitematicFile metadata() {
        return metadata;
    }

    /// Returns the parse failure text or null for a readable file.
    ///
    /// @return failure text, or null
    public @Nullable String failureMessage() {
        return failureMessage;
    }

    /// Returns whether metadata and preview access are available for this row.
    ///
    /// @return whether parsing succeeded
    public boolean readable() {
        return metadata != null;
    }
}

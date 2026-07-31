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

import java.nio.file.Path;
import java.util.Objects;
import java.util.OptionalInt;

/// Immutable presentation-neutral state for one schematic browser directory.
///
/// @param rootDirectory immutable navigation boundary
/// @param currentDirectory directory represented by the last successful scan
/// @param itemCount exact indexed row count, or empty before the first successful scan
/// @param contentRevision revision incremented by each successfully committed scan
/// @param status current directory lifecycle
/// @param failureMessage latest scan failure text, or null outside the error state
/// @param canReturnToParent whether parent navigation remains inside the root boundary
/// @param writeStatus current independent file-system write lifecycle
/// @param writeFailureMessage latest write failure text, or null outside the write error state
@NotNullByDefault
public record SchematicBrowserSnapshot(
        Path rootDirectory,
        Path currentDirectory,
        OptionalInt itemCount,
        long contentRevision,
        SchematicBrowserStatus status,
        @Nullable String failureMessage,
        boolean canReturnToParent,
        SchematicBrowserWriteStatus writeStatus,
        @Nullable String writeFailureMessage) {
    /// Validates scan and write lifecycle fields without coupling either state machine to the other.
    public SchematicBrowserSnapshot {
        Objects.requireNonNull(rootDirectory, "rootDirectory");
        Objects.requireNonNull(currentDirectory, "currentDirectory");
        Objects.requireNonNull(itemCount, "itemCount");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(writeStatus, "writeStatus");
        if (contentRevision < 0L) {
            throw new IllegalArgumentException("contentRevision must not be negative");
        }
        if (status == SchematicBrowserStatus.ERROR) {
            requireFailureText(failureMessage, "failureMessage");
        } else if (failureMessage != null) {
            throw new IllegalArgumentException("failureMessage is only valid for scan errors");
        }
        if (writeStatus == SchematicBrowserWriteStatus.ERROR) {
            requireFailureText(writeFailureMessage, "writeFailureMessage");
        } else if (writeFailureMessage != null) {
            throw new IllegalArgumentException(
                    "writeFailureMessage is only valid for write errors");
        }
    }

    /// Creates a snapshot without a running or failed write for existing read-only consumers.
    ///
    /// @param rootDirectory immutable navigation boundary
    /// @param currentDirectory directory represented by the last successful scan
    /// @param itemCount exact indexed row count, or empty before the first successful scan
    /// @param contentRevision revision incremented by each successfully committed scan
    /// @param status current directory lifecycle
    /// @param failureMessage latest scan failure text, or null outside the error state
    /// @param canReturnToParent whether parent navigation remains inside the root boundary
    public SchematicBrowserSnapshot(
            Path rootDirectory,
            Path currentDirectory,
            OptionalInt itemCount,
            long contentRevision,
            SchematicBrowserStatus status,
            @Nullable String failureMessage,
            boolean canReturnToParent) {
        this(
                rootDirectory,
                currentDirectory,
                itemCount,
                contentRevision,
                status,
                failureMessage,
                canReturnToParent,
                SchematicBrowserWriteStatus.IDLE,
                null);
    }

    /// Rejects null or blank lifecycle failure text.
    ///
    /// @param failure failure text to validate
    /// @param parameterName parameter used in validation diagnostics
    private static void requireFailureText(@Nullable String failure, String parameterName) {
        if (failure == null || failure.isBlank()) {
            throw new IllegalArgumentException(parameterName + " must not be blank");
        }
    }
}

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
// Added by MinecraftSTL in 2026 for safe XoyzNBT file editing.
package space.minecraftstl.xyml.library.nbt.io;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Objects;

/// Immutable options for one [NBTFile#save(NBTSaveOptions)] operation.
@NotNullByDefault
public final class NBTSaveOptions {
    /// Shared options instance which resolves the source's default rolling backup.
    private static final NBTSaveOptions DEFAULTS = new NBTSaveOptions(null, true);
    /// Shared options instance which explicitly disables backups.
    private static final NBTSaveOptions WITHOUT_BACKUP = new NBTSaveOptions(null, false);

    /// Optional path which receives one atomically replaced copy of the previous source.
    private final @Nullable Path backupPath;
    /// Whether a standalone save should derive `<source>.xyml_old` when no path is supplied.
    private final boolean defaultBackup;

    /// Creates options using the default standalone rolling backup.
    public NBTSaveOptions() {
        this(null, true);
    }

    /// Creates save options with an optional rolling backup path.
    ///
    /// A non-null path is interpreted when the save starts and may be outside the source
    /// directory. The backup itself is staged and atomically replaced in its own directory.
    ///
    /// @param backupPath backup destination, or `null` to disable backups
    public NBTSaveOptions(@Nullable Path backupPath) {
        this(backupPath, false);
    }

    /// Creates immutable options with an explicit default-backup mode.
    ///
    /// @param backupPath explicit backup destination, or `null`
    /// @param defaultBackup whether `null` resolves to the source's `.xyml_old` sibling
    private NBTSaveOptions(@Nullable Path backupPath, boolean defaultBackup) {
        this.backupPath = backupPath;
        this.defaultBackup = defaultBackup;
    }

    /// Returns options which derive the source's default `.xyml_old` backup.
    ///
    /// @return shared default options
    @Contract(pure = true)
    public static NBTSaveOptions defaults() {
        return DEFAULTS;
    }

    /// Returns options which explicitly disable backups.
    ///
    /// @return shared default options
    @Contract(pure = true)
    public static NBTSaveOptions withoutBackup() {
        return WITHOUT_BACKUP;
    }

    /// Creates options which atomically replace one rolling backup on every successful save.
    ///
    /// @param backupPath backup destination
    /// @return immutable save options
    @Contract("_ -> new")
    public static NBTSaveOptions withBackup(Path backupPath) {
        return new NBTSaveOptions(Objects.requireNonNull(backupPath, "backupPath"));
    }

    /// Returns the configured backup destination.
    ///
    /// @return backup destination, or `null` when backups are disabled
    @Contract(pure = true)
    public @Nullable Path backupPath() {
        return backupPath;
    }

    /// Returns the configured backup destination.
    ///
    /// @return backup destination, or `null` when backups are disabled
    @Contract(pure = true)
    public @Nullable Path getBackupPath() {
        return backupPath;
    }

    /// Returns whether the source-relative default backup should be resolved by [NBTFile].
    public boolean usesDefaultBackup() {
        return defaultBackup && backupPath == null;
    }

    /// Returns whether another object configures the same backup destination.
    ///
    /// @param object candidate object
    /// @return whether the objects are equal
    @Override
    public boolean equals(Object object) {
        return this == object
                || object instanceof NBTSaveOptions other
                && Objects.equals(backupPath, other.backupPath)
                && defaultBackup == other.defaultBackup;
    }

    /// Returns a hash code consistent with [#equals(Object)].
    ///
    /// @return options hash code
    @Override
    public int hashCode() {
        return Objects.hash(backupPath, defaultBackup);
    }

    /// Returns a diagnostic representation of these immutable options.
    ///
    /// @return diagnostic representation
    @Override
    public String toString() {
        return "NBTSaveOptions[backupPath=" + backupPath + ", defaultBackup=" + defaultBackup + ']';
    }
}

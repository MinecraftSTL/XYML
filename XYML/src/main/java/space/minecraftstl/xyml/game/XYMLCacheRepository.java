/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml.game;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.download.DefaultCacheRepository;

import java.nio.file.Paths;
import java.util.Objects;

/// Provides the process-wide launcher download cache rooted at the configured common directory.
@NotNullByDefault
public final class XYMLCacheRepository extends DefaultCacheRepository {
    /// Shared cache repository installed after launcher settings are loaded.
    public static final XYMLCacheRepository REPOSITORY = new XYMLCacheRepository();

    /// Current common-directory path used by the cache.
    private String directory = "";

    /// Creates an unconfigured repository whose directory is assigned during launcher startup.
    public XYMLCacheRepository() {
    }

    /// Returns the configured common-directory path.
    ///
    /// @return current directory path, or an empty string before startup configuration
    public synchronized String getDirectory() {
        return directory;
    }

    /// Changes the cache root when the effective common-directory path changes.
    ///
    /// @param directory non-empty common-directory path
    public synchronized void setDirectory(String directory) {
        String normalized = Objects.requireNonNull(directory, "directory");
        if (normalized.equals(this.directory)) {
            return;
        }
        this.directory = normalized;
        changeDirectory(Paths.get(normalized));
    }
}

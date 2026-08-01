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
package space.minecraftstl.xyml.download;

import org.jetbrains.annotations.NotNullByDefault;

/// Supplies common download-provider and version-list behavior for dependency managers.
///
/// @author huangyuhui
@NotNullByDefault
public abstract class AbstractDependencyManager implements DependencyManager {

    /// Returns the provider used to resolve remote Minecraft and loader versions.
    public abstract DownloadProvider getDownloadProvider();

    /// Returns the cache repository used by this dependency manager.
    @Override
    public abstract DefaultCacheRepository getCacheRepository();

    /// Returns a registered remote version list by its logical identifier.
    ///
    /// @param id logical list identifier such as `game` or `forge`
    /// @return the matching version list
    @Override
    public VersionList<?> getVersionList(String id) {
        return getDownloadProvider().getVersionListById(id);
    }
}

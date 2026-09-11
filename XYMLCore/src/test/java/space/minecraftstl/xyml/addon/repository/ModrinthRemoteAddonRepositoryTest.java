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
package space.minecraftstl.xyml.addon.repository;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.addon.RemoteAddonRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Tests provider-neutral ordering conversion for Modrinth project searches.
@NotNullByDefault
public final class ModrinthRemoteAddonRepositoryTest {

    /// Verifies that every provider-neutral sort maps to a supported Modrinth search index.
    @Test
    public void mapsSortTypesToSupportedModrinthIndexes() {
        assertEquals("follows", ModrinthRemoteAddonRepository.convertSortType(
                RemoteAddonRepository.SortType.POPULARITY));
        assertEquals("relevance", ModrinthRemoteAddonRepository.convertSortType(
                RemoteAddonRepository.SortType.NAME));
        assertEquals("newest", ModrinthRemoteAddonRepository.convertSortType(
                RemoteAddonRepository.SortType.DATE_CREATED));
        assertEquals("updated", ModrinthRemoteAddonRepository.convertSortType(
                RemoteAddonRepository.SortType.LAST_UPDATED));
        assertEquals("relevance", ModrinthRemoteAddonRepository.convertSortType(
                RemoteAddonRepository.SortType.AUTHOR));
        assertEquals("downloads", ModrinthRemoteAddonRepository.convertSortType(
                RemoteAddonRepository.SortType.TOTAL_DOWNLOADS));
    }
}

// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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

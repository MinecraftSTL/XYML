// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management.servers;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Objects;

/// Immutable state published by the server-list model.
@NotNullByDefault
public record ServerCatalogSnapshot(
        ServerCatalogStatus status,
        @Unmodifiable List<ServerCatalogItem> servers,
        String message) {
    /// Validates and defensively copies one snapshot.
    public ServerCatalogSnapshot {
        Objects.requireNonNull(status, "status");
        servers = List.copyOf(servers);
        Objects.requireNonNull(message, "message");
    }
}

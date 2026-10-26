// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/// Immutable, presentation-safe configured authlib-injector server list.
///
/// Server endpoints are unique workflow identifiers. The snapshot deliberately contains no mutable
/// server objects, mutable metadata, or account credentials.
///
/// @param servers immutable configured servers in persisted order
@NotNullByDefault
public record AuthlibServerSnapshot(@Unmodifiable List<AuthlibServerOption> servers) {
    /// Defensively copies server options and rejects duplicate endpoint identifiers.
    public AuthlibServerSnapshot {
        servers = List.copyOf(Objects.requireNonNull(servers, "servers"));
        Set<String> urls = new HashSet<>(servers.size());
        for (AuthlibServerOption server : servers) {
            if (!urls.add(server.url())) {
                throw new IllegalArgumentException(
                        "Duplicate authlib-injector server URL: " + server.url());
            }
        }
    }
}

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

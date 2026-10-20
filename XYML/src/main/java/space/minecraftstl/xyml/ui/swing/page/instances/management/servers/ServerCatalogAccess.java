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
package space.minecraftstl.xyml.ui.swing.page.instances.management.servers;

import org.jetbrains.annotations.NotNullByDefault;
import java.io.IOException;
import java.util.List;

/// Filesystem boundary for one instance's Minecraft server list.
@NotNullByDefault
public interface ServerCatalogAccess {
    /// Reads the current ordered server list.
    ///
    /// @return immutable ordered entries
    /// @throws IOException if the source cannot be read or is malformed
    List<ServerCatalogItem> read() throws IOException;

    /// Publishes one complete ordered server list.
    ///
    /// @param servers ordered entries to publish
    /// @throws IOException if staging or publication fails
    void write(List<ServerCatalogItem> servers) throws IOException;

}

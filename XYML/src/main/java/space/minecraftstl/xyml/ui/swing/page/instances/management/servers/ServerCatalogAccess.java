// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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

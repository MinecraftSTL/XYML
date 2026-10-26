// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.update;

import org.jetbrains.annotations.NotNullByDefault;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.util.Objects;

/// Opens the manual launcher release page independently from any automatic-update implementation.
@FunctionalInterface
@NotNullByDefault
public interface ReleasePageLauncher {
    /// Creates a cross-platform system-browser launcher backed by [Desktop].
    ///
    /// @return desktop release-page launcher
    static ReleasePageLauncher desktop() {
        return releasePage -> {
            Objects.requireNonNull(releasePage, "releasePage");
            try {
                if (!Desktop.isDesktopSupported()) {
                    throw new IOException("Desktop integration is not supported");
                }
                Desktop desktop = Desktop.getDesktop();
                if (!desktop.isSupported(Desktop.Action.BROWSE)) {
                    throw new IOException("Desktop browser integration is not supported");
                }
                desktop.browse(releasePage);
            } catch (UnsupportedOperationException | SecurityException failure) {
                throw new IOException("Cannot open the release page", failure);
            }
        };
    }

    /// Opens one absolute HTTP(S) release page.
    ///
    /// @param releasePage manual upgrade or release page
    /// @throws IOException when no system browser can be launched
    void open(URI releasePage) throws IOException;
}

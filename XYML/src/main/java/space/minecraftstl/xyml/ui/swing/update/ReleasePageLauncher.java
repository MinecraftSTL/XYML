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

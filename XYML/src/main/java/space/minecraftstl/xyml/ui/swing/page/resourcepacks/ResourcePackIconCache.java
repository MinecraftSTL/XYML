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
package space.minecraftstl.xyml.ui.swing.page.resourcepacks;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.image.EncodedImage;

import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JList;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/// Lazily decodes local resource-pack icons without performing file or ImageIO work on the EDT.
@NotNullByDefault
final class ResourcePackIconCache {
    /// Fixed resource-pack row icon edge.
    static final int ICON_SIZE = 32;

    /// Maximum decoded source edge accepted before scaling.
    private static final int MAX_SOURCE_EDGE = 4096;

    /// Placeholder for a pack without pack.png.
    static final Icon PLACEHOLDER = createPlaceholder(new Color(128, 128, 128, 80));

    /// Marker for a malformed or unreadable pack icon.
    static final Icon FAILURE = createPlaceholder(new Color(190, 90, 90, 180));

    /// In-flight and completed icons keyed by normalized pack path.
    private final ConcurrentMap<Path, CompletableFuture<Icon>> icons = new ConcurrentHashMap<>();

    /// Returns a completed icon or null while the model loads encoded icon data.
    ///
    /// @param model resource-pack model owning the file access boundary
    /// @param item visible resource-pack row
    /// @param list list to repaint after asynchronous completion
    /// @return cached icon, placeholder, or null while loading
    @Nullable Icon iconFor(ResourcePackCatalogModel model, ResourcePackCatalogItem item, JList<?> list) {
        Path path = Objects.requireNonNull(item, "item").path().toAbsolutePath().normalize();
        CompletableFuture<Icon> future = icons.computeIfAbsent(path, ignored ->
                model.loadIcon(path)
                        .thenApply(ResourcePackIconCache::decode)
                        .toCompletableFuture()
                        .exceptionally(ignoredFailure -> FAILURE));
        if (!future.isDone()) {
            future.whenComplete((ignoredIcon, ignoredFailure) ->
                    SwingUtilities.invokeLater(list::repaint));
            return PLACEHOLDER;
        }
        return future.getNow(PLACEHOLDER);
    }

    /// Invalidates paths after a catalog content revision changes.
    void clear() {
        icons.clear();
    }

    /// Decodes one immutable encoded pack icon and scales it to the shared row size.
    private static Icon decode(@Nullable EncodedImage encodedImage) {
        if (encodedImage == null) {
            return PLACEHOLDER;
        }
        try {
            BufferedImage source = ImageIO.read(encodedImage.openStream());
            if (source == null
                    || source.getWidth() > MAX_SOURCE_EDGE
                    || source.getHeight() > MAX_SOURCE_EDGE) {
                return FAILURE;
            }
            BufferedImage target = new BufferedImage(
                    ICON_SIZE,
                    ICON_SIZE,
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = target.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.drawImage(source, 0, 0, ICON_SIZE, ICON_SIZE, null);
            } finally {
                graphics.dispose();
                source.flush();
            }
            return new ImageIcon(target);
        } catch (IOException | RuntimeException failure) {
            return FAILURE;
        }
    }

    /// Creates a stable colored square fallback without reading a file.
    private static Icon createPlaceholder(Color color) {
        BufferedImage image = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(color);
            graphics.fillRect(0, 0, ICON_SIZE, ICON_SIZE);
        } finally {
            graphics.dispose();
        }
        return new ImageIcon(image);
    }
}

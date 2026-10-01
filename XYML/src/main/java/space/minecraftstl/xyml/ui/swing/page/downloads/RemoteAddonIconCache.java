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
package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JList;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/// Loads remote add-on icons away from the Swing event-dispatch thread and shares results in memory.
@NotNullByDefault
final class RemoteAddonIconCache {
    /// Fixed result-row edge in pixels.
    static final int ICON_SIZE = 32;

    /// Shared production cache for all remote catalog result lists in this process.
    private static final RemoteAddonIconCache SHARED = new RemoteAddonIconCache(
            new NetworkRemoteAddonIconLoader());

    /// Placeholder used while a remote icon is unavailable.
    static final Icon PLACEHOLDER = createPlaceholder(new Color(128, 128, 128, 80));

    /// Failure marker used after a request or decode failure.
    static final Icon FAILURE = createPlaceholder(new Color(190, 90, 90, 180));

    /// Injectable loader used by this cache instance.
    private final RemoteAddonIconLoader loader;

    /// In-flight and completed icons keyed by URL and fixed target size.
    private final ConcurrentMap<String, CompletableFuture<Icon>> icons = new ConcurrentHashMap<>();

    /// Creates a cache using the production network loader.
    RemoteAddonIconCache() {
        this(new NetworkRemoteAddonIconLoader());
    }

    /// Creates a cache with a controllable asynchronous loader for isolated tests.
    ///
    /// @param loader asynchronous icon loader
    RemoteAddonIconCache(RemoteAddonIconLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    /// Returns the process-shared production cache.
    ///
    /// @return shared in-memory icon cache
    static RemoteAddonIconCache shared() {
        return SHARED;
    }

    /// Returns a completed icon or null while a bounded background request is pending.
    ///
    /// @param item remote result whose provider icon URL may be loaded
    /// @param list result list to repaint after completion
    /// @return cached icon, or null while loading
    @Nullable Icon iconFor(RemoteAddonCatalogItem item, JList<?> list) {
        String rawUrl = Objects.requireNonNull(item, "item").addon().iconUrl();
        if (rawUrl.isBlank()) {
            return FAILURE;
        }
        CompletableFuture<Icon> future = icons.computeIfAbsent(rawUrl, ignored -> load(rawUrl));
        if (!future.isDone()) {
            future.whenComplete((ignoredIcon, ignoredFailure) ->
                    SwingUtilities.invokeLater(list::repaint));
            return null;
        }
        return future.getNow(FAILURE);
    }

    /// Starts one injected load and converts all ordinary loader failures to a stable placeholder.
    private CompletableFuture<Icon> load(String rawUrl) {
        try {
            CompletionStage<Icon> stage = Objects.requireNonNull(loader.load(rawUrl), "loader result");
            return stage.toCompletableFuture().exceptionally(ignoredFailure -> FAILURE);
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(FAILURE);
        }
    }

    /// Creates a stable colored square fallback without loading a resource or touching the network.
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

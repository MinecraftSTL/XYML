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
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.task.Schedulers;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JList;
import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/// Lazily derives UUID-stable account head icons from launcher-bundled skin textures.
///
/// The first renderer request returns immediately. Classpath image decoding and nearest-neighbor
/// head extraction run on the shared I/O scheduler, then only a repaint is posted to the EDT.
/// No account page open or row paint performs network or filesystem work.
@NotNullByDefault
final class AccountAvatarIconCache {
    /// Fixed account avatar edge matching the stable row icon slot.
    static final int ICON_SIZE = 40;

    /// In-flight and completed icon loads keyed by immutable profile identity.
    private final ConcurrentMap<AvatarKey, CompletableFuture<Icon>> icons = new ConcurrentHashMap<>();

    /// Returns a completed icon or null while its bundled texture is still decoding.
    ///
    /// @param item loaded account row
    /// @param list owning list repainted after asynchronous completion
    /// @return decoded icon, or null while loading
    @Nullable Icon iconFor(AccountListItem item, JList<?> list) {
        AccountListItem row = Objects.requireNonNull(item, "item");
        JList<?> owner = Objects.requireNonNull(list, "list");
        AvatarKey key = new AvatarKey(row.displayName(), row.profileId());
        CompletableFuture<Icon> future = icons.computeIfAbsent(key, ignored ->
                CompletableFuture.supplyAsync(() -> loadIcon(key), Schedulers.io())
                        .exceptionally(AccountAvatarIconCache::failureIcon)
                        .whenComplete((icon, failure) -> SwingUtilities.invokeLater(owner::repaint)));
        return future.getNow(null);
    }

    /// Replaces an unexpected bundled-resource failure with a stable non-null marker.
    ///
    /// @param failure avatar decoding failure
    /// @return fixed failure icon retained in the cache
    private static Icon failureIcon(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        BufferedImage image = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new java.awt.Color(180, 64, 64, 48));
            graphics.fillRoundRect(0, 0, ICON_SIZE, ICON_SIZE, 6, 6);
            graphics.setColor(new java.awt.Color(180, 64, 64));
            graphics.drawString("!", 18, 25);
        } finally {
            graphics.dispose();
        }
        return new ImageIcon(image);
    }

    /// Decodes one UUID-derived bundled skin and extracts its base and hat head layers.
    ///
    /// @param key immutable profile identity
    /// @return crisp fixed-size head icon
    private static Icon loadIcon(AvatarKey key) {
        try {
            OfflineSkinPreview preview = OfflineSkinPreviewLoader.load(
                    null,
                    key.profileName(),
                    key.profileId());
            BufferedImage texture = preview.skin();
            int textureScale = Math.max(1, texture.getWidth() / 64);
            BufferedImage head = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = head.createGraphics();
            try {
                graphics.setRenderingHint(
                        RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                drawLayer(graphics, texture, 8, 8, textureScale);
                drawLayer(graphics, texture, 40, 8, textureScale);
            } finally {
                graphics.dispose();
            }
            return new ImageIcon(head);
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("Failed to load bundled account avatar", failure);
        }
    }

    /// Draws one canonical eight-pixel head layer into the fixed icon surface.
    ///
    /// @param graphics destination graphics
    /// @param texture decoded bundled skin
    /// @param canonicalX canonical source X coordinate
    /// @param canonicalY canonical source Y coordinate
    /// @param textureScale source pixels per canonical pixel
    private static void drawLayer(
            Graphics2D graphics,
            BufferedImage texture,
            int canonicalX,
            int canonicalY,
            int textureScale) {
        int sourceX = canonicalX * textureScale;
        int sourceY = canonicalY * textureScale;
        int sourceSize = 8 * textureScale;
        graphics.drawImage(
                texture,
                0,
                0,
                ICON_SIZE,
                ICON_SIZE,
                sourceX,
                sourceY,
                sourceX + sourceSize,
                sourceY + sourceSize,
                null);
    }

    /// Immutable key for one UUID-derived default avatar.
    ///
    /// @param profileName profile name used only if the profile ID is malformed
    /// @param profileId stable Minecraft profile UUID text
    @NotNullByDefault
    private record AvatarKey(String profileName, String profileId) {
        /// Validates one immutable avatar key.
        private AvatarKey {
            Objects.requireNonNull(profileName, "profileName");
            Objects.requireNonNull(profileId, "profileId");
        }
    }
}

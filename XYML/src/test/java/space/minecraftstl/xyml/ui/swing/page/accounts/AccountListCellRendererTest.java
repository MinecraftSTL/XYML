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
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.choice.ChoiceListEntry;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JList;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies stable account-row geometry and real bundled-avatar offscreen rendering.
@NotNullByDefault
public final class AccountListCellRendererTest {
    /// Maximum time allowed for the shared avatar loader to decode one bundled texture.
    private static final Duration AVATAR_TIMEOUT = Duration.ofSeconds(10);

    /// A loaded row retains its fixed height and paints a decoded 40-pixel avatar offscreen.
    @Test
    public void paintsLoadedAccountWithStableAvatarSlot() {
        AccountListCellRenderer renderer = onEdt(AccountListCellRenderer::new);
        JList<ChoiceListEntry<AccountListItem>> list = onEdt(JList::new);
        ChoiceListEntry<AccountListItem> entry = ChoiceListEntry.loaded(
                0,
                new AccountListItem(
                        "account-0",
                        "Test Player",
                        "Microsoft - Global",
                        "00000000-0000-0000-0000-000000000009"));

        runOnEdt(() -> renderer.getListCellRendererComponent(list, entry, 0, true, true));
        awaitEdt(() -> {
            renderer.getListCellRendererComponent(list, entry, 0, true, true);
            return findLabel(renderer, "accountListAvatar").getIcon() instanceof ImageIcon;
        });

        AtomicReference<@Nullable BufferedImage> renderedReference = new AtomicReference<>();
        AtomicReference<@Nullable Rectangle> avatarBoundsReference = new AtomicReference<>();
        AtomicReference<@Nullable Integer> backgroundReference = new AtomicReference<>();
        runOnEdt(() -> {
            Component component = renderer.getListCellRendererComponent(list, entry, 0, true, true);
            JLabel avatar = findLabel(renderer, "accountListAvatar");
            Icon icon = assertInstanceOf(ImageIcon.class, avatar.getIcon());
            Dimension preferred = avatar.getPreferredSize();
            Dimension minimum = avatar.getMinimumSize();
            Dimension maximum = avatar.getMaximumSize();

            assertAll(
                    () -> assertEquals(AccountListCellRenderer.ROW_HEIGHT, component.getPreferredSize().height),
                    () -> assertEquals(40, preferred.width),
                    () -> assertEquals(40, preferred.height),
                    () -> assertEquals(preferred, minimum),
                    () -> assertEquals(preferred, maximum),
                    () -> assertEquals(40, icon.getIconWidth()),
                    () -> assertEquals(40, icon.getIconHeight()));

            component.setSize(360, AccountListCellRenderer.ROW_HEIGHT);
            layoutRecursively((Container) component);
            BufferedImage rendered = new BufferedImage(
                    component.getWidth(),
                    component.getHeight(),
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = rendered.createGraphics();
            try {
                component.paint(graphics);
            } finally {
                graphics.dispose();
            }
            renderedReference.set(rendered);
            avatarBoundsReference.set(avatar.getBounds());
            backgroundReference.set(component.getBackground().getRGB());
        });

        BufferedImage rendered = Objects.requireNonNull(renderedReference.get(), "Missing renderer image");
        Rectangle avatarBounds = Objects.requireNonNull(avatarBoundsReference.get(), "Missing avatar bounds");
        int background = Objects.requireNonNull(backgroundReference.get(), "Missing renderer background");
        assertAll(
                () -> assertEquals(AccountListCellRenderer.ROW_HEIGHT, rendered.getHeight()),
                () -> assertEquals(40, avatarBounds.width),
                () -> assertTrue(countOpaquePixels(rendered) > 10_000),
                () -> assertTrue(distinctColors(rendered) > 8),
                () -> assertTrue(countPixelsDifferentFrom(rendered, avatarBounds, background) > 300));
    }

    /// Lays out a component hierarchy before offscreen painting.
    ///
    /// @param container hierarchy root
    private static void layoutRecursively(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                layoutRecursively(nested);
            }
        }
    }

    /// Finds one named label without reflecting into renderer fields.
    ///
    /// @param root hierarchy root
    /// @param name stable component name
    /// @return matching label
    private static JLabel findLabel(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (Objects.equals(name, child.getName()) && child instanceof JLabel label) {
                return label;
            }
            if (child instanceof Container nested) {
                @Nullable JLabel result = findLabelOrNull(nested, name);
                if (result != null) {
                    return result;
                }
            }
        }
        throw new IllegalArgumentException("Missing label: " + name);
    }

    /// Finds a named descendant or returns null when one branch does not contain it.
    ///
    /// @param root branch root
    /// @param name stable component name
    /// @return matching label, or null
    private static @Nullable JLabel findLabelOrNull(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (Objects.equals(name, child.getName()) && child instanceof JLabel label) {
                return label;
            }
            if (child instanceof Container nested) {
                @Nullable JLabel result = findLabelOrNull(nested, name);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    /// Counts nontransparent pixels in an offscreen result.
    ///
    /// @param image rendered image
    /// @return nontransparent pixel count
    private static int countOpaquePixels(BufferedImage image) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); ++y) {
            for (int x = 0; x < image.getWidth(); ++x) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    ++count;
                }
            }
        }
        return count;
    }

    /// Counts distinct ARGB values in an offscreen result.
    ///
    /// @param image rendered image
    /// @return number of distinct colors
    private static int distinctColors(BufferedImage image) {
        Set<Integer> colors = new HashSet<>();
        for (int y = 0; y < image.getHeight(); ++y) {
            for (int x = 0; x < image.getWidth(); ++x) {
                colors.add(image.getRGB(x, y));
            }
        }
        return colors.size();
    }

    /// Counts avatar-slot pixels that differ from the renderer background.
    ///
    /// @param image rendered image
    /// @param bounds avatar-label bounds
    /// @param background renderer background ARGB
    /// @return differing pixel count
    private static int countPixelsDifferentFrom(BufferedImage image, Rectangle bounds, int background) {
        int count = 0;
        int maximumX = Math.min(image.getWidth(), bounds.x + bounds.width);
        int maximumY = Math.min(image.getHeight(), bounds.y + bounds.height);
        for (int y = Math.max(0, bounds.y); y < maximumY; ++y) {
            for (int x = Math.max(0, bounds.x); x < maximumX; ++x) {
                if (image.getRGB(x, y) != background) {
                    ++count;
                }
            }
        }
        return count;
    }

    /// Polls an EDT-owned condition until it succeeds or the avatar timeout elapses.
    ///
    /// @param condition condition evaluated only on the EDT
    private static void awaitEdt(BooleanSupplier condition) {
        long deadline = System.nanoTime() + AVATAR_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (onEdt(condition::getAsBoolean)) {
                return;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for bundled avatar", interrupted);
            }
        }
        assertTrue(onEdt(condition::getAsBoolean), "Timed out waiting for bundled avatar");
    }

    /// Runs an action synchronously on the EDT.
    ///
    /// @param action UI action
    private static void runOnEdt(Runnable action) {
        EdtDispatcher.executeAndWait(action);
    }

    /// Runs a value-producing operation synchronously on the EDT.
    ///
    /// @param operation UI operation
    /// @param <T> non-null result type
    /// @return operation result
    private static <T extends Object> T onEdt(Supplier<T> operation) {
        CompletableFuture<T> result = new CompletableFuture<>();
        EdtDispatcher.executeAndWait(() -> result.complete(operation.get()));
        return result.join();
    }
}

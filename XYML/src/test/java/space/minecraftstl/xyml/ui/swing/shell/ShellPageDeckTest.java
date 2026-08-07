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
package space.minecraftstl.xyml.ui.swing.shell;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.MotionPolicy;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;

import javax.swing.JPanel;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies stable page ownership and cached-frame transitions in both top-level navigation directions.
@NotNullByDefault
public final class ShellPageDeckTest {
    /// A transition retains one live page and can animate back after releasing its cached outgoing frame.
    @Test
    public void compositesOutgoingFrameWithoutRetainingItsComponent() {
        SwingAnimator animator = new SwingAnimator(MotionPolicy.FULL, 10_000);

        EdtDispatcher.executeAndWait(() -> {
            ShellPageDeck deck = new ShellPageDeck(animator, Duration.ofSeconds(2L));
            JPanel first = new JPanel();
            JPanel second = new JPanel();

            deck.setSize(640, 480);
            deck.showPage(first, false);
            deck.doLayout();
            deck.showPage(second, true);
            assertAll(
                    () -> assertSame(second, deck.currentPage()),
                    () -> assertEquals(1, deck.getComponentCount()),
                    () -> assertNull(first.getParent()),
                    () -> assertSame(deck, second.getParent()),
                    () -> assertFalse(first.isVisible()),
                    () -> assertTrue(second.isVisible()),
                    deck::isTransitionRunning);

            animator.setMotionPolicy(MotionPolicy.OFF);
            assertAll(
                    () -> assertEquals(1, deck.getComponentCount()),
                    () -> assertFalse(first.isVisible()),
                    () -> assertTrue(second.isVisible()),
                    () -> assertFalse(deck.isTransitionRunning()));

            animator.setMotionPolicy(MotionPolicy.FULL);
            deck.doLayout();
            deck.showPage(first, true);
            assertAll(
                    () -> assertSame(first, deck.currentPage()),
                    () -> assertEquals(1, deck.getComponentCount()),
                    () -> assertTrue(first.isVisible()),
                    () -> assertFalse(second.isVisible()),
                    deck::isTransitionRunning);

            animator.setMotionPolicy(MotionPolicy.OFF);
            assertAll(
                    () -> assertEquals(1, deck.getComponentCount()),
                    () -> assertTrue(first.isVisible()),
                    () -> assertFalse(second.isVisible()),
                    () -> assertFalse(deck.isTransitionRunning()));
        });
    }
}

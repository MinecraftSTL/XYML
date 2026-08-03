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
package space.minecraftstl.xyml.ui.swing;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies cached-frame content transitions and inherited animated tab selection.
@NotNullByDefault
public final class SwingContentTransitionTest {
    /// Repeated paints reuse exactly two frames and initially conceal the already-installed incoming component.
    @Test
    public void capturesBothStatesOnceAndStartsFromOutgoingFrame() {
        SwingAnimator animator = new SwingAnimator(MotionPolicy.FULL, 10_000);

        EdtDispatcher.executeAndWait(() -> {
            CountingHost host = new CountingHost();
            JPanel outgoing = new JPanel();
            JPanel incoming = new JPanel();
            outgoing.setBackground(Color.RED);
            incoming.setBackground(Color.BLUE);
            host.setSize(480, 320);
            outgoing.setBounds(0, 0, 480, 320);
            incoming.setBounds(0, 0, 480, 320);
            host.add(outgoing);
            SwingContentTransition transition = new SwingContentTransition(
                    host,
                    animator,
                    Duration.ofSeconds(2L),
                    20);

            transition.transitionFrom(outgoing, SwingContentTransition.Direction.BACKWARD, () -> {
                host.remove(outgoing);
                host.add(incoming);
            });
            BufferedImage target = new BufferedImage(480, 320, BufferedImage.TYPE_INT_ARGB);
            Graphics graphics = target.getGraphics();
            try {
                assertTrue(transition.paintFrames(graphics));
                assertTrue(transition.paintFrames(graphics));
            } finally {
                graphics.dispose();
            }

            assertAll(
                    () -> assertEquals(2, host.printCount()),
                    () -> assertEquals(Color.RED.getRGB(), target.getRGB(240, 160)),
                    () -> assertEquals(
                            SwingContentTransition.Direction.BACKWARD,
                            transition.direction()),
                    transition::isRunning,
                    () -> assertSame(host, incoming.getParent()));
            animator.setMotionPolicy(MotionPolicy.OFF);
            assertFalse(transition.isRunning());
        });
    }

    /// Tab selection inherits context and derives opposite directions from relative tab indices.
    @Test
    public void animatesInheritedTabContentSelection() {
        SwingAnimator animator = new SwingAnimator(MotionPolicy.FULL, 10_000);

        EdtDispatcher.executeAndWait(() -> {
            AnimatedTabbedPane tabs = new AnimatedTabbedPane();
            SwingContentTransition.provideContext(tabs, animator, Duration.ofSeconds(2L));
            JPanel first = new JPanel();
            JPanel second = new JPanel();
            JPanel third = new JPanel();
            tabs.addTab("First", first);
            tabs.addTab("Second", second);
            tabs.addTab("Third", third);
            tabs.setSize(480, 320);
            tabs.doLayout();

            tabs.setSelectedIndex(2);

            assertAll(
                    () -> assertEquals(2, tabs.getSelectedIndex()),
                    () -> assertTrue(first.getWidth() > 0),
                    () -> assertEquals(
                            SwingContentTransition.Direction.FORWARD,
                            tabs.contentTransitionDirection()),
                    tabs::isContentTransitionRunning);
            animator.setMotionPolicy(MotionPolicy.OFF);
            assertFalse(tabs.isContentTransitionRunning());

            animator.setMotionPolicy(MotionPolicy.FULL);
            tabs.setSelectedIndex(0);
            assertAll(
                    () -> assertEquals(0, tabs.getSelectedIndex()),
                    () -> assertEquals(
                            SwingContentTransition.Direction.BACKWARD,
                            tabs.contentTransitionDirection()),
                    tabs::isContentTransitionRunning);
            animator.setMotionPolicy(MotionPolicy.OFF);
        });
    }

    /// Host exposing how often a complete component-tree print was requested.
    @NotNullByDefault
    private static final class CountingHost extends JPanel {
        /// Number of complete host prints used to create transition frames.
        private final AtomicInteger printCount = new AtomicInteger();

        /// Creates a fixed-bounds host for deterministic transition capture.
        private CountingHost() {
            super(null);
        }

        /// Records full-tree rendering before delegating to ordinary Swing printing.
        ///
        /// @param graphics print destination
        @Override
        public void printAll(Graphics graphics) {
            printCount.incrementAndGet();
            super.printAll(graphics);
        }

        /// Returns the number of full-tree print requests.
        ///
        /// @return captured print count
        private int printCount() {
            return printCount.get();
        }
    }
}

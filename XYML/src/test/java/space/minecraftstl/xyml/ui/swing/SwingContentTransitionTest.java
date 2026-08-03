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

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
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

            transition.transitionFrom(outgoing, SwingContentTransition.Direction.HORIZONTAL_BACKWARD, () -> {
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
                    () -> assertEquals(2, host.paintCount()),
                    () -> assertEquals(Color.RED.getRGB(), target.getRGB(240, 160)),
                    () -> assertEquals(
                            SwingContentTransition.Direction.HORIZONTAL_BACKWARD,
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
                            SwingContentTransition.Direction.HORIZONTAL_FORWARD,
                            tabs.contentTransitionDirection()),
                    tabs::isContentTransitionRunning);
            animator.setMotionPolicy(MotionPolicy.OFF);
            assertFalse(tabs.isContentTransitionRunning());

            animator.setMotionPolicy(MotionPolicy.FULL);
            tabs.setSelectedIndex(0);
            assertAll(
                    () -> assertEquals(0, tabs.getSelectedIndex()),
                    () -> assertEquals(
                            SwingContentTransition.Direction.HORIZONTAL_BACKWARD,
                            tabs.contentTransitionDirection()),
                    tabs::isContentTransitionRunning);
            animator.setMotionPolicy(MotionPolicy.OFF);
        });
    }

    /// Device-scale conversion retains enough source pixels for HiDPI snapshot composition.
    @Test
    public void scalesSnapshotDimensionsToDevicePixels() {
        assertAll(
                () -> assertEquals(480, SwingContentTransition.scaledPixelLength(480, 1.0)),
                () -> assertEquals(600, SwingContentTransition.scaledPixelLength(480, 1.25)),
                () -> assertEquals(720, SwingContentTransition.scaledPixelLength(480, 1.5)),
                () -> assertEquals(960, SwingContentTransition.scaledPixelLength(480, 2.0)));
    }

    /// Transparent content captures its context root so composed background pixels remain part of the frame.
    @Test
    public void capturesComposedContextSurfaceBehindTransparentContent() {
        SwingAnimator animator = new SwingAnimator(MotionPolicy.FULL, 10_000);

        EdtDispatcher.executeAndWait(() -> {
            JPanel root = new JPanel(null);
            JPanel host = new JPanel(null);
            JPanel outgoing = new JPanel();
            JPanel incoming = new JPanel();
            root.setBackground(Color.GREEN);
            root.setSize(240, 160);
            host.setOpaque(false);
            host.setBounds(20, 20, 200, 120);
            outgoing.setOpaque(false);
            outgoing.setBounds(0, 0, 200, 120);
            incoming.setOpaque(false);
            incoming.setBounds(0, 0, 200, 120);
            root.add(host);
            host.add(outgoing);
            SwingContentTransition.provideContext(root, animator, Duration.ofSeconds(2L));
            SwingContentTransition transition = new SwingContentTransition(host);

            transition.transitionFrom(outgoing, () -> {
                host.remove(outgoing);
                host.add(incoming);
            });
            BufferedImage target = new BufferedImage(200, 120, BufferedImage.TYPE_INT_ARGB_PRE);
            Graphics graphics = target.getGraphics();
            try {
                assertTrue(transition.paintFrames(graphics));
            } finally {
                graphics.dispose();
            }

            assertEquals(Color.GREEN.getRGB(), target.getRGB(100, 60));
            animator.setMotionPolicy(MotionPolicy.OFF);
        });
    }

    /// Vertical tab placement moves content vertically while tab-label components remain live and repaintable.
    @Test
    public void verticalTabsKeepLabelsLiveDuringContentTransition() {
        SwingAnimator animator = new SwingAnimator(MotionPolicy.FULL, 10_000);

        EdtDispatcher.executeAndWait(() -> {
            AnimatedTabbedPane tabs = new AnimatedTabbedPane();
            tabs.setTabPlacement(JTabbedPane.LEFT);
            SwingContentTransition.provideContext(tabs, animator, Duration.ofSeconds(2L));
            tabs.addTab("First", new JPanel());
            tabs.addTab("Second", new JPanel());
            CountingTabLabel firstLabel = new CountingTabLabel("First");
            CountingTabLabel secondLabel = new CountingTabLabel("Second");
            tabs.setTabComponentAt(0, firstLabel);
            tabs.setTabComponentAt(1, secondLabel);
            tabs.setSize(480, 320);
            tabs.doLayout();

            tabs.setSelectedIndex(1);
            firstLabel.resetPaintCount();
            secondLabel.resetPaintCount();
            BufferedImage target = new BufferedImage(480, 320, BufferedImage.TYPE_INT_ARGB_PRE);
            Graphics graphics = target.getGraphics();
            try {
                tabs.paint(graphics);
            } finally {
                graphics.dispose();
            }

            assertAll(
                    () -> assertEquals(
                            SwingContentTransition.Direction.VERTICAL_FORWARD,
                            tabs.contentTransitionDirection()),
                    () -> assertTrue(firstLabel.paintCount() > 0),
                    () -> assertTrue(secondLabel.paintCount() > 0),
                    tabs::isContentTransitionRunning);
            animator.setMotionPolicy(MotionPolicy.OFF);
        });
    }

    /// Host exposing how often a complete component-tree print was requested.
    @NotNullByDefault
    private static final class CountingHost extends JPanel {
        /// Number of complete host paints used to create transition frames.
        private final AtomicInteger paintCount = new AtomicInteger();

        /// Creates a fixed-bounds host for deterministic transition capture.
        private CountingHost() {
            super(null);
        }

        /// Records full-tree rendering before delegating to ordinary Swing painting.
        ///
        /// @param graphics paint destination
        @Override
        public void paint(Graphics graphics) {
            paintCount.incrementAndGet();
            super.paint(graphics);
        }

        /// Returns the number of full-tree paint requests.
        ///
        /// @return captured paint count
        private int paintCount() {
            return paintCount.get();
        }
    }

    /// Tab label that exposes whether live child painting occurred during a content-only transition.
    @NotNullByDefault
    private static final class CountingTabLabel extends JLabel {
        /// Number of component paints since the latest reset.
        private int paintCount;

        /// Creates one visible tab label.
        ///
        /// @param text visible label text
        private CountingTabLabel(String text) {
            super(text);
        }

        /// Records one live label paint before delegating to ordinary text rendering.
        ///
        /// @param graphics label graphics
        @Override
        protected void paintComponent(Graphics graphics) {
            paintCount++;
            super.paintComponent(graphics);
        }

        /// Clears paints performed by transition snapshot capture.
        private void resetPaintCount() {
            paintCount = 0;
        }

        /// Returns paints performed after the latest reset.
        ///
        /// @return live paint count
        private int paintCount() {
            return paintCount;
        }
    }
}

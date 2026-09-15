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

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.ui.FlatNativeWindowBorder;
import com.formdev.flatlaf.util.SystemInfo;
import com.formdev.flatlaf.util.UIScale;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.theme.ThemeBrightnessPreference;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.MotionPolicy;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.SwingDesignTokens;
import space.minecraftstl.xyml.ui.swing.SwingThemeManager;
import space.minecraftstl.xyml.ui.swing.SystemThemeDetector;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JButton;
import javax.swing.JRootPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// Verifies deterministic shell disposal and native-frame constraints.
@NotNullByDefault
public final class AppShellFrameTest {
    /// Visibility transitions clear minimization without discarding maximized state.
    @Test
    public void clearsOnlyIconifiedWindowState() {
        assertAll(
                () -> assertEquals(Frame.NORMAL, AppShellFrame.nonIconifiedState(Frame.ICONIFIED)),
                () -> assertEquals(
                        Frame.MAXIMIZED_BOTH,
                        AppShellFrame.nonIconifiedState(Frame.MAXIMIZED_BOTH | Frame.ICONIFIED)),
                () -> assertEquals(
                        Frame.MAXIMIZED_BOTH,
                        AppShellFrame.nonIconifiedState(Frame.MAXIMIZED_BOTH)));
    }

    /// Shell cleanup precedes native disposal when neither action fails.
    @Test
    public void disposesInOrder() {
        List<String> actions = new ArrayList<>();

        AppShellFrame.disposeInOrder(
                () -> actions.add("shell"),
                () -> actions.add("native"));

        assertEquals(List.of("shell", "native"), actions);
    }

    /// Native disposal still runs when shell cleanup fails, and the original failure is preserved.
    @Test
    public void disposesNativeWindowAfterShellCleanupFailure() {
        List<String> actions = new ArrayList<>();
        IllegalStateException cleanupFailure = new IllegalStateException("shell cleanup failed");

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                AppShellFrame.disposeInOrder(
                        () -> {
                            actions.add("shell");
                            throw cleanupFailure;
                        },
                        () -> actions.add("native")));

        assertAll(
                () -> assertSame(cleanupFailure, thrown),
                () -> assertEquals(List.of("shell", "native"), actions));
    }

    /// A native-disposal failure is suppressed by the earlier shell-cleanup failure.
    @Test
    public void suppressesNativeFailureAfterShellCleanupFailure() {
        List<String> actions = new ArrayList<>();
        IllegalStateException cleanupFailure = new IllegalStateException("shell cleanup failed");
        IllegalArgumentException nativeFailure = new IllegalArgumentException("native disposal failed");

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                AppShellFrame.disposeInOrder(
                        () -> {
                            actions.add("shell");
                            throw cleanupFailure;
                        },
                        () -> {
                            actions.add("native");
                            throw nativeFailure;
                        }));

        assertAll(
                () -> assertSame(cleanupFailure, thrown),
                () -> assertEquals(List.of("shell", "native"), actions),
                () -> assertEquals(1, thrown.getSuppressed().length),
                () -> assertSame(nativeFailure, thrown.getSuppressed()[0]));
    }

    /// The frame installs full-window FlatLaf chrome, paints instance management first, then preloads sidebar pages.
    ///
    /// @throws InterruptedException when the test thread is interrupted while awaiting post-paint preloading
    @Test
    public void createsFullWindowContentFrame() throws InterruptedException {
        assumeFalse(GraphicsEnvironment.isHeadless());
        CountDownLatch pagesCreated = new CountDownLatch(ShellPageId.values().length);
        EnumMap<ShellPageId, ShellPageFactory<? extends JComponent>> factories =
                new EnumMap<>(ShellPageId.class);
        for (ShellPageId page : ShellPageId.values()) {
            factories.put(page, () -> {
                pagesCreated.countDown();
                return new JPanel();
            });
        }

        SwingThemeManager themeManager = new SwingThemeManager(
                ThemeBrightnessPreference.LIGHT,
                new SwingDesignTokens(0),
                SystemThemeDetector.lightFallback());
        AppShellFrame frame = AppShellFrame.create(
                "XYML",
                themeManager,
                factories,
                ShellPagePresentations.englishFallback(),
                new ShellToolbarModels(
                        AppShellPanelTest.testHomeModel(),
                        AppShellPanelTest.testInstancesModel(),
                        AppShellPanelTest.testAccountsModel(),
                        AppShellPanelTest.testGameDirectories(),
                        ShellRecentSelections.transientSelections()),
                AppShellPanelTest.testHomeStrings(),
                space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings.english(),
                new SwingAnimator(MotionPolicy.OFF, 16),
                Duration.ZERO,
                Duration.ZERO);
        try {
            JRootPane rootPane = frame.getRootPane();
            boolean clientDecorated = !SystemInfo.isMacOS
                    && (frame.windowTransparencySupported()
                    || !FlatLaf.supportsNativeWindowDecorations());
            assertAll(
                    () -> assertEquals(clientDecorated, frame.isUndecorated()),
                    () -> assertEquals(Boolean.TRUE, rootPane.getClientProperty(
                            FlatClientProperties.USE_WINDOW_DECORATIONS)),
                    () -> assertEquals(Boolean.TRUE, rootPane.getClientProperty(
                            FlatClientProperties.FULL_WINDOW_CONTENT)),
                    () -> assertEquals(Boolean.FALSE, rootPane.getClientProperty(
                            FlatClientProperties.TITLE_BAR_SHOW_ICON)),
                    () -> assertEquals(Boolean.FALSE, rootPane.getClientProperty(
                            FlatClientProperties.TITLE_BAR_SHOW_TITLE)),
                    () -> assertEquals(Boolean.TRUE, rootPane.getClientProperty(
                            FlatClientProperties.TITLE_BAR_SHOW_ICONIFFY)),
                    () -> assertEquals(Boolean.TRUE, rootPane.getClientProperty(
                            FlatClientProperties.TITLE_BAR_SHOW_MAXIMIZE)),
                    () -> assertEquals(Boolean.TRUE, rootPane.getClientProperty(
                            FlatClientProperties.TITLE_BAR_SHOW_CLOSE)),
                    () -> assertEquals(AppShellPanel.HEADER_HEIGHT, rootPane.getClientProperty(
                            FlatClientProperties.TITLE_BAR_HEIGHT)),
                    () -> assertEquals(SystemInfo.isMacOS ? JRootPane.NONE : JRootPane.FRAME,
                            rootPane.getWindowDecorationStyle()),
                    () -> assertNull(frame.shellPanel().selectedPage()),
                    () -> assertTrue(frame.shellPanel().isPageCached(ShellPageId.INSTANCES)),
                    () -> assertEquals(ShellPageId.values().length - 1L, pagesCreated.getCount()),
                    () -> assertEquals("mac horizontal zeroInFullScreen",
                            frame.shellPanel().toolbar().macWindowButtonsPlaceholder().getClientProperty(
                                    FlatClientProperties.FULL_WINDOW_CONTENT_BUTTONS_PLACEHOLDER)),
                    () -> assertEquals("win horizontal",
                            frame.shellPanel().toolbar().winWindowButtonsPlaceholder().getClientProperty(
                                    FlatClientProperties.FULL_WINDOW_CONTENT_BUTTONS_PLACEHOLDER)));

            AtomicBoolean transparencyActivated = new AtomicBoolean();
            EdtDispatcher.executeAndWait(() -> transparencyActivated.set(
                    frame.applyWindowTransparency(true)));
            assertAll(
                    () -> assertEquals(frame.windowTransparencySupported(), transparencyActivated.get()),
                    () -> assertEquals(transparencyActivated.get(), frame.windowTransparencyActive()),
                    () -> assertEquals(transparencyActivated.get() ? 0 : 255, frame.getBackground().getAlpha()));
            EdtDispatcher.executeAndWait(() -> frame.applyWindowTransparency(false));
            assertAll(
                    () -> assertFalse(frame.windowTransparencyActive()),
                    () -> assertEquals(255, frame.getBackground().getAlpha()));

            frame.open();
            assertTrue(pagesCreated.await(5L, TimeUnit.SECONDS));
            if (SystemInfo.isWindows) {
                assertWindowsTitlePaneButtonGeometry(rootPane);
                JButton squareCloseButton = Objects.requireNonNull(
                        findButtonByAccessibleName(rootPane, "Close"),
                        "square close title-pane button");
                Icon squareCloseIcon = squareCloseButton.getIcon();

                themeManager.update(ThemeBrightnessPreference.LIGHT, new SwingDesignTokens(18));
                assertWindowsTitlePaneButtonGeometry(rootPane);
                JButton roundedCloseButton = Objects.requireNonNull(
                        findButtonByAccessibleName(rootPane, "Close"),
                        "rounded close title-pane button");
                assertAll(
                        () -> assertEquals(36, UIManager.getInt("TitlePane.buttonArc")),
                        () -> assertNotSame(squareCloseIcon, roundedCloseButton.getIcon()));
            }
            if (!SystemInfo.isMacOS) {
                Rectangle buttonBounds = assertInstanceOf(
                        Rectangle.class,
                        rootPane.getClientProperty(FlatClientProperties.FULL_WINDOW_CONTENT_BUTTONS_BOUNDS));
                Rectangle launchBounds = javax.swing.SwingUtilities.convertRectangle(
                        frame.shellPanel().toolbar().launchButton().getParent(),
                        frame.shellPanel().toolbar().launchButton().getBounds(),
                        rootPane);
                int launchWindowControlsGap = buttonBounds.x
                        - launchBounds.x
                        - launchBounds.width;
                assertAll(
                        () -> assertEquals(
                                UIScale.scale(ShellToolbarPanel.LAUNCH_WINDOW_CONTROLS_GAP),
                                launchWindowControlsGap,
                                "launch=" + launchBounds + ", buttons=" + buttonBounds));
            }
            assertAll(
                    () -> assertTrue(frame.isResizable()),
                    () -> assertEquals(
                            frame.isUndecorated() && FlatNativeWindowBorder.isSupported(),
                            frame.undecoratedWindowResizerInstalled()),
                    () -> assertEquals(AppShellPanel.MINIMUM_WIDTH, frame.getMinimumSize().width),
                    () -> assertEquals(AppShellPanel.MINIMUM_HEIGHT, frame.getMinimumSize().height),
                    () -> assertTrue(frame.getWidth() >= AppShellPanel.PREFERRED_WIDTH),
                    () -> assertTrue(frame.getHeight() >= AppShellPanel.PREFERRED_HEIGHT),
                    () -> assertEquals(4, frame.getIconImages().size()),
                    () -> assertTrue(frame.isVisible()));
            if (frame.undecoratedWindowResizerInstalled()) {
                Dimension sizeBeforeDrag = frame.getSize();
                dragRightWindowEdge(frame, 32);
                assertAll(
                        () -> assertEquals(sizeBeforeDrag.width + 32, frame.getWidth()),
                        () -> assertEquals(sizeBeforeDrag.height, frame.getHeight()));
            }
            frame.hideWindow();
            assertFalse(frame.isVisible());
        } finally {
            EdtDispatcher.executeAndWait(frame::dispose);
            assertFalse(frame.undecoratedWindowResizerInstalled());
        }
    }

    /// Verifies the three visible Windows caption controls use centered squares with a trailing window margin.
    ///
    /// @param rootPane displayed shell root containing FlatLaf's title pane
    private static void assertWindowsTitlePaneButtonGeometry(JRootPane rootPane) {
        EdtDispatcher.executeAndWait(() -> {
            layoutRecursively(rootPane);
            for (String accessibleName : List.of("Iconify", "Maximize", "Close")) {
                JButton button = Objects.requireNonNull(
                        findButtonByAccessibleName(rootPane, accessibleName),
                        accessibleName + " title-pane button");
                Container parent = button.getParent();
                assertAll(
                        () -> assertEquals(36, button.getWidth(), accessibleName + " width"),
                        () -> assertEquals(36, button.getHeight(), accessibleName + " height"),
                        () -> assertEquals(
                                (parent.getHeight() - button.getHeight()) / 2,
                                button.getY(),
                                accessibleName + " vertical position"));
            }
            JButton closeButton = Objects.requireNonNull(
                    findButtonByAccessibleName(rootPane, "Close"),
                    "close title-pane button");
            Rectangle closeBounds = SwingUtilities.convertRectangle(
                    closeButton.getParent(),
                    closeButton.getBounds(),
                    rootPane);
            assertEquals(
                    UIScale.scale(UIManager.getInsets("TitlePane.buttonsMargins").right),
                    rootPane.getWidth() - closeBounds.x - closeBounds.width,
                    "close=" + closeBounds + ", rootPane=" + rootPane.getBounds());
        });
    }

    /// Finds one visible button by the stable FlatLaf accessibility label.
    ///
    /// @param component current component subtree
    /// @param accessibleName requested accessible label
    /// @return matching visible button, or `null` when this subtree contains none
    private static @Nullable JButton findButtonByAccessibleName(
            Component component,
            String accessibleName) {
        if (component instanceof JButton button
                && button.isVisible()
                && Objects.equals(button.getAccessibleContext().getAccessibleName(), accessibleName)) {
            return button;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                @Nullable JButton button = findButtonByAccessibleName(child, accessibleName);
                if (button != null) {
                    return button;
                }
            }
        }
        return null;
    }

    /// Synthesizes one real FlatLaf right-edge drag against a visible fallback resize hit target.
    ///
    /// @param frame visible shell frame
    /// @param horizontalDelta positive width change requested by the drag
    private static void dragRightWindowEdge(AppShellFrame frame, int horizontalDelta) {
        if (horizontalDelta <= 0) {
            throw new IllegalArgumentException("horizontalDelta must be positive");
        }
        EdtDispatcher.executeAndWait(() -> {
            JRootPane rootPane = frame.getRootPane();
            layoutRecursively(rootPane);
            Component edge = Objects.requireNonNull(
                    findRightResizeEdge(rootPane, rootPane),
                    "right resize edge");
            Point screenLocation = edge.getLocationOnScreen();
            int localX = Math.max(0, edge.getWidth() / 2);
            int localY = Math.max(0, edge.getHeight() / 2);
            int screenX = screenLocation.x + localX;
            int screenY = screenLocation.y + localY;
            long now = System.currentTimeMillis();

            edge.dispatchEvent(new MouseEvent(
                    edge,
                    MouseEvent.MOUSE_MOVED,
                    now,
                    0,
                    localX,
                    localY,
                    screenX,
                    screenY,
                    0,
                    false,
                    MouseEvent.NOBUTTON));
            edge.dispatchEvent(new MouseEvent(
                    edge,
                    MouseEvent.MOUSE_PRESSED,
                    now,
                    InputEvent.BUTTON1_DOWN_MASK,
                    localX,
                    localY,
                    screenX,
                    screenY,
                    1,
                    false,
                    MouseEvent.BUTTON1));
            edge.dispatchEvent(new MouseEvent(
                    edge,
                    MouseEvent.MOUSE_DRAGGED,
                    now,
                    InputEvent.BUTTON1_DOWN_MASK,
                    localX + horizontalDelta,
                    localY,
                    screenX + horizontalDelta,
                    screenY,
                    0,
                    false,
                    MouseEvent.NOBUTTON));
            edge.dispatchEvent(new MouseEvent(
                    edge,
                    MouseEvent.MOUSE_RELEASED,
                    now,
                    0,
                    localX + horizontalDelta,
                    localY,
                    screenX + horizontalDelta,
                    screenY,
                    1,
                    false,
                    MouseEvent.BUTTON1));
        });
    }

    /// Locates the vertical drag border occupying the right half of the root pane.
    ///
    /// @param component current component subtree
    /// @param rootPane coordinate target used to distinguish the right edge
    /// @return matching edge, or `null` when this subtree contains none
    private static @Nullable Component findRightResizeEdge(
            Component component,
            JRootPane rootPane) {
        if (component.getClass().getName().equals(
                "com.formdev.flatlaf.ui.FlatWindowResizer$DragBorderComponent")) {
            Rectangle bounds = SwingUtilities.convertRectangle(
                    component.getParent(),
                    component.getBounds(),
                    rootPane);
            if (bounds.height > bounds.width && bounds.getCenterX() > rootPane.getWidth() / 2.0) {
                return component;
            }
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                @Nullable Component edge = findRightResizeEdge(child, rootPane);
                if (edge != null) {
                    return edge;
                }
            }
        }
        return null;
    }

    /// Recursively applies the current real layout managers without replacing zero allocations by preferred sizes.
    ///
    /// @param container root or nested native-window container
    private static void layoutRecursively(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                layoutRecursively(nested);
            }
        }
    }
}

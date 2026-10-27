// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.dialog;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Robot;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Opt-in native-window smoke checks requiring an interactive desktop and no filesystem deletion.
@NotNullByDefault
@EnabledIfSystemProperty(named = "xyml.nativeDialogTests", matches = "true")
public final class SwingDeletionFallbackDialogNativeTest {
    /// Verifies actual option buttons, initial focus, Enter, Escape, and window-close cancellation.
    @Test
    public void verifiesNativeChoicesAndSafeDefault() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        Robot robot = new Robot();
        for (int choice : new int[]{0, 1, 2, -1, -2, -3}) {
            AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                AtomicInteger ticks = new AtomicInteger();
                Timer timer = new Timer(100, null);
                timer.addActionListener(event -> {
                    int tick = ticks.incrementAndGet();
                    for (Window window : Window.getWindows()) {
                        if (!(window instanceof JDialog dialog) || !dialog.isShowing()
                                || !dialog.getTitle().equals(i18n("button.remove"))) {
                            continue;
                        }
                        if (tick < 3) {
                            dialog.toFront();
                            continue;
                        }
                        timer.stop();
                        try {
                            JButton cancel = Objects.requireNonNull(dialog.getRootPane().getDefaultButton());
                            assertEquals(i18n("button.cancel"), cancel.getText());
                            assertEquals(cancel, dialog.getFocusOwner());
                            JButton retry = Objects.requireNonNull(findButton(dialog, i18n("button.retry")));
                            JButton permanent = Objects.requireNonNull(findButton(dialog, i18n("button.delete_permanently")));
                            Container row = Objects.requireNonNull(cancel.getParent());
                            assertEquals(row, retry.getParent());
                            assertEquals(row, permanent.getParent());
                            assertTrue(row.getComponentZOrder(retry) < row.getComponentZOrder(permanent));
                            assertTrue(row.getComponentZOrder(permanent) < row.getComponentZOrder(cancel));
                            if (choice == -1) {
                                dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));
                            } else if (choice == -2 || choice == -3) {
                                int key = choice == -2 ? KeyEvent.VK_ESCAPE : KeyEvent.VK_ENTER;
                                robot.keyPress(key);
                                robot.keyRelease(key);
                            } else {
                                (choice == 0 ? retry : choice == 1 ? permanent : cancel).doClick();
                            }
                        } catch (RuntimeException | Error problem) {
                            failure.set(problem);
                            dialog.dispose();
                        }
                    }
                });
                Timer timeout = new Timer(8000, event -> {
                    failure.set(new AssertionError("Native dialog did not finish within eight seconds"));
                    for (Window window : Window.getWindows()) {
                        if (window instanceof JDialog dialog && dialog.getTitle().equals(i18n("button.remove"))) {
                            dialog.dispose();
                        }
                    }
                });
                timeout.setRepeats(false);
                timer.start();
                timeout.start();
                try {
                    DeletionFallbackDecision expected = choice == 0 ? DeletionFallbackDecision.RETRY_RECYCLE_BIN
                            : choice == 1 ? DeletionFallbackDecision.DELETE_PERMANENTLY : DeletionFallbackDecision.CANCEL;
                    assertEquals(expected, SwingDeletionFallbackDialog.choose(new JPanel(), i18n("button.remove"),
                            i18n("button.remove.confirm")));
                } finally {
                    timer.stop();
                    timeout.stop();
                }
            });
            @Nullable Throwable problem = failure.get();
            if (problem != null) {
                throw new AssertionError("Native fallback dialog failed", problem);
            }
        }
    }

    /// Locates an actual option button without relying on platform-specific component hierarchy depth.
    ///
    /// @param container native dialog subtree
    /// @param text existing localized option label
    /// @return matching button, or null when absent
    private static @Nullable JButton findButton(Container container, String text) {
        for (Component child : container.getComponents()) {
            if (child instanceof JButton button && text.equals(button.getText())) {
                return button;
            }
            if (child instanceof Container nested) {
                @Nullable JButton button = findButton(nested, text);
                if (button != null) {
                    return button;
                }
            }
        }
        return null;
    }
}

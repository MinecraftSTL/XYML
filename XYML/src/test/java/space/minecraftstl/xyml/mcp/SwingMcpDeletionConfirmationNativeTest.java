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
package space.minecraftstl.xyml.mcp;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.game.GameInstanceID;

import javax.imageio.ImageIO;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// Desktop smoke tests interact only with this test JVM's own dialogs and never move or delete host files.
@NotNullByDefault
final class SwingMcpDeletionConfirmationNativeTest {
    /// Exercises both production modal dialogs and verifies permanent deletion never offers an opt-out checkbox.
    @Test
    void productionDialogsKeepPermanentDeletionIndependent() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Native Swing dialogs require a desktop session");
        AtomicInteger disabled = new AtomicInteger();
        SwingMcpDeletionConfirmation confirmation = new SwingMcpDeletionConfirmation(
                kind -> true, () -> true, kind -> disabled.incrementAndGet());
        McpDeletionConfirmation.DeletionRequest request = new McpDeletionConfirmation.DeletionRequest(
                McpDeletionConfirmation.DeletionKind.SCHEMATICS, new GameInstanceID("MCP desktop test"), 2);
        answerDialog(() -> confirmation.confirm(request), true, "ordinary");
        assertEquals(1, disabled.get());
        answerDialog(() -> confirmation.confirmPermanent(request), false, "permanent");
        assertEquals(1, disabled.get());
    }

    /// Inspects and approves a real modal dialog using its own Swing components, without global mouse input.
    /// @param operation production confirmation invocation
    /// @param optOut whether the ordinary opt-out control must be present
    /// @param imageName screenshot name below build/reports/mcp-ui
    /// @throws Exception when the dialog fails, times out, or its screenshot cannot be written
    private static void answerDialog(BooleanSupplier operation, boolean optOut, String imageName) throws Exception {
        AtomicReference<@Nullable Throwable> error = new AtomicReference<>();
        AtomicReference<@Nullable BufferedImage> screenshot = new AtomicReference<>();
        Timer timer = new Timer(40, event -> {
            for (Window window : Window.getWindows()) {
                if (!(window instanceof JDialog dialog) || !dialog.isShowing()) {
                    continue;
                }
                @Nullable JOptionPane pane = find(dialog, JOptionPane.class);
                if (pane == null) {
                    continue;
                }
                try {
                    @Nullable JTextArea warning = find(dialog, JTextArea.class);
                    assertNotNull(warning);
                    assertTrue(warning.getLineWrap());
                    assertFalse(warning.isEditable());
                    @Nullable JCheckBox checkbox = find(dialog, JCheckBox.class);
                    if (optOut) {
                        assertNotNull(checkbox);
                        checkbox.setSelected(true);
                    } else {
                        assertNull(checkbox);
                    }
                    BufferedImage image = new BufferedImage(
                            dialog.getWidth(), dialog.getHeight(), BufferedImage.TYPE_INT_ARGB);
                    java.awt.Graphics2D graphics = image.createGraphics();
                    try {
                        dialog.paintAll(graphics);
                    } finally {
                        graphics.dispose();
                    }
                    screenshot.set(image);
                } catch (Throwable failure) {
                    error.set(failure);
                } finally {
                    pane.setValue(JOptionPane.OK_OPTION);
                }
            }
        });
        CompletableFuture<Boolean> result = CompletableFuture.supplyAsync(operation::getAsBoolean);
        SwingUtilities.invokeAndWait(timer::start);
        try {
            assertTrue(result.get(10, TimeUnit.SECONDS));
            if (error.get() != null) {
                throw new AssertionError("Production confirmation dialog failed", error.get());
            }
            @Nullable BufferedImage image = screenshot.get();
            assertNotNull(image);
            Path output = Path.of("build", "reports", "mcp-ui", imageName + ".png");
            Files.createDirectories(output.getParent());
            assertTrue(ImageIO.write(image, "png", output.toFile()));
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                timer.stop();
                for (Window window : Window.getWindows()) {
                    if (window instanceof JDialog dialog && find(dialog, JOptionPane.class) != null) {
                        dialog.dispose();
                    }
                }
            });
        }
    }

    /// Finds a component only within the test JVM's own component tree.
    /// @param component root component
    /// @param type requested component type
    /// @param <T> component type
    /// @return matching child, or null when absent
    private static <T extends Component> @Nullable T find(Component component, Class<T> type) {
        if (type.isInstance(component)) {
            return type.cast(component);
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                @Nullable T found = find(child, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}

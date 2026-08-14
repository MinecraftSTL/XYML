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

import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.TransferHandler;
import javax.swing.plaf.UIResource;
import java.awt.GraphicsEnvironment;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// Verifies native target precedence without replacing Swing clipboard or custom transfer handlers.
@NotNullByDefault
final class ShellDefaultDropTargetSuppressorTest {
    /// Look-and-Feel targets are removed recursively while custom and post-close targets remain intact.
    @Test
    void suppressesCurrentAndDynamicDefaultTargetsOnly() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Native Swing drop targets require a graphical environment");

        EdtDispatcher.executeAndWait(() -> {
            JPanel root = new JPanel();
            JTextField currentField = new JTextField();
            root.add(currentField);
            TransferHandler currentHandler = Objects.requireNonNull(currentField.getTransferHandler());
            assertInstanceOf(UIResource.class, currentHandler);
            assertInstanceOf(UIResource.class, Objects.requireNonNull(currentField.getDropTarget()));

            ShellDefaultDropTargetSuppressor suppressor = ShellDefaultDropTargetSuppressor.install(root);
            assertSame(currentHandler, currentField.getTransferHandler());
            assertNull(currentField.getDropTarget());

            JTextField dynamicField = new JTextField();
            TransferHandler dynamicHandler = Objects.requireNonNull(dynamicField.getTransferHandler());
            assertNotNull(dynamicField.getDropTarget());
            root.add(dynamicField);
            assertSame(dynamicHandler, dynamicField.getTransferHandler());
            assertNull(dynamicField.getDropTarget());

            JPanel customTarget = new JPanel();
            ShellFileDropHandler.RouteRegistration customRoute = ShellFileDropHandler.registerText(
                    customTarget,
                    text -> true,
                    text -> { });
            root.add(customTarget);
            assertNotNull(customTarget.getDropTarget());

            suppressor.close();
            JTextField postCloseField = new JTextField();
            root.add(postCloseField);
            assertNotNull(postCloseField.getDropTarget());
            customRoute.close();
        });
    }
}

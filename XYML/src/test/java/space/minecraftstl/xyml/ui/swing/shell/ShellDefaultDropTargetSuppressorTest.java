// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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

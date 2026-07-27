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
package space.minecraftstl.xyml.ui.swing.page.nbt;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.Serial;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/// Hosts one temporary NBT editor in a modeless native window owned by the launcher frame.
///
/// User-initiated closure delegates to [NBTEditorPanel#requestClose()] so unsaved changes remain
/// protected. Forced lifecycle closure calls [NBTEditorPanel#close()] before native disposal,
/// cancelling pending work and preventing late UI callbacks.
@NotNullByDefault
final class SwingNBTEditorDialog extends JDialog implements SwingNBTEditorLauncher.EditorWindow {
    /// Serialization identifier for the Swing window superclass contract.
    @Serial
    private static final long serialVersionUID = 1L;

    /// Stable minimum editor width.
    private static final int MINIMUM_WIDTH = 900;

    /// Stable minimum editor height.
    private static final int MINIMUM_HEIGHT = 560;

    /// Initial editor width.
    private static final int PREFERRED_WIDTH = 1040;

    /// Initial editor height.
    private static final int PREFERRED_HEIGHT = 700;

    /// Action-map key for the Escape close command.
    private static final String CLOSE_ACTION_KEY = "close-nbt-editor";

    /// Embedded editor page that owns document state and asynchronous work.
    private final NBTEditorPanel editorPanel;

    /// Launcher callback used to release the current-window reference.
    private final Consumer<SwingNBTEditorLauncher.EditorWindow> closedObserver;

    /// Prevents repeated panel cleanup and native disposal.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Creates a modeless editor window on the EDT without opening a file.
    ///
    /// @param owner owning launcher frame
    /// @param ioExecutor caller-owned executor for NBT and bundled-icon I/O
    /// @param strings stable localized window text
    /// @param closedObserver callback invoked once after native disposal is attempted
    SwingNBTEditorDialog(
            Frame owner,
            Executor ioExecutor,
            NBTEditorStrings strings,
            Consumer<SwingNBTEditorLauncher.EditorWindow> closedObserver) {
        super(
                Objects.requireNonNull(owner, "owner"),
                Objects.requireNonNull(strings, "strings").title(),
                ModalityType.MODELESS);
        EdtDispatcher.requireEventDispatchThread();
        this.closedObserver = Objects.requireNonNull(closedObserver, "closedObserver");
        editorPanel = new NBTEditorPanel(
                Objects.requireNonNull(ioExecutor, "ioExecutor"),
                this::dispose);

        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setContentPane(editorPanel);
        setMinimumSize(new Dimension(MINIMUM_WIDTH, MINIMUM_HEIGHT));
        setPreferredSize(new Dimension(PREFERRED_WIDTH, PREFERRED_HEIGHT));
        configureCloseCommands();
        pack();
        setLocationRelativeTo(owner);
    }

    /// Opens or replaces the document represented by this editor.
    ///
    /// @param source lexically supported NBT source
    @Override
    public void open(Path source) {
        EdtDispatcher.requireEventDispatchThread();
        if (!closed.get()) {
            editorPanel.open(Objects.requireNonNull(source, "source"));
        }
    }

    /// Reveals the modeless window or moves an existing window to the foreground.
    @Override
    public void showOrFocus() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get()) {
            return;
        }
        if (!isVisible()) {
            setVisible(true);
        } else {
            toFront();
            requestFocus();
        }
    }

    /// Requests user-confirmed closure of the current document.
    @Override
    public void requestClose() {
        EdtDispatcher.requireEventDispatchThread();
        if (!closed.get()) {
            editorPanel.requestClose();
        }
    }

    /// Releases the editor and native peer from any caller thread.
    @Override
    public void close() {
        dispose();
    }

    /// Disposes panel state and the native peer exactly once on the EDT.
    @Override
    public void dispose() {
        if (!SwingUtilities.isEventDispatchThread()) {
            EdtDispatcher.executeAndWait(this::dispose);
            return;
        }
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            editorPanel.close();
        } finally {
            try {
                super.dispose();
            } finally {
                closedObserver.accept(this);
            }
        }
    }

    /// Installs window-manager and Escape commands that preserve dirty-state confirmation.
    private void configureCloseCommands() {
        addWindowListener(new WindowAdapter() {
            /// Routes a native close request through the editor confirmation boundary.
            ///
            /// @param event native window event
            @Override
            public void windowClosing(WindowEvent event) {
                Objects.requireNonNull(event, "event");
                requestClose();
            }
        });
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                CLOSE_ACTION_KEY);
        getRootPane().getActionMap().put(CLOSE_ACTION_KEY, new AbstractAction() {
            /// Serialization identifier for the Swing action superclass contract.
            @Serial
            private static final long serialVersionUID = 1L;

            /// Routes Escape through the editor confirmation boundary.
            ///
            /// @param event originating key action
            @Override
            public void actionPerformed(ActionEvent event) {
                Objects.requireNonNull(event, "event");
                requestClose();
            }
        });
    }
}

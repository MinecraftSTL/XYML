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
package space.minecraftstl.xyml.ui.swing.page.instances.importing;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings;

import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.WindowConstants;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/// Modeless native window hosting one cancellable instance JSON import panel.
@NotNullByDefault
final class SwingInstanceJsonImportDialog extends JDialog
        implements SwingInstanceJsonImportLauncher.ImportWindow {
    /// Stable content panel owning task lifecycle and cancellation.
    private final InstanceJsonImportPanel panel;

    /// Terminal disposal observer used to release launcher window identity.
    private final Consumer<SwingInstanceJsonImportLauncher.ImportWindow> closedObserver;

    /// Whether terminal disposal has already released the panel and observer.
    private boolean closed;

    /// Creates a modeless import dialog on the Swing event-dispatch thread.
    ///
    /// @param owner launcher frame
    /// @param service deferred repository import service
    /// @param strings localized workflow text
    /// @param taskProgressStrings localized task lifecycle text
    /// @param animator optional shared progress animator
    /// @param progressAnimationDuration non-negative progress animation duration
    /// @param closedObserver terminal disposal observer
    SwingInstanceJsonImportDialog(
            JFrame owner,
            InstanceJsonImportService service,
            InstanceJsonImportStrings strings,
            TaskProgressStrings taskProgressStrings,
            @Nullable SwingAnimator animator,
            Duration progressAnimationDuration,
            Consumer<SwingInstanceJsonImportLauncher.ImportWindow> closedObserver) {
        super(Objects.requireNonNull(owner, "owner"), strings.dialogTitle(), false);
        EdtDispatcher.requireEventDispatchThread();
        this.closedObserver = Objects.requireNonNull(closedObserver, "closedObserver");
        panel = new InstanceJsonImportPanel(
                Objects.requireNonNull(service, "service"),
                Objects.requireNonNull(strings, "strings"),
                Objects.requireNonNull(taskProgressStrings, "taskProgressStrings"),
                animator,
                Objects.requireNonNull(progressAnimationDuration, "progressAnimationDuration"));
        setName("instanceJsonImportDialog");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setContentPane(panel);
        setMinimumSize(new Dimension(620, 380));
        setPreferredSize(new Dimension(700, 440));
        pack();
        setLocationRelativeTo(owner);
        addWindowListener(new WindowAdapter() {
            /// Releases task resources after native terminal disposal.
            ///
            /// @param event native window event
            @Override
            public void windowClosed(WindowEvent event) {
                Objects.requireNonNull(event, "event");
                finishClose();
            }
        });
    }

    /// Replaces the selected source while the hosted workflow is idle.
    ///
    /// @param source normalized JSON source
    @Override
    public void open(Path source) {
        EdtDispatcher.requireEventDispatchThread();
        if (!closed) {
            panel.open(Objects.requireNonNull(source, "source"));
        }
    }

    /// Reveals this modeless window or focuses its existing native peer.
    @Override
    public void showOrFocus() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            return;
        }
        if (!isVisible()) {
            setVisible(true);
        }
        toFront();
        requestFocus();
    }

    /// Forces terminal disposal and cancellation of a running import.
    @Override
    public void close() {
        EdtDispatcher.requireEventDispatchThread();
        if (!closed) {
            dispose();
            finishClose();
        }
    }

    /// Releases the panel and reports terminal disposal exactly once.
    private void finishClose() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            return;
        }
        closed = true;
        panel.close();
        closedObserver.accept(this);
    }
}

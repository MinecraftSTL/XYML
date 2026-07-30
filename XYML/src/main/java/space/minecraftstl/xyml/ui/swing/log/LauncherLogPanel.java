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
package space.minecraftstl.xyml.ui.swing.log;

import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;

import javax.swing.JButton;
import javax.swing.JPanel;
import java.util.Objects;
import java.util.concurrent.CompletionException;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Renders the restored launcher-log actions inside the settings center.
///
/// Construction neither enumerates log files nor starts archive work. File access begins only after an explicit
/// user action, and late completion callbacks are ignored after this panel is closed.
@NotNullByDefault
public final class LauncherLogPanel extends JPanel implements AutoCloseable {
    /// Service that owns archive generation and cancellation.
    private final LauncherLogExportService exportService;

    /// Desktop and dialog interaction boundary.
    private final LauncherLogPanelInteractions interactions;

    /// Native command that opens the active live-log directory.
    private final JButton revealButton = new JButton(i18n("settings.launcher.launcher_log.reveal"));

    /// Native command that starts one archive export.
    private final JButton exportButton = new JButton(i18n("settings.launcher.launcher_log.export"));

    /// Whether an archive export is currently pending.
    private boolean exporting;

    /// Whether this panel has released its service and no longer accepts user actions.
    private boolean closed;

    /// Creates the production launcher-log panel backed by the shared launcher logger.
    ///
    /// @return configured settings-center log controls
    public static LauncherLogPanel createForCurrentLauncher() {
        return new LauncherLogPanel(
                LauncherLogExportService.createForCurrentLauncher(),
                new DefaultLauncherLogPanelInteractions());
    }

    /// Creates one testable launcher-log action row on the event dispatch thread.
    ///
    /// @param exportService archive service owned by this panel
    /// @param interactions native desktop and dialog interactions
    public LauncherLogPanel(LauncherLogExportService exportService, LauncherLogPanelInteractions interactions) {
        super(new MigLayout("insets 0, fillx", "[grow,fill]8[]", "[]"));
        EdtDispatcher.requireEventDispatchThread();
        this.exportService = Objects.requireNonNull(exportService, "exportService");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        configureComponents();
        refreshControls();
    }

    /// Returns whether an explicit export is currently pending for focused integration checks.
    ///
    /// @return true while archive work is pending
    public boolean isExporting() {
        EdtDispatcher.requireEventDispatchThread();
        return exporting;
    }

    /// Returns the export command for focused integration checks.
    ///
    /// @return native export button
    JButton exportButton() {
        EdtDispatcher.requireEventDispatchThread();
        return exportButton;
    }

    /// Returns the live-log directory command for focused integration checks.
    ///
    /// @return native reveal button
    JButton revealButton() {
        EdtDispatcher.requireEventDispatchThread();
        return revealButton;
    }

    /// Cancels pending archive work and disables this detached settings fragment.
    @Override
    public void close() {
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (!closed) {
                closed = true;
                exportService.close();
                refreshControls();
            }
        });
    }

    /// Configures the two restored diagnostic commands without initiating any file work.
    private void configureComponents() {
        setOpaque(false);
        revealButton.setName("launcherLogReveal");
        exportButton.setName("launcherLogExport");
        revealButton.addActionListener(event -> revealLogDirectory());
        exportButton.addActionListener(event -> exportLogs());
        add(revealButton, "split 2, alignx right");
        add(exportButton);
    }

    /// Opens the active logger directory only when the logger has an on-disk destination.
    private void revealLogDirectory() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || exporting) {
            return;
        }
        exportService.logDirectory().ifPresent(directory -> interactions.revealLogDirectory(this, directory));
        refreshControls();
    }

    /// Starts archive generation and delivers a result only while this settings panel remains live.
    private void exportLogs() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || exporting) {
            return;
        }
        exporting = true;
        refreshControls();
        exportService.export().whenComplete((result, failure) -> SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (closed) {
                return;
            }
            exporting = false;
            refreshControls();
            if (failure == null) {
                java.nio.file.Path exportFile = Objects.requireNonNull(result, "export result");
                interactions.showExportSuccess(this, exportFile);
                interactions.revealExport(this, exportFile);
            } else {
                interactions.showExportFailure(this, unwrapFailure(failure));
            }
        }));
    }

    /// Updates command availability from the current lifecycle and active logger state.
    private void refreshControls() {
        EdtDispatcher.requireEventDispatchThread();
        revealButton.setEnabled(!closed && !exporting && exportService.logDirectory().isPresent());
        exportButton.setEnabled(!closed && !exporting);
    }

    /// Removes one completion wrapper while preserving the original diagnostic cause.
    ///
    /// @param failure completion callback failure
    /// @return underlying failure when one is present
    private static Throwable unwrapFailure(Throwable failure) {
        Throwable current = Objects.requireNonNull(failure, "failure");
        while (current instanceof CompletionException && current.getCause() != null) {
            current = Objects.requireNonNull(current.getCause(), "completion failure cause");
        }
        return current;
    }
}

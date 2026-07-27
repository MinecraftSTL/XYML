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
package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings;

import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import java.awt.BorderLayout;
import java.time.Duration;
import java.util.Objects;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Combines remote world discovery with the existing local world-archive workflow.
///
/// The remote child is selected initially and stays network-idle until Search is pressed. The local
/// child does not resolve the selected instance or enumerate saves until its nested tab is visible.
@NotNullByDefault
public final class WorldDownloadPanel extends JPanel implements AutoCloseable {
    /// Native remote world catalog using CurseForge's WORLD repository and save-as destinations.
    private final RemoteAddonCatalogPanel remoteCatalog;

    /// Existing local archive import and selected-instance world catalog.
    private final WorldArchiveDownloadPanel localArchivePanel;

    /// Nested world workflow selector exposed for keyboard navigation and focused verification.
    private final JTabbedPane workflowTabs = new JTabbedPane();

    /// Whether this composite has rejected further activation.
    private volatile boolean closed;

    /// Creates the production remote and local world workflows without network or save enumeration.
    ///
    /// @param taskProgressStrings localized task lifecycle controls
    /// @param animator optional shared determinate-progress animator
    /// @param progressAnimationDuration non-negative determinate-progress animation duration
    public WorldDownloadPanel(
            TaskProgressStrings taskProgressStrings,
            @Nullable SwingAnimator animator,
            Duration progressAnimationDuration) {
        this(
                new RemoteAddonCatalogPanel(
                        RemoteAddonCatalogKind.WORLD,
                        new SwingRemoteWorldSaveTargetResolver(),
                        RemoteAddonCatalogStrings.launcherLocalized(RemoteAddonCatalogKind.WORLD),
                        Objects.requireNonNull(taskProgressStrings, "taskProgressStrings"),
                        animator,
                        Objects.requireNonNull(progressAnimationDuration, "progressAnimationDuration")),
                new WorldArchiveDownloadPanel());
    }

    /// Creates a composite with explicit owned children for deterministic tests.
    ///
    /// @param remoteCatalog remote world catalog child
    /// @param localArchivePanel local archive child
    WorldDownloadPanel(
            RemoteAddonCatalogPanel remoteCatalog,
            WorldArchiveDownloadPanel localArchivePanel) {
        super(new BorderLayout());
        EdtDispatcher.requireEventDispatchThread();
        this.remoteCatalog = Objects.requireNonNull(remoteCatalog, "remoteCatalog");
        this.localArchivePanel = Objects.requireNonNull(localArchivePanel, "localArchivePanel");

        setName("downloadsWorldWorkflows");
        setOpaque(false);
        workflowTabs.setName("downloadsWorldWorkflowTabs");
        workflowTabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        workflowTabs.addTab(i18n("swing.remote_world.tab.remote"), remoteCatalog);
        workflowTabs.addTab(i18n("swing.remote_world.tab.local"), localArchivePanel);
        workflowTabs.addChangeListener(event -> activateSelectedWorkflow());
        add(workflowTabs, BorderLayout.CENTER);
    }

    /// Returns the nested remote/local workflow selector.
    ///
    /// @return stable world workflow tab host
    public JTabbedPane workflowTabs() {
        return workflowTabs;
    }

    /// Activates only the currently visible local workflow; remote discovery remains command-driven.
    public void activate() {
        EdtDispatcher.requireEventDispatchThread();
        activateSelectedWorkflow();
    }

    /// Closes both owned workflows and rejects later local activation.
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        remoteCatalog.close();
        localArchivePanel.close();
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> workflowTabs.setEnabled(false));
    }

    /// Starts local world enumeration only when the user has selected the local archive tab.
    private void activateSelectedWorkflow() {
        EdtDispatcher.requireEventDispatchThread();
        if (!closed && workflowTabs.getSelectedComponent() == localArchivePanel) {
            localArchivePanel.activate();
        }
    }
}

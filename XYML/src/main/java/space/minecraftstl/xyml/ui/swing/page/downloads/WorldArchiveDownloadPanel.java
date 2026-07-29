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

import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.setting.GameDirectoryManager;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;
import space.minecraftstl.xyml.ui.swing.page.instances.management.worlds.WorldCatalogPanel;
import space.minecraftstl.xyml.ui.swing.page.instances.management.worlds.WorldQuickPlayActions;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Font;
import java.util.Objects;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Hosts the native local-world archive workflow inside the download center.
///
/// Construction deliberately does not resolve the selected repository or enumerate saves. The host
/// calls [#activate()] only when the Worlds category becomes visible, at which point the embedded
/// [WorldCatalogPanel] performs its existing viewport-driven shallow scan and safe archive import.
@NotNullByDefault
public final class WorldArchiveDownloadPanel extends JPanel implements AutoCloseable {
    /// Lazily replaced content area containing either guidance or the selected-instance world page.
    private final JPanel content = new JPanel(new BorderLayout());

    /// Identifies the currently bound launcher instance beside the page title.
    private final JLabel targetLabel = new JLabel();

    /// Explains why the workspace has no selected instance-specific catalog yet.
    private final JLabel emptyLabel = new JLabel();

    /// Rebinds the page after the user changes the selected launcher instance.
    private final JButton reloadTargetButton = new JButton();

    /// Embedded instance-specific world page, or null before the first successful activation.
    private @Nullable WorldCatalogPanel worldCatalog;

    /// Prevents construction or replacement after lifecycle teardown begins.
    private boolean closed;

    /// Creates a zero-I/O native world archive destination on the Swing event dispatch thread.
    public WorldArchiveDownloadPanel() {
        super(new MigLayout("insets 0, fill, wrap 1", "[grow,fill]", "[]8[grow,fill]"));
        EdtDispatcher.requireEventDispatchThread();

        JPanel heading = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill][]8[]", "[40!]"));
        heading.setOpaque(false);
        JLabel title = new JLabel(i18n("world"));
        title.setName("downloadsWorldArchiveTitle");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22.0F));
        heading.add(title, "growx");
        targetLabel.setName("downloadsWorldArchiveTarget");
        heading.add(targetLabel);
        reloadTargetButton.setName("downloadsWorldArchiveReloadTarget");
        reloadTargetButton.setText(i18n("button.refresh"));
        reloadTargetButton.setToolTipText(i18n("instance.switch"));
        reloadTargetButton.addActionListener(event -> reloadTarget());
        heading.add(reloadTargetButton, "h 40!");
        add(heading, "growx");

        content.setName("downloadsWorldArchiveContent");
        content.setOpaque(false);
        emptyLabel.setName("downloadsWorldArchiveEmpty");
        emptyLabel.setHorizontalAlignment(JLabel.CENTER);
        content.add(emptyLabel, BorderLayout.CENTER);
        add(content, "grow");

        showSelectionRequired();
    }

    /// Resolves the current selected instance and begins its lazy shallow world index once.
    ///
    /// Repeated calls retain the existing bound catalog. Users explicitly request a rebinding
    /// through the refresh-target command after changing their launcher-wide selection.
    public void activate() {
        EdtDispatcher.requireEventDispatchThread();
        if (!closed && worldCatalog == null) {
            reloadTarget();
        }
    }

    /// Closes the currently bound sparse catalog and rejects further target resolution.
    @Override
    public void close() {
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (closed) {
                return;
            }
            closed = true;
            reloadTargetButton.setEnabled(false);
            @Nullable WorldCatalogPanel currentCatalog = worldCatalog;
            worldCatalog = null;
            if (currentCatalog != null) {
                currentCatalog.close();
            }
            content.removeAll();
            targetLabel.setText("");
            emptyLabel.setText("");
            content.add(emptyLabel, BorderLayout.CENTER);
            content.revalidate();
            content.repaint();
        });
    }

    /// Replaces the hosted page with the current selected instance's zero-network world catalog.
    private void reloadTarget() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            return;
        }

        @Nullable WorldCatalogPanel currentCatalog = worldCatalog;
        worldCatalog = null;
        if (currentCatalog != null) {
            currentCatalog.close();
        }
        content.removeAll();

        @Nullable SelectedInstance selected = resolveSelectedInstance();
        if (selected == null) {
            showSelectionRequired();
        } else {
            targetLabel.setText(selected.instanceId());
            WorldCatalogPanel createdCatalog = new WorldCatalogPanel(
                    selected.repository(),
                    selected.instanceId(),
                    Schedulers.io(),
                    WorldQuickPlayActions.unavailable());
            worldCatalog = createdCatalog;
            content.add(createdCatalog, BorderLayout.CENTER);
            createdCatalog.activate();
        }
        content.revalidate();
        content.repaint();
    }

    /// Shows guidance when the launcher currently has no usable selected instance.
    private void showSelectionRequired() {
        targetLabel.setText("");
        emptyLabel.setText(i18n("instance.switch"));
        content.add(emptyLabel, BorderLayout.CENTER);
    }

    /// Snapshots the currently selected repository and stable instance identifier without I/O.
    ///
    /// @return selected repository identity, or null when launcher state has no usable target
    private static @Nullable SelectedInstance resolveSelectedInstance() {
        try {
            XYMLGameRepository repository = GameDirectoryManager.getSelectedRepository();
            @Nullable String instanceId = repository.getSelectedInstance();
            if (instanceId == null || instanceId.isBlank()) {
                return null;
            }
            return new SelectedInstance(repository, instanceId);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /// Immutable current target captured before constructing an instance-specific child page.
    ///
    /// @param repository selected repository owning the instance
    /// @param instanceId stable non-blank selected instance identifier
    @NotNullByDefault
    private record SelectedInstance(XYMLGameRepository repository, String instanceId) {
        /// Validates the captured launcher selection.
        private SelectedInstance {
            repository = Objects.requireNonNull(repository, "repository");
            instanceId = Objects.requireNonNull(instanceId, "instanceId");
            if (instanceId.isBlank()) {
                throw new IllegalArgumentException("instanceId must not be blank");
            }
        }
    }
}

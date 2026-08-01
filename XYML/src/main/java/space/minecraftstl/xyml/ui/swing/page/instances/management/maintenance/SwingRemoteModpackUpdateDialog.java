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
package space.minecraftstl.xyml.ui.swing.page.instances.management.maintenance;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.page.downloads.ExistingInstanceRemoteModpackUpdateLauncher;
import space.minecraftstl.xyml.ui.swing.page.downloads.RemoteModpackCatalogPanel;
import space.minecraftstl.xyml.ui.swing.page.downloads.RemoteModpackCatalogStrings;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings;

import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Window;
import java.time.Duration;
import java.util.Objects;

/// Opens the native remote-modpack catalog as a bounded update workflow for one existing instance.
@NotNullByDefault
final class SwingRemoteModpackUpdateDialog {
    /// Prevents construction of the stateless dialog utility.
    private SwingRemoteModpackUpdateDialog() {
    }

    /// Shows a resizable modal catalog and releases its task lifecycle when the window closes.
    ///
    /// @param owner maintenance component owning the native dialog
    /// @param repository repository containing the fixed existing instance
    /// @param instanceId existing modpack update target
    /// @param taskProgressStrings localized task progress text
    /// @param animator optional shared motion-aware animator
    /// @param progressAnimationDuration non-negative progress animation duration
    static void show(
            Component owner,
            XYMLGameRepository repository,
            GameInstanceID instanceId,
            TaskProgressStrings taskProgressStrings,
            @Nullable SwingAnimator animator,
            Duration progressAnimationDuration) {
        EdtDispatcher.requireEventDispatchThread();
        Component resolvedOwner = Objects.requireNonNull(owner, "owner");
        XYMLGameRepository resolvedRepository = Objects.requireNonNull(repository, "repository");
        GameInstanceID resolvedInstanceId = Objects.requireNonNull(instanceId, "instanceId");
        RemoteModpackCatalogStrings strings = RemoteModpackCatalogStrings.launcherUpdateLocalized();
        RemoteModpackCatalogPanel panel = new RemoteModpackCatalogPanel(
                resolvedInstanceId,
                new ExistingInstanceRemoteModpackUpdateLauncher(resolvedRepository, resolvedInstanceId),
                strings,
                Objects.requireNonNull(taskProgressStrings, "taskProgressStrings"),
                animator,
                Objects.requireNonNull(progressAnimationDuration, "progressAnimationDuration"));
        @Nullable Window ancestor = SwingUtilities.getWindowAncestor(resolvedOwner);
        JDialog dialog = new JDialog(ancestor, strings.pageTitle(), Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setResizable(true);
        panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        dialog.setContentPane(panel);
        dialog.setMinimumSize(new Dimension(760, 520));
        dialog.setSize(new Dimension(1_040, 720));
        dialog.setLocationRelativeTo(resolvedOwner);
        try {
            dialog.setVisible(true);
        } finally {
            panel.close();
            dialog.dispose();
        }
    }
}

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
package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.XYMLGameRepository;

import javax.swing.JDialog;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Window;
import java.util.Objects;
import java.util.concurrent.Executor;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Opens the manual instance-configuration migration controls in a separate Swing dialog.
@NotNullByDefault
public final class InstanceConfigManualMigrationDialog {
    private InstanceConfigManualMigrationDialog() {
    }

    /// Opens a modeless migration dialog owned by the current instance settings surface.
    ///
    /// @param parent instance settings component used to resolve the dialog owner
    /// @param repository repository containing the target instance
    /// @param instanceId target instance identifier
    /// @param executor executor used for migration I/O
    public static void show(
            Component parent,
            XYMLGameRepository repository,
            GameInstanceID instanceId,
            Executor executor) {
        Window owner = parent instanceof Window window
                ? window
                : javax.swing.SwingUtilities.getWindowAncestor(parent);
        JDialog dialog = new JDialog(
                owner,
                i18n("settings.instance_config_migration.manual.title"),
                Dialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setContentPane(new InstanceConfigManualMigrationPanel(
                Objects.requireNonNull(repository, "repository"),
                Objects.requireNonNull(instanceId, "instanceId"),
                Objects.requireNonNull(executor, "executor")));
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }
}

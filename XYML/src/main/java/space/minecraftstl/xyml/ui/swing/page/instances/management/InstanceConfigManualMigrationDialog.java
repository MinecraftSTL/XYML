// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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

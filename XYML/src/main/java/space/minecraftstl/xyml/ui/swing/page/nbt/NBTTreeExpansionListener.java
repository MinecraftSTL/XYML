// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.nbt;

import org.jetbrains.annotations.NotNullByDefault;

import javax.swing.JTree;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.ExpandVetoException;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/// Reveals lazy root children exactly when a real expansion begins.
@NotNullByDefault
final class NBTTreeExpansionListener implements TreeWillExpandListener {
    /// Tree whose model may be lazy.
    private final JTree tree;

    /// Reports whether a replacement model is being installed.
    private final BooleanSupplier installingModel;

    /// Creates one expansion listener.
    NBTTreeExpansionListener(JTree tree, BooleanSupplier installingModel) {
        this.tree = Objects.requireNonNull(tree, "tree");
        this.installingModel = Objects.requireNonNull(installingModel, "installingModel");
    }

    /// Reveals root children before Swing enumerates the expanding path.
    @Override
    public void treeWillExpand(TreeExpansionEvent event) throws ExpandVetoException {
        TreeExpansionEvent expansion = Objects.requireNonNull(event, "event");
        Object component = expansion.getPath().getLastPathComponent();
        if (!installingModel.getAsBoolean()
                && expansion.getSource() == tree
                && tree.getModel() instanceof NBTLazyTreeModel model
                && component == model.getRoot()) {
            model.revealRootChildren();
        }
    }

    /// Accepts collapse without changing model visibility.
    @Override
    public void treeWillCollapse(TreeExpansionEvent event) throws ExpandVetoException {
        Objects.requireNonNull(event, "event");
    }
}

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
package space.minecraftstl.xyml.ui.swing.shell;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;
import space.minecraftstl.xyml.ui.swing.choice.ChoiceListEntry;
import space.minecraftstl.xyml.ui.swing.choice.ViewportChoiceList;
import space.minecraftstl.xyml.ui.swing.page.instances.InstanceListCellRenderer;
import space.minecraftstl.xyml.ui.swing.page.instances.InstanceListItem;
import space.minecraftstl.xyml.ui.swing.page.instances.InstancesModel;
import space.minecraftstl.xyml.ui.swing.page.instances.InstancesSnapshot;

import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import javax.swing.plaf.basic.BasicArrowButton;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.Consumer;

/// Compact instance drop-down whose popup keeps the existing viewport-driven lazy loading behavior.
///
/// Popup height is derived from the exact item count and the actual usable screen space. No default page size
/// or fixed adjacent-page cache is introduced by this title-bar control.
@NotNullByDefault
final class LazyInstanceSelector extends JPanel implements AutoCloseable {
    /// Minimum popup width needed by the icon, two text lines, and selection indicator.
    private static final int MINIMUM_POPUP_WIDTH = 320;

    /// Space retained around a popup inside its current screen work area.
    private static final int POPUP_SCREEN_MARGIN = 16;

    /// Main selected-instance display and popup command.
    private final JButton valueButton = new JButton();

    /// Familiar trailing disclosure control sharing the same popup command.
    private final BasicArrowButton arrowButton = new BasicArrowButton(SwingConstants.SOUTH);

    /// Popup hosting the measured lazy single-choice list.
    private final JPopupMenu popup = new JPopupMenu();

    /// Existing viewport-driven list used without eager row materialization.
    private final ViewportChoiceList<InstanceListItem> choiceList;

    /// Current selected-repository instance model.
    private final InstancesModel model;

    /// Command returning the stable shell to instance management.
    private final Consumer<ShellPageId> navigateCommand;

    /// Owned instance-model subscription.
    private final Subscription modelSubscription;

    /// Listener that completes a placeholder selection after its lazy row arrives.
    private final ListDataListener listDataListener = new ListDataListener() {
        /// Rechecks a newly loaded range.
        @Override
        public void intervalAdded(ListDataEvent event) {
            submitPendingSelection();
        }

        /// Rechecks a removed sparse range.
        @Override
        public void intervalRemoved(ListDataEvent event) {
            submitPendingSelection();
        }

        /// Rechecks changed loading or failure rows.
        @Override
        public void contentsChanged(ListDataEvent event) {
            submitPendingSelection();
        }
    };

    /// Latest represented model state.
    private InstancesSnapshot displayedSnapshot;

    /// User-selected logical row waiting for its lazy value, or -1 when none is pending.
    private int pendingSelectionIndex = -1;

    /// Whether programmatic model selection is suppressing command delegation.
    private boolean applyingSnapshot;

    /// Whether the popup and model subscription have been released.
    private boolean closed;

    /// Creates one title-bar instance selector.
    ///
    /// @param model current selected-repository instance model
    /// @param navigateCommand shell navigation command
    LazyInstanceSelector(
            InstancesModel model,
            Consumer<ShellPageId> navigateCommand) {
        super(new BorderLayout(0, 0));
        EdtDispatcher.requireEventDispatchThread();
        this.model = Objects.requireNonNull(model, "model");
        this.navigateCommand = Objects.requireNonNull(navigateCommand, "navigateCommand");
        choiceList = new ViewportChoiceList<>(model, new InstanceListCellRenderer());
        displayedSnapshot = model.snapshot();
        configureComponents();
        modelSubscription = model.subscribe(this::modelChanged);
        applySnapshot(displayedSnapshot);
    }

    /// Updates the compact selected value from the launcher's shared selection projection.
    ///
    /// @param selectedName selected instance display name or localized missing-selection text
    /// @param detail selected version-folder detail or an empty string
    void setSelectedText(String selectedName, String detail) {
        EdtDispatcher.requireEventDispatchThread();
        String name = Objects.requireNonNull(selectedName, "selectedName");
        String secondary = Objects.requireNonNull(detail, "detail");
        valueButton.setText(name);
        valueButton.setToolTipText(secondary.isBlank() ? name : name + " - " + secondary);
        valueButton.getAccessibleContext().setAccessibleDescription(valueButton.getToolTipText());
    }

    /// Returns the main display command for focused accessibility and geometry tests.
    ///
    /// @return stable selected-instance button
    JButton valueButton() {
        return valueButton;
    }

    /// Returns the viewport list used by the popup for focused loading tests.
    ///
    /// @return measured lazy list
    ViewportChoiceList<InstanceListItem> choiceList() {
        return choiceList;
    }

    /// Returns the popup preferred size after deriving it from current geometry.
    ///
    /// @return current popup size
    Dimension preparePopupSize() {
        EdtDispatcher.requireEventDispatchThread();
        int rowCount = displayedSnapshot.itemCount();
        int availableHeight = availablePopupHeight();
        int availableRows = Math.max(1, availableHeight / InstanceListCellRenderer.ROW_HEIGHT);
        int displayedRows = Math.min(Math.max(1, rowCount), availableRows);
        int popupHeight = displayedRows * InstanceListCellRenderer.ROW_HEIGHT;
        int popupWidth = Math.max(MINIMUM_POPUP_WIDTH, getWidth());
        Dimension size = new Dimension(popupWidth, popupHeight);
        choiceList.setPreferredSize(size);
        popup.setPopupSize(size);
        return size;
    }

    /// Releases sparse requests and the model listener on the EDT.
    @Override
    public void close() {
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (closed) {
                return;
            }
            closed = true;
            popup.setVisible(false);
            modelSubscription.unsubscribe();
            choiceList.getChoiceModel().removeListDataListener(listDataListener);
            choiceList.close();
            valueButton.setEnabled(false);
            arrowButton.setEnabled(false);
        });
    }

    /// Builds the compact selector and reusable popup.
    private void configureComponents() {
        setOpaque(false);
        setName("shellInstanceSelector");

        valueButton.setName("shellInstanceValue");
        valueButton.setIcon(new FlatSVGIcon("assets/swing/icons/nav-instances.svg", 18, 18));
        valueButton.setHorizontalAlignment(SwingConstants.LEFT);
        valueButton.setIconTextGap(8);
        valueButton.putClientProperty("JButton.buttonType", "roundRect");
        valueButton.setMargin(new Insets(4, 10, 4, 8));
        valueButton.addActionListener(event -> showPopup());
        valueButton.getAccessibleContext().setAccessibleName("Instance");

        arrowButton.setName("shellInstanceDisclosure");
        arrowButton.setFocusable(true);
        arrowButton.addActionListener(event -> showPopup());
        arrowButton.getAccessibleContext().setAccessibleName("Choose instance");

        add(valueButton, BorderLayout.CENTER);
        add(arrowButton, BorderLayout.LINE_END);

        popup.setName("shellInstancePopup");
        popup.setLayout(new BorderLayout());
        popup.add(choiceList, BorderLayout.CENTER);
        JList<ChoiceListEntry<InstanceListItem>> list = choiceList.getList();
        list.setName("shellInstancePopupList");
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addListSelectionListener(event -> {
            if (!closed && !applyingSnapshot && !event.getValueIsAdjusting()) {
                pendingSelectionIndex = list.getSelectedIndex();
                submitPendingSelection();
            }
        });
        choiceList.getChoiceModel().addListDataListener(listDataListener);
    }

    /// Shows the popup only when at least one exact instance exists.
    private void showPopup() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || displayedSnapshot.itemCount() == 0 || !displayedSnapshot.listEnabled()) {
            return;
        }
        restoreSelection(displayedSnapshot.selectedIndex());
        Dimension size = preparePopupSize();
        choiceList.refreshLoadPlan();
        popup.show(this, getWidth() - size.width, getHeight());
    }

    /// Returns the actual vertical work area available to this popup.
    ///
    /// @return positive usable popup height
    private int availablePopupHeight() {
        @Nullable GraphicsConfiguration configuration = getGraphicsConfiguration();
        if (configuration == null || !isShowing()) {
            int localHeight = getRootPane() == null ? 0 : getRootPane().getHeight() - getHeight();
            return Math.max(InstanceListCellRenderer.ROW_HEIGHT, localHeight);
        }
        Rectangle screen = configuration.getBounds();
        Insets screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        int workBottom = screen.y + screen.height - screenInsets.bottom;
        int anchorBottom = getLocationOnScreen().y + getHeight();
        return Math.max(
                InstanceListCellRenderer.ROW_HEIGHT,
                workBottom - anchorBottom - POPUP_SCREEN_MARGIN);
    }

    /// Receives a repository or selection transition and coalesces it onto the EDT.
    ///
    /// @param change transition invalidating the represented popup state
    private void modelChanged(ValueChange<InstancesSnapshot> change) {
        Objects.requireNonNull(change, "change");
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (!closed) {
                applySnapshot(model.snapshot());
            }
        });
    }

    /// Applies count, content revision, selection, and availability without eager row loading.
    ///
    /// @param replacement current model state
    private void applySnapshot(InstancesSnapshot replacement) {
        EdtDispatcher.requireEventDispatchThread();
        InstancesSnapshot previous = displayedSnapshot;
        displayedSnapshot = Objects.requireNonNull(replacement, "replacement");
        if (previous.contentRevision() != replacement.contentRevision()) {
            pendingSelectionIndex = -1;
            choiceList.reloadData();
        }
        restoreSelection(replacement.selectedIndex());
        boolean enabled = replacement.itemCount() > 0 && replacement.listEnabled();
        valueButton.setEnabled(enabled);
        arrowButton.setEnabled(enabled);
        choiceList.setEnabled(enabled);
        choiceList.getList().setEnabled(enabled);
    }

    /// Restores the selected logical row without invoking a model command.
    ///
    /// @param selectedIndex selected row or empty when no repository instance is selected
    private void restoreSelection(OptionalInt selectedIndex) {
        int targetIndex = selectedIndex.orElse(-1);
        JList<ChoiceListEntry<InstanceListItem>> list = choiceList.getList();
        if (targetIndex >= choiceList.getChoiceModel().getSize()) {
            targetIndex = -1;
        }
        if (list.getSelectedIndex() == targetIndex) {
            return;
        }
        applyingSnapshot = true;
        try {
            list.setSelectedIndex(targetIndex);
            if (targetIndex >= 0 && popup.isVisible()) {
                list.ensureIndexIsVisible(targetIndex);
            }
        } finally {
            applyingSnapshot = false;
        }
    }

    /// Commits one user-selected lazy row and opens its management view.
    private void submitPendingSelection() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || applyingSnapshot || pendingSelectionIndex < 0
                || choiceList.getList().getSelectedIndex() != pendingSelectionIndex) {
            return;
        }
        @Nullable InstanceListItem selected = choiceList.getSelectedValue();
        if (selected == null) {
            return;
        }
        pendingSelectionIndex = -1;
        model.selectInstance(selected.id());
        popup.setVisible(false);
        navigateCommand.accept(ShellPageId.INSTANCES);
        model.manageSelectedInstance();
    }
}

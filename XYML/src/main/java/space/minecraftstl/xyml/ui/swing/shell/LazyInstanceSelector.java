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
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;
import space.minecraftstl.xyml.ui.swing.choice.ChoiceListEntry;
import space.minecraftstl.xyml.ui.swing.choice.OrderedChoiceDataSource;
import space.minecraftstl.xyml.ui.swing.choice.ViewportChoiceList;
import space.minecraftstl.xyml.ui.swing.page.instances.InstanceListCellRenderer;
import space.minecraftstl.xyml.ui.swing.page.instances.InstanceListItem;
import space.minecraftstl.xyml.ui.swing.page.instances.InstancesModel;
import space.minecraftstl.xyml.ui.swing.page.instances.InstancesSnapshot;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.Consumer;

/// Compact instance dropdown with explicit creation and management commands around a lazy MRU list.
///
/// Popup height follows the exact item count and current screen work area. Stable identifiers are reordered eagerly,
/// while row details and icons remain viewport loaded. The selected instance button and popup use no independent
/// `roundRect` override, allowing the configured global corner radius to own their shape.
@NotNullByDefault
final class LazyInstanceSelector extends JPanel implements AutoCloseable {
    /// Minimum popup width needed by the icon and two text lines.
    private static final int MINIMUM_POPUP_WIDTH = 320;

    /// Height reserved for each explicit popup command.
    static final int COMMAND_HEIGHT = 42;

    /// Space retained around a popup inside its current screen work area.
    private static final int POPUP_SCREEN_MARGIN = 16;

    /// Current selected-instance display and popup command.
    private final ShellDropdownButton valueButton = new ShellDropdownButton();

    /// Popup hosting explicit commands and the measured lazy list.
    private final JPopupMenu popup = new JPopupMenu();

    /// Switches the popup body between the lazy list and an explicit empty state.
    private final CardLayout choiceLayout = new CardLayout();

    /// Stable body hosting either instance rows or the empty state.
    private final JPanel choiceHost = new JPanel(choiceLayout);

    /// Stable-ID projection applying per-directory recent-use ordering.
    private final OrderedChoiceDataSource<InstanceListItem> orderedSource;

    /// Existing viewport-driven list used without eager row materialization.
    private final ViewportChoiceList<InstanceListItem> choiceList;

    /// Empty state retained between popup openings.
    private final JLabel emptyLabel = new JLabel();

    /// Top command opening the new-game workflow.
    private final JButton addButton = new JButton();

    /// Bottom command opening complete instance management.
    private final JButton manageButton = new JButton();

    /// Current selected-directory instance model.
    private final InstancesModel model;

    /// Persistent recent-use order shared by shell selectors.
    private final ShellRecentSelections recentSelections;

    /// Command returning the shell to persistent instance management.
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

    /// User-selected display row waiting for its lazy value, or -1 when none is pending.
    private int pendingSelectionIndex = -1;

    /// Projection revision captured with [#pendingSelectionIndex].
    private long pendingSelectionRevision = -1L;

    /// Whether programmatic selection restoration suppresses command delegation.
    private boolean applyingSnapshot;

    /// Stable selected-directory identifier qualifying instance history, or null before directory state arrives.
    private @Nullable String directoryContext;

    /// Whether popup and model resources have been released.
    private boolean closed;

    /// Creates one title-bar instance selector.
    ///
    /// @param model current selected-directory lazy instance model
    /// @param recentSelections persistent compact-selector history
    /// @param selectorLabel accessible selector label
    /// @param emptyLabelText localized empty-instance state
    /// @param addLabel localized new-game command label
    /// @param managementLabel localized instance-management command label
    /// @param navigateCommand shell navigation command
    LazyInstanceSelector(
            InstancesModel model,
            ShellRecentSelections recentSelections,
            String selectorLabel,
            String emptyLabelText,
            String addLabel,
            String managementLabel,
            Consumer<ShellPageId> navigateCommand) {
        super(new BorderLayout());
        EdtDispatcher.requireEventDispatchThread();
        this.model = Objects.requireNonNull(model, "model");
        this.recentSelections = Objects.requireNonNull(recentSelections, "recentSelections");
        this.navigateCommand = Objects.requireNonNull(navigateCommand, "navigateCommand");
        orderedSource = new OrderedChoiceDataSource<>(model);
        choiceList = new ViewportChoiceList<>(orderedSource, new InstanceListCellRenderer());
        displayedSnapshot = model.snapshot();
        configureComponents(
                Objects.requireNonNull(selectorLabel, "selectorLabel"),
                Objects.requireNonNull(emptyLabelText, "emptyLabelText"),
                Objects.requireNonNull(addLabel, "addLabel"),
                Objects.requireNonNull(managementLabel, "managementLabel"));
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

    /// Switches instance history to the exact selected directory context.
    ///
    /// @param stableDirectoryId selected directory identifier
    void setDirectoryContext(String stableDirectoryId) {
        EdtDispatcher.requireEventDispatchThread();
        String replacement = Objects.requireNonNull(stableDirectoryId, "stableDirectoryId");
        if (replacement.isBlank()) {
            throw new IllegalArgumentException("stableDirectoryId must not be blank");
        }
        if (replacement.equals(directoryContext)) {
            return;
        }
        directoryContext = replacement;
        pendingSelectionIndex = -1;
        pendingSelectionRevision = -1L;
        popup.setVisible(false);
        OptionalInt selection = synchronizeOrder(displayedSnapshot);
        choiceList.reloadData();
        restoreSelection(selection);
    }

    /// Returns the main display command for focused accessibility and geometry tests.
    ///
    /// @return stable selected-instance button
    ShellDropdownButton valueButton() {
        return valueButton;
    }

    /// Returns the explicit new-game command for focused popup tests.
    ///
    /// @return stable add button
    JButton addButton() {
        return addButton;
    }

    /// Returns the explicit instance-management command for focused popup tests.
    ///
    /// @return stable management button
    JButton manageButton() {
        return manageButton;
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
        int listBudget = Math.max(
                InstanceListCellRenderer.ROW_HEIGHT,
                availablePopupHeight() - COMMAND_HEIGHT * 2);
        int availableRows = Math.max(1, listBudget / InstanceListCellRenderer.ROW_HEIGHT);
        int displayedRows = Math.min(Math.max(1, rowCount), availableRows);
        int listHeight = displayedRows * InstanceListCellRenderer.ROW_HEIGHT;
        int popupWidth = Math.max(MINIMUM_POPUP_WIDTH, getWidth());
        choiceList.setPreferredSize(new Dimension(popupWidth, listHeight));
        choiceLayout.show(choiceHost, rowCount == 0 ? "empty" : "instances");
        Dimension size = new Dimension(popupWidth, listHeight + COMMAND_HEIGHT * 2);
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
            addButton.setEnabled(false);
            manageButton.setEnabled(false);
        });
    }

    /// Builds the compact selector and reusable three-part popup.
    private void configureComponents(
            String selectorLabel,
            String emptyLabelText,
            String addLabel,
            String managementLabel) {
        setOpaque(false);
        setName("shellInstanceSelector");

        valueButton.setName("shellInstanceValue");
        valueButton.setIcon(new FlatSVGIcon("assets/swing/icons/nav-instances.svg", 18, 18));
        valueButton.setIconTextGap(8);
        valueButton.bindPopup(popup, this::showPopup);
        valueButton.getAccessibleContext().setAccessibleName(selectorLabel);
        add(valueButton, BorderLayout.CENTER);

        popup.setName("shellInstancePopup");
        popup.setLayout(new BorderLayout());
        addButton.setName("shellInstanceAdd");
        addButton.setText(addLabel);
        addButton.setIcon(new FlatSVGIcon("assets/swing/icons/add.svg", 18, 18));
        addButton.setHorizontalAlignment(SwingConstants.LEFT);
        addButton.putClientProperty("JButton.buttonType", "toolBarButton");
        addButton.addActionListener(event -> {
            if (!closed) {
                popup.setVisible(false);
                model.addInstance();
            }
        });
        popup.add(addButton, BorderLayout.NORTH);

        choiceHost.setName("shellInstanceChoices");
        choiceHost.add(choiceList, "instances");
        emptyLabel.setName("shellInstanceEmpty");
        emptyLabel.setText(emptyLabelText);
        emptyLabel.setHorizontalAlignment(SwingConstants.CENTER);
        choiceHost.add(emptyLabel, "empty");
        popup.add(choiceHost, BorderLayout.CENTER);

        JList<ChoiceListEntry<InstanceListItem>> list = choiceList.getList();
        list.setName("shellInstancePopupList");
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addListSelectionListener(event -> {
            if (!closed && !applyingSnapshot && !event.getValueIsAdjusting()) {
                pendingSelectionIndex = list.getSelectedIndex();
                pendingSelectionRevision = orderedSource.sourceRevision().orElse(-1L);
                submitPendingSelection();
            }
        });
        choiceList.getChoiceModel().addListDataListener(listDataListener);

        manageButton.setName("shellInstanceManagement");
        manageButton.setText(managementLabel);
        manageButton.setIcon(new FlatSVGIcon("assets/swing/icons/format-list-bulleted.svg", 18, 18));
        manageButton.setHorizontalAlignment(SwingConstants.LEFT);
        manageButton.putClientProperty("JButton.buttonType", "toolBarButton");
        manageButton.addActionListener(event -> {
            if (!closed) {
                popup.setVisible(false);
                navigateCommand.accept(ShellPageId.INSTANCES);
            }
        });
        popup.add(manageButton, BorderLayout.SOUTH);
    }

    /// Shows the popup even for an empty list so creation and management remain reachable.
    private void showPopup() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            return;
        }
        restoreSelection(synchronizeOrder(displayedSnapshot));
        Dimension size = preparePopupSize();
        if (displayedSnapshot.itemCount() > 0) {
            choiceList.refreshLoadPlan();
        }
        popup.show(this, getWidth() - size.width, getHeight());
        popup.setPopupSize(size);
    }

    /// Returns the actual vertical work area available to this popup.
    ///
    /// @return positive usable popup height
    private int availablePopupHeight() {
        @Nullable GraphicsConfiguration configuration = getGraphicsConfiguration();
        if (configuration == null || !isShowing()) {
            int localHeight = getRootPane() == null ? 0 : getRootPane().getHeight() - getHeight();
            return Math.max(COMMAND_HEIGHT * 2 + InstanceListCellRenderer.ROW_HEIGHT, localHeight);
        }
        Rectangle screen = configuration.getBounds();
        Insets screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        int workBottom = screen.y + screen.height - screenInsets.bottom;
        int anchorBottom = getLocationOnScreen().y + getHeight();
        return Math.max(
                COMMAND_HEIGHT * 2 + InstanceListCellRenderer.ROW_HEIGHT,
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
        long previousOrderRevision = orderedSource.sourceRevision().orElse(-1L);
        OptionalInt displaySelection = synchronizeOrder(replacement);
        boolean orderChanged = previousOrderRevision != orderedSource.sourceRevision().orElse(-1L);
        if (previous.contentRevision() != replacement.contentRevision() || orderChanged) {
            pendingSelectionIndex = -1;
            pendingSelectionRevision = -1L;
            popup.setVisible(false);
            choiceList.reloadData();
        }
        restoreSelection(displaySelection);
        boolean listEnabled = replacement.itemCount() > 0 && replacement.listEnabled();
        valueButton.setEnabled(!closed && (listEnabled || replacement.addEnabled()));
        addButton.setEnabled(!closed && replacement.addEnabled());
        manageButton.setEnabled(!closed);
        choiceList.setEnabled(listEnabled);
        choiceList.getList().setEnabled(listEnabled);
    }

    /// Restores the selected display row without invoking a model command.
    ///
    /// @param selectedIndex selected display row or empty for no repository selection
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

    /// Commits one loaded user-selected row without implicitly opening its management view.
    private void submitPendingSelection() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || applyingSnapshot || pendingSelectionIndex < 0
                || pendingSelectionRevision != orderedSource.sourceRevision().orElse(-1L)
                || choiceList.getList().getSelectedIndex() != pendingSelectionIndex) {
            return;
        }
        @Nullable InstanceListItem selected = choiceList.getSelectedValue();
        if (selected == null) {
            return;
        }
        pendingSelectionIndex = -1;
        pendingSelectionRevision = -1L;
        model.selectInstance(selected.id());
        popup.setVisible(false);
    }

    /// Reconciles per-directory history and translates source selection into display order.
    ///
    /// @param snapshot current instance model state
    /// @return selected display index, or empty when no instance is selected
    private OptionalInt synchronizeOrder(InstancesSnapshot snapshot) {
        @Unmodifiable List<String> sourceIds = model.stableItemIds();
        @Nullable String selectedId = null;
        if (snapshot.selectedIndex().isPresent()
                && snapshot.selectedIndex().getAsInt() < sourceIds.size()) {
            selectedId = sourceIds.get(snapshot.selectedIndex().getAsInt());
        }
        @Unmodifiable List<String> orderedIds;
        if (directoryContext == null) {
            orderedIds = sourceIds;
        } else {
            if (selectedId != null) {
                recentSelections.recordInstance(directoryContext, selectedId);
            }
            orderedIds = recentSelections.orderInstances(directoryContext, sourceIds);
        }
        orderedSource.setOrder(orderedIds);
        return selectedId == null ? OptionalInt.empty() : orderedSource.displayIndexOf(selectedId);
    }
}

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
import space.minecraftstl.xyml.ui.swing.page.accounts.AccountListCellRenderer;
import space.minecraftstl.xyml.ui.swing.page.accounts.AccountListItem;
import space.minecraftstl.xyml.ui.swing.page.accounts.AccountsModel;
import space.minecraftstl.xyml.ui.swing.page.accounts.AccountsSnapshot;

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

/// Compact account drop-down backed by the existing viewport-driven account data source.
///
/// The popup derives its row count from measured screen space and the exact model count. Its footer
/// keeps full account management reachable without conflating account selection with navigation.
@NotNullByDefault
final class LazyAccountSelector extends JPanel implements AutoCloseable {
    /// Minimum popup width needed by the avatar, two text lines, and selection indicator.
    private static final int MINIMUM_POPUP_WIDTH = 320;

    /// Height reserved for the account-management footer.
    static final int MANAGEMENT_FOOTER_HEIGHT = 42;

    /// Height reserved for the add-account command above the list.
    static final int ADD_HEADER_HEIGHT = 42;

    /// Space retained around a popup inside its current screen work area.
    private static final int POPUP_SCREEN_MARGIN = 16;

    /// Main selected-account display and popup command.
    private final ShellDropdownButton valueButton = new ShellDropdownButton();

    /// Popup hosting the measured lazy list and explicit account-management command.
    private final JPopupMenu popup = new JPopupMenu();

    /// Switches the popup body between the lazy list and an explicit empty state.
    private final CardLayout choiceLayout = new CardLayout();

    /// Stable popup body hosting either account rows or the empty-state label.
    private final JPanel choiceHost = new JPanel(choiceLayout);

    /// Existing viewport-driven list used without eager row materialization.
    private final ViewportChoiceList<AccountListItem> choiceList;

    /// Stable-ID projection applying independent recent-use ordering without eager row loading.
    private final OrderedChoiceDataSource<AccountListItem> orderedSource;

    /// Empty state shown when the exact account count is zero.
    private final JLabel emptyLabel = new JLabel();

    /// Footer command opening the complete account-management overlay.
    private final JButton manageButton = new JButton();

    /// Top command opening the add-account workflow.
    private final JButton addButton = new JButton();

    /// Lazy account source and selection command model.
    private final AccountsModel model;

    /// Persistent recent-use order shared by shell selectors.
    private final ShellRecentSelections recentSelections;

    /// Command opening the stable account-management overlay.
    private final Consumer<ShellPageId> navigateCommand;

    /// Owned account-model subscription.
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

    /// Latest represented account model state.
    private AccountsSnapshot displayedSnapshot;

    /// User-selected logical row waiting for its lazy value, or -1 when none is pending.
    private int pendingSelectionIndex = -1;

    /// Projection revision captured with [#pendingSelectionIndex].
    private long pendingSelectionRevision = -1L;

    /// Whether programmatic model selection is suppressing command delegation.
    private boolean applyingSnapshot;

    /// Whether launcher state currently permits account interaction.
    private boolean interactionEnabled = true;

    /// Whether the popup and model subscription have been released.
    private boolean closed;

    /// Creates one title-bar account selector.
    ///
    /// @param model lazy account model
    /// @param recentSelections persistent compact-selector history
    /// @param selectorLabel localized account-selector accessible label
    /// @param emptyLabelText localized empty-account state
    /// @param addLabel localized add-account command label
    /// @param managementLabel localized account-management command label
    /// @param navigateCommand shell navigation command
    LazyAccountSelector(
            AccountsModel model,
            ShellRecentSelections recentSelections,
            String selectorLabel,
            String emptyLabelText,
            String addLabel,
            String managementLabel,
            Consumer<ShellPageId> navigateCommand) {
        super(new BorderLayout(0, 0));
        EdtDispatcher.requireEventDispatchThread();
        this.model = Objects.requireNonNull(model, "model");
        this.recentSelections = Objects.requireNonNull(recentSelections, "recentSelections");
        this.navigateCommand = Objects.requireNonNull(navigateCommand, "navigateCommand");
        orderedSource = new OrderedChoiceDataSource<>(model);
        choiceList = new ViewportChoiceList<>(orderedSource, new AccountListCellRenderer());
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
    /// @param selectedName selected account name or localized missing-selection text
    /// @param detail provider or storage detail, or an empty string
    void setSelectedText(String selectedName, String detail) {
        EdtDispatcher.requireEventDispatchThread();
        String name = Objects.requireNonNull(selectedName, "selectedName");
        String secondary = Objects.requireNonNull(detail, "detail");
        valueButton.setText(name);
        valueButton.setToolTipText(secondary.isBlank() ? name : name + " - " + secondary);
        valueButton.getAccessibleContext().setAccessibleDescription(valueButton.getToolTipText());
    }

    /// Marks the account-management footer as selected while its overlay is active.
    ///
    /// @param selected whether account management is the active shell page
    void setManagementSelected(boolean selected) {
        manageButton.putClientProperty("JButton.selectedState", selected ? "selected" : null);
    }

    /// Enables or disables popup interaction from shared launcher state.
    ///
    /// @param enabled whether account selection and management may be opened
    void setInteractionEnabled(boolean enabled) {
        EdtDispatcher.requireEventDispatchThread();
        interactionEnabled = enabled;
        if (!enabled) {
            pendingSelectionIndex = -1;
            popup.setVisible(false);
        }
        applyEnabledState();
    }

    /// Returns the main selected-account display for focused accessibility tests.
    ///
    /// @return stable selected-account button
    ShellDropdownButton valueButton() {
        return valueButton;
    }

    /// Returns the add-account header command for focused popup tests.
    ///
    /// @return stable add-account button
    JButton addButton() {
        return addButton;
    }

    /// Returns the account-management footer command for focused navigation tests.
    ///
    /// @return stable management button
    JButton manageButton() {
        return manageButton;
    }

    /// Returns the viewport list used by the popup for focused loading tests.
    ///
    /// @return measured lazy account list
    ViewportChoiceList<AccountListItem> choiceList() {
        return choiceList;
    }

    /// Returns the popup empty-state label for focused accessibility tests.
    ///
    /// @return stable empty-state label
    JLabel emptyLabel() {
        return emptyLabel;
    }

    /// Returns the popup preferred size after deriving it from current geometry.
    ///
    /// @return current popup size
    Dimension preparePopupSize() {
        EdtDispatcher.requireEventDispatchThread();
        int rowCount = displayedSnapshot.itemCount();
        int listBudget = Math.max(
                0,
                availablePopupHeight() - MANAGEMENT_FOOTER_HEIGHT - ADD_HEADER_HEIGHT);
        int availableRows = Math.max(1, listBudget / AccountListCellRenderer.ROW_HEIGHT);
        int displayedRows = Math.min(Math.max(1, rowCount), availableRows);
        int listHeight = displayedRows * AccountListCellRenderer.ROW_HEIGHT;
        int popupWidth = Math.max(MINIMUM_POPUP_WIDTH, getWidth());
        choiceList.setPreferredSize(new Dimension(popupWidth, listHeight));
        choiceLayout.show(choiceHost, rowCount == 0 ? "empty" : "accounts");
        Dimension size = new Dimension(
                popupWidth,
                listHeight + MANAGEMENT_FOOTER_HEIGHT + ADD_HEADER_HEIGHT);
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

    /// Builds the compact selector and reusable popup.
    ///
    /// @param selectorLabel localized account-selector accessible label
    /// @param emptyLabelText localized empty-account state
    /// @param managementLabel localized account-management command label
    private void configureComponents(
            String selectorLabel,
            String emptyLabelText,
            String addLabel,
            String managementLabel) {
        setOpaque(false);
        setName("shellAccountSelector");

        valueButton.setName("shellAccountValue");
        valueButton.setIcon(new FlatSVGIcon("assets/swing/icons/nav-accounts.svg", 18, 18));
        valueButton.setIconTextGap(8);
        valueButton.bindPopup(popup, this::showPopup);
        valueButton.getAccessibleContext().setAccessibleName(selectorLabel);

        add(valueButton, BorderLayout.CENTER);

        popup.setName("shellAccountPopup");
        popup.setLayout(new BorderLayout());
        addButton.setName("shellAccountAdd");
        addButton.setText(addLabel);
        addButton.setIcon(new FlatSVGIcon("assets/swing/icons/add.svg", 18, 18));
        addButton.setHorizontalAlignment(SwingConstants.LEFT);
        addButton.putClientProperty("JButton.buttonType", "toolBarButton");
        addButton.addActionListener(event -> {
            if (!closed && interactionEnabled) {
                popup.setVisible(false);
                model.addAccount();
            }
        });
        addButton.getAccessibleContext().setAccessibleName(addLabel);
        popup.add(addButton, BorderLayout.NORTH);
        choiceHost.setName("shellAccountChoices");
        choiceHost.add(choiceList, "accounts");
        emptyLabel.setName("shellAccountEmpty");
        emptyLabel.setText(emptyLabelText);
        emptyLabel.setHorizontalAlignment(SwingConstants.CENTER);
        emptyLabel.getAccessibleContext().setAccessibleName(emptyLabelText);
        choiceHost.add(emptyLabel, "empty");
        popup.add(choiceHost, BorderLayout.CENTER);
        JList<ChoiceListEntry<AccountListItem>> list = choiceList.getList();
        list.setName("shellAccountPopupList");
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addListSelectionListener(event -> {
            if (!closed && !applyingSnapshot && !event.getValueIsAdjusting()) {
                pendingSelectionIndex = list.getSelectedIndex();
                pendingSelectionRevision = orderedSource.sourceRevision().orElse(-1L);
                submitPendingSelection();
            }
        });
        choiceList.getChoiceModel().addListDataListener(listDataListener);

        manageButton.setName("shellAccountManagement");
        manageButton.setText(managementLabel);
        manageButton.setIcon(new FlatSVGIcon("assets/swing/icons/nav-accounts.svg", 18, 18));
        manageButton.setHorizontalAlignment(SwingConstants.LEFT);
        manageButton.putClientProperty("JButton.buttonType", "toolBarButton");
        manageButton.addActionListener(event -> {
            if (!closed) {
                popup.setVisible(false);
                navigateCommand.accept(ShellPageId.ACCOUNTS);
            }
        });
        manageButton.getAccessibleContext().setAccessibleName(managementLabel);
        popup.add(manageButton, BorderLayout.SOUTH);
    }

    /// Shows the popup even for an empty account list so management remains reachable.
    private void showPopup() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || !interactionEnabled) {
            return;
        }
        restoreSelection(synchronizeOrder(displayedSnapshot));
        Dimension size = preparePopupSize();
        if (displayedSnapshot.itemCount() > 0) {
            choiceList.refreshLoadPlan();
        }
        popup.show(this, 0, getHeight());
        popup.setPopupSize(size);
    }

    /// Returns the actual vertical work area available to this popup.
    ///
    /// @return positive usable popup height
    private int availablePopupHeight() {
        @Nullable GraphicsConfiguration configuration = getGraphicsConfiguration();
        if (configuration == null || !isShowing()) {
            int localHeight = getRootPane() == null ? 0 : getRootPane().getHeight() - getHeight();
            return Math.max(
                    MANAGEMENT_FOOTER_HEIGHT + ADD_HEADER_HEIGHT + AccountListCellRenderer.ROW_HEIGHT,
                    localHeight);
        }
        Rectangle screen = configuration.getBounds();
        Insets screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        int workBottom = screen.y + screen.height - screenInsets.bottom;
        int anchorBottom = getLocationOnScreen().y + getHeight();
        return Math.max(
                MANAGEMENT_FOOTER_HEIGHT + ADD_HEADER_HEIGHT + AccountListCellRenderer.ROW_HEIGHT,
                workBottom - anchorBottom - POPUP_SCREEN_MARGIN);
    }

    /// Receives one account-list transition and coalesces it onto the EDT.
    ///
    /// @param change transition invalidating popup state or selection
    private void modelChanged(ValueChange<AccountsSnapshot> change) {
        Objects.requireNonNull(change, "change");
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (!closed) {
                applySnapshot(model.snapshot());
            }
        });
    }

    /// Applies count, content revision, and selection without eager row loading.
    ///
    /// @param replacement current model state
    private void applySnapshot(AccountsSnapshot replacement) {
        EdtDispatcher.requireEventDispatchThread();
        AccountsSnapshot previous = displayedSnapshot;
        displayedSnapshot = Objects.requireNonNull(replacement, "replacement");
        long previousOrderRevision = orderedSource.sourceRevision().orElse(-1L);
        OptionalInt displaySelection = synchronizeOrder(replacement);
        boolean orderChanged = previousOrderRevision != orderedSource.sourceRevision().orElse(-1L);
        if (previous.contentRevision() != replacement.contentRevision()
                || orderChanged) {
            pendingSelectionIndex = -1;
            pendingSelectionRevision = -1L;
            popup.setVisible(false);
            choiceList.reloadData();
        }
        restoreSelection(displaySelection);
        applyEnabledState();
    }

    /// Applies lifecycle, launcher interaction, and exact-count availability to child controls.
    private void applyEnabledState() {
        boolean available = !closed && interactionEnabled;
        boolean hasAccounts = displayedSnapshot.itemCount() > 0;
        valueButton.setEnabled(available);
        addButton.setEnabled(available);
        manageButton.setEnabled(available);
        choiceList.setEnabled(available && hasAccounts);
        choiceList.getList().setEnabled(available && hasAccounts);
    }

    /// Restores the selected logical row without invoking a model command.
    ///
    /// @param selectedIndex selected row or empty when no account is selected
    private void restoreSelection(OptionalInt selectedIndex) {
        int targetIndex = selectedIndex.orElse(-1);
        JList<ChoiceListEntry<AccountListItem>> list = choiceList.getList();
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

    /// Commits one user-selected lazy row without opening account management.
    private void submitPendingSelection() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || !interactionEnabled || applyingSnapshot || pendingSelectionIndex < 0
                || pendingSelectionRevision != orderedSource.sourceRevision().orElse(-1L)
                || choiceList.getList().getSelectedIndex() != pendingSelectionIndex) {
            return;
        }
        @Nullable AccountListItem selected = choiceList.getSelectedValue();
        if (selected == null) {
            return;
        }
        pendingSelectionIndex = -1;
        pendingSelectionRevision = -1L;
        model.selectAccount(selected.accountId());
        popup.setVisible(false);
    }

    /// Reconciles persistent history and translates the model's source selection into display order.
    ///
    /// @param snapshot current account model state
    /// @return selected display index, or empty when no account is selected
    private OptionalInt synchronizeOrder(AccountsSnapshot snapshot) {
        @Unmodifiable List<String> sourceIds = model.stableItemIds();
        @Nullable String selectedId = null;
        if (snapshot.selectedIndex().isPresent()
                && snapshot.selectedIndex().getAsInt() < sourceIds.size()) {
            selectedId = sourceIds.get(snapshot.selectedIndex().getAsInt());
            recentSelections.recordAccount(selectedId);
        }
        @Unmodifiable List<String> orderedIds = recentSelections.orderAccounts(sourceIds);
        orderedSource.setOrder(orderedIds);
        return selectedId == null ? OptionalInt.empty() : orderedSource.displayIndexOf(selectedId);
    }
}

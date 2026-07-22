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
package space.minecraftstl.xyml.ui.swing.page.accounts;

import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;
import space.minecraftstl.xyml.ui.swing.choice.ChoiceListEntry;
import space.minecraftstl.xyml.ui.swing.choice.ViewportChoiceList;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import java.awt.CardLayout;
import java.awt.Font;
import java.util.Objects;
import java.util.OptionalInt;

/// Presents a sparse, viewport-driven list for choosing exactly one launcher account.
///
/// The panel owns its model subscription and viewport requests. It must be closed when its cached
/// shell page is permanently discarded.
@NotNullByDefault
public final class AccountsPanel extends JPanel implements AutoCloseable {
    /// Card name used while at least one account exists.
    private static final String LIST_CARD = "list";

    /// Card name used for an empty exact source.
    private static final String EMPTY_CARD = "empty";

    /// Toolkit-neutral account source and command model.
    private final AccountsModel model;

    /// Viewport-measured single-choice list.
    private final ViewportChoiceList<AccountListItem> choiceList;

    /// Cards that switch between the lazy list and exact empty state.
    private final JPanel listCards = new JPanel(new CardLayout());

    /// Add-account command.
    private final JButton addButton = new JButton();

    /// Owned model listener registration.
    private final Subscription modelSubscription;

    /// Listener that commits a user-selected placeholder after its row finishes loading.
    private final ListDataListener listDataListener = new ListDataListener() {
        /// Rechecks a changed loaded row.
        @Override
        public void intervalAdded(ListDataEvent event) {
            submitPendingUserSelection();
        }

        /// Rechecks a changed loaded row.
        @Override
        public void intervalRemoved(ListDataEvent event) {
            submitPendingUserSelection();
        }

        /// Rechecks a changed loaded row.
        @Override
        public void contentsChanged(ListDataEvent event) {
            submitPendingUserSelection();
        }
    };

    /// Snapshot currently represented by controls, or null before initialization.
    private @Nullable AccountsSnapshot displayedSnapshot;

    /// User-selected logical row waiting for its loaded value, or -1 when none is pending.
    private int pendingUserSelectionIndex = -1;

    /// Whether programmatic selection restoration is suppressing command delegation.
    private boolean applyingSnapshot;

    /// Whether this panel has released subscriptions and load resources.
    private boolean closed;

    /// Creates an account-selection panel on the EDT.
    ///
    /// @param model toolkit-neutral account model and viewport source
    /// @param strings localized page text
    public AccountsPanel(AccountsModel model, AccountsStrings strings) {
        super(new MigLayout(
                "insets 0, fill, wrap 1",
                "[grow,fill]",
                "[]16[grow,fill]"));
        EdtDispatcher.requireEventDispatchThread();
        this.model = Objects.requireNonNull(model, "model");
        Objects.requireNonNull(strings, "strings");
        choiceList = new ViewportChoiceList<>(model, AccountsPanel::displayText);

        configureComponents(strings);
        modelSubscription = model.subscribe(this::modelChanged);
        applySnapshot(model.snapshot());
    }

    /// Returns the immutable snapshot currently represented by this page.
    ///
    /// @return displayed account-page state
    public AccountsSnapshot displayedSnapshot() {
        EdtDispatcher.requireEventDispatchThread();
        return Objects.requireNonNull(displayedSnapshot, "initial account snapshot was not applied");
    }

    /// Returns the viewport list for shell integrations and focused verification.
    ///
    /// @return viewport-driven account list
    public ViewportChoiceList<AccountListItem> choiceList() {
        return choiceList;
    }

    /// Releases the model subscription and viewport requests from any caller thread.
    @Override
    public void close() {
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (!closed) {
                closed = true;
                modelSubscription.unsubscribe();
                choiceList.getChoiceModel().removeListDataListener(listDataListener);
                choiceList.close();
            }
        });
    }

    /// Builds the stable title, command, list, and empty-state layout.
    ///
    /// @param strings localized page text
    private void configureComponents(AccountsStrings strings) {
        JPanel toolbar = new JPanel(new MigLayout(
                "insets 0, fillx",
                "[grow,fill][]",
                "[]"));
        toolbar.setOpaque(false);

        JLabel heading = new JLabel(strings.pageTitle());
        heading.setName("accountsPageTitle");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 28.0F));
        toolbar.add(heading);

        addButton.setName("accountsAdd");
        addButton.setText(strings.addAction());
        addButton.addActionListener(event -> model.addAccount());
        toolbar.add(addButton, "h 40!");
        add(toolbar, "growx");

        choiceList.setName("accountsList");
        JList<ChoiceListEntry<AccountListItem>> list = choiceList.getList();
        list.setName("accountsListView");
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !applyingSnapshot) {
                pendingUserSelectionIndex = list.getSelectedIndex();
                submitPendingUserSelection();
            }
        });
        choiceList.getChoiceModel().addListDataListener(listDataListener);

        JLabel emptyLabel = new JLabel(strings.emptyText(), SwingConstants.CENTER);
        emptyLabel.setName("accountsEmpty");
        listCards.add(choiceList, LIST_CARD);
        listCards.add(emptyLabel, EMPTY_CARD);
        add(listCards, "grow");
    }

    /// Coalesces a worker-published transition to the model's latest snapshot on the EDT.
    ///
    /// @param change transition that invalidated the displayed page
    private void modelChanged(ValueChange<AccountsSnapshot> change) {
        Objects.requireNonNull(change, "change");
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (!closed) {
                applySnapshot(model.snapshot());
            }
        });
    }

    /// Applies one immutable state and reloads only when indexed content changed.
    ///
    /// @param snapshot latest page state
    private void applySnapshot(AccountsSnapshot snapshot) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(snapshot, "snapshot");
        @Nullable AccountsSnapshot previous = displayedSnapshot;
        boolean contentChanged = previous == null
                || previous.contentRevision() != snapshot.contentRevision();
        displayedSnapshot = snapshot;

        if (contentChanged) {
            pendingUserSelectionIndex = -1;
            choiceList.reloadData();
        }

        ((CardLayout) listCards.getLayout()).show(
                listCards,
                snapshot.itemCount() == 0 ? EMPTY_CARD : LIST_CARD);
        restoreSelection(snapshot.selectedIndex());
    }

    /// Restores the model-selected row without delegating it back as a user command.
    ///
    /// @param selectedIndex selected source index, or empty for no selection
    private void restoreSelection(OptionalInt selectedIndex) {
        int targetIndex = selectedIndex.orElse(-1);
        if (targetIndex >= choiceList.getChoiceModel().getSize()) {
            targetIndex = -1;
        }
        if (choiceList.getList().getSelectedIndex() == targetIndex) {
            return;
        }

        pendingUserSelectionIndex = -1;
        applyingSnapshot = true;
        try {
            choiceList.getList().setSelectedIndex(targetIndex);
            if (targetIndex >= 0) {
                choiceList.getList().ensureIndexIsVisible(targetIndex);
            }
        } finally {
            applyingSnapshot = false;
        }
        choiceList.refreshLoadPlan();
    }

    /// Commits a pending user selection once its sparse row has loaded.
    private void submitPendingUserSelection() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || applyingSnapshot || pendingUserSelectionIndex < 0
                || choiceList.getList().getSelectedIndex() != pendingUserSelectionIndex) {
            return;
        }

        @Nullable AccountListItem selected = choiceList.getSelectedValue();
        if (selected != null) {
            pendingUserSelectionIndex = -1;
            model.selectAccount(selected.accountId());
        }
    }

    /// Returns the compact text rendered by the reusable viewport-list cell.
    ///
    /// @param item loaded account row
    /// @return account display name followed by detail when available
    private static String displayText(AccountListItem item) {
        return item.detailText().isBlank()
                ? item.displayName()
                : item.displayName() + " - " + item.detailText();
    }
}

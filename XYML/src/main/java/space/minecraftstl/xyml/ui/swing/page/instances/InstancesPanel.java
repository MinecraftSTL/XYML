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
package space.minecraftstl.xyml.ui.swing.page.instances;

import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.choice.ChoiceListEntry;
import space.minecraftstl.xyml.ui.swing.choice.ViewportChoiceList;
import space.minecraftstl.xyml.ui.swing.page.instances.management.InstanceManagementCoordinator;
import space.minecraftstl.xyml.ui.swing.page.instances.management.InstanceManagementHost;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Font;
import java.util.Objects;
import java.util.OptionalInt;

/// Presents an installed-instance list whose source demand follows measured viewport geometry.
///
/// The panel owns its model subscription and viewport requests and must be closed when its cached
/// shell page is permanently discarded.
@NotNullByDefault
public final class InstancesPanel extends JPanel implements AutoCloseable {
    /// Root card name used while the installed-instance list is visible.
    private static final String INSTANCES_VIEW_CARD = "instances";

    /// Root card name used while one coordinator-owned management view is visible.
    private static final String MANAGEMENT_VIEW_CARD = "management";

    /// Card name used while at least one instance exists.
    private static final String LIST_CARD = "list";

    /// Card name used for an empty exact source.
    private static final String EMPTY_CARD = "empty";

    /// Toolkit-neutral instance source and command model.
    private final InstancesModel model;

    /// Localized page text.
    private final InstancesStrings strings;

    /// Viewport-measured single-choice list.
    private final ViewportChoiceList<InstanceListItem> choiceList;

    /// Stable workspace containing the list toolbar, lazy list, and status controls.
    private final JPanel instancesWorkspace = new JPanel(new MigLayout(
            "insets 0, fill, wrap 1",
            "[grow,fill]",
            "[]16[grow,fill]16[]"));

    /// Empty host whose sole child is the current coordinator-owned management component.
    private final JPanel managementWorkspace = new JPanel(new BorderLayout());

    /// Cards that switch between the lazy list and exact empty state.
    private final JPanel listCards = new JPanel(new CardLayout());

    /// Refresh command.
    private final JButton refreshButton = new JButton();

    /// Add-instance command.
    private final JButton addButton = new JButton();

    /// Manage-selected-instance command.
    private final JButton manageButton = new JButton();

    /// Current repository state text.
    private final JLabel statusLabel = new JLabel();

    /// Owned model listener registration.
    private final Subscription modelSubscription;

    /// Coordinator host lease owned by this panel.
    private final Subscription managementHostLease;

    /// Host adapter that mutates only the panel's root cards and dynamic component mount.
    private final InstanceManagementHost managementHost = new PanelManagementHost();

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
    private @Nullable InstancesSnapshot displayedSnapshot;

    /// User-selected logical row waiting for its loaded value, or -1 when none is pending.
    private int pendingUserSelectionIndex = -1;

    /// Loaded user selection awaiting a matching model snapshot, or -1 when none is pending.
    private int pendingModelSelectionIndex = -1;

    /// Whether programmatic selection restoration is suppressing command delegation.
    private boolean applyingSnapshot;

    /// Cross-thread gate preventing queued model updates after close begins.
    private volatile boolean closed;

    /// Whether the EDT has attempted every owned resource cleanup.
    private boolean resourcesClosed;

    /// Creates an installed-instance panel and attaches its internal management host on the EDT.
    ///
    /// @param model toolkit-neutral instance model and viewport source
    /// @param strings localized page text
    /// @param managementCoordinator coordinator owning all dynamic management views
    public InstancesPanel(
            InstancesModel model,
            InstancesStrings strings,
            InstanceManagementCoordinator managementCoordinator) {
        super(new CardLayout());
        EdtDispatcher.requireEventDispatchThread();
        this.model = Objects.requireNonNull(model, "model");
        this.strings = Objects.requireNonNull(strings, "strings");
        Objects.requireNonNull(managementCoordinator, "managementCoordinator");
        choiceList = new ViewportChoiceList<>(model, new InstanceListCellRenderer());

        @Nullable Subscription createdModelSubscription = null;
        @Nullable Subscription createdManagementHostLease = null;
        try {
            configureComponents();
            createdModelSubscription = Objects.requireNonNull(
                    model.subscribe(this::modelChanged),
                    "model returned null subscription");
            createdManagementHostLease = Objects.requireNonNull(
                    managementCoordinator.attachHost(managementHost),
                    "management coordinator returned null host lease");
            applySnapshot(model.snapshot());
        } catch (RuntimeException | Error failure) {
            @Nullable Throwable cleanupFailure = failure;
            @Nullable Subscription hostLease = createdManagementHostLease;
            @Nullable Subscription modelLease = createdModelSubscription;
            cleanupFailure = attempt(cleanupFailure, () -> unsubscribeNullable(hostLease));
            cleanupFailure = attempt(cleanupFailure, () -> unsubscribeNullable(modelLease));
            cleanupFailure = attempt(
                    cleanupFailure,
                    () -> choiceList.getChoiceModel().removeListDataListener(listDataListener));
            cleanupFailure = attempt(cleanupFailure, choiceList::close);
            rethrowUnchecked(Objects.requireNonNull(cleanupFailure));
            throw new AssertionError("Unchecked construction failure was not rethrown");
        }
        modelSubscription = Objects.requireNonNull(
                createdModelSubscription,
                "model subscription was not created");
        managementHostLease = Objects.requireNonNull(
                createdManagementHostLease,
                "management host lease was not created");
    }

    /// Returns the immutable snapshot currently represented by this page.
    ///
    /// @return displayed instance-page state
    public InstancesSnapshot displayedSnapshot() {
        EdtDispatcher.requireEventDispatchThread();
        return Objects.requireNonNull(displayedSnapshot, "initial instance snapshot was not applied");
    }

    /// Returns the viewport list for shell integrations and focused verification.
    ///
    /// @return viewport-driven instance list
    public ViewportChoiceList<InstanceListItem> choiceList() {
        return choiceList;
    }

    /// Releases the model subscription and viewport requests from any caller thread.
    @Override
    public void close() {
        closed = true;
        EdtDispatcher.executeAndWait(this::closeOnEventDispatchThread);
    }

    /// Builds the stable title, command, list, and status layout.
    private void configureComponents() {
        JPanel toolbar = new JPanel(new MigLayout(
                "insets 0, fillx",
                "[grow,fill][]12[]",
                "[]"));
        toolbar.setOpaque(false);

        JLabel heading = new JLabel(strings.pageTitle());
        heading.setName("instancesPageTitle");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 28.0F));
        toolbar.add(heading);

        refreshButton.setName("instancesRefresh");
        refreshButton.setText(strings.refreshAction());
        refreshButton.addActionListener(event -> {
            if (!closed) {
                model.refreshInstances();
            }
        });
        toolbar.add(refreshButton, "h 40!");

        addButton.setName("instancesAdd");
        addButton.setText(strings.addAction());
        addButton.addActionListener(event -> {
            if (!closed) {
                model.addInstance();
            }
        });
        toolbar.add(addButton, "h 40!");
        instancesWorkspace.setName("instancesListWorkspace");
        managementWorkspace.setName("instancesManagementHost");
        instancesWorkspace.add(toolbar, "growx");

        choiceList.setName("instancesList");
        JList<ChoiceListEntry<InstanceListItem>> list = choiceList.getList();
        list.setName("instancesListView");
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addListSelectionListener(event -> {
            if (!closed && !event.getValueIsAdjusting() && !applyingSnapshot) {
                pendingUserSelectionIndex = list.getSelectedIndex();
                pendingModelSelectionIndex = -1;
                manageButton.setEnabled(false);
                submitPendingUserSelection();
            }
        });
        choiceList.getChoiceModel().addListDataListener(listDataListener);

        JLabel emptyLabel = new JLabel(strings.emptyText(), SwingConstants.CENTER);
        emptyLabel.setName("instancesEmpty");
        listCards.add(choiceList, LIST_CARD);
        listCards.add(emptyLabel, EMPTY_CARD);
        instancesWorkspace.add(listCards, "grow");

        JPanel statusBand = new JPanel(new MigLayout(
                "insets 0, fillx",
                "[grow,fill][]",
                "[40!]"));
        statusBand.setOpaque(false);
        statusLabel.setName("instancesStatus");
        statusBand.add(statusLabel, "growx");

        manageButton.setName("instancesManage");
        manageButton.setText(strings.manageAction());
        manageButton.addActionListener(event -> {
            if (!closed) {
                model.manageSelectedInstance();
            }
        });
        statusBand.add(manageButton, "h 40!");
        instancesWorkspace.add(statusBand, "growx");

        add(instancesWorkspace, INSTANCES_VIEW_CARD);
        add(managementWorkspace, MANAGEMENT_VIEW_CARD);
        ((CardLayout) getLayout()).show(this, INSTANCES_VIEW_CARD);
    }

    /// Coalesces a worker-published transition to the model's latest snapshot on the EDT.
    ///
    /// @param change transition that invalidated the displayed page
    private void modelChanged(ValueChange<InstancesSnapshot> change) {
        Objects.requireNonNull(change, "change");
        EdtDispatcher.execute(() -> {
            if (!closed) {
                applySnapshot(model.snapshot());
            }
        });
    }

    /// Applies one immutable state and reloads only when indexed content changed.
    ///
    /// @param snapshot latest page state
    private void applySnapshot(InstancesSnapshot snapshot) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(snapshot, "snapshot");
        @Nullable InstancesSnapshot previous = displayedSnapshot;
        boolean contentChanged = previous == null
                || previous.contentRevision() != snapshot.contentRevision();
        displayedSnapshot = snapshot;

        if (contentChanged) {
            pendingUserSelectionIndex = -1;
            pendingModelSelectionIndex = -1;
            choiceList.reloadData();
        }

        if (pendingModelSelectionIndex >= 0
                && snapshot.selectedIndex().orElse(-1) == pendingModelSelectionIndex) {
            pendingModelSelectionIndex = -1;
        }

        ((CardLayout) listCards.getLayout()).show(
                listCards,
                snapshot.itemCount() == 0 ? EMPTY_CARD : LIST_CARD);
        if (pendingUserSelectionIndex < 0 && pendingModelSelectionIndex < 0) {
            restoreSelection(snapshot.selectedIndex());
        }

        choiceList.setEnabled(snapshot.listEnabled());
        choiceList.getList().setEnabled(snapshot.listEnabled());
        refreshButton.setText(snapshot.refreshing()
                ? strings.refreshingAction()
                : strings.refreshAction());
        refreshButton.setEnabled(snapshot.refreshEnabled());
        addButton.setEnabled(snapshot.addEnabled());
        manageButton.setEnabled(snapshot.manageEnabled()
                && pendingUserSelectionIndex < 0
                && pendingModelSelectionIndex < 0);
        statusLabel.setText(snapshot.statusText());
        statusLabel.setToolTipText(snapshot.statusText());
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

        @Nullable InstanceListItem selected = choiceList.getSelectedValue();
        if (selected != null) {
            pendingModelSelectionIndex = pendingUserSelectionIndex;
            pendingUserSelectionIndex = -1;
            model.selectInstance(selected.id());
        }
    }

    /// Attempts all owned cleanup in deterministic order on the EDT.
    private void closeOnEventDispatchThread() {
        EdtDispatcher.requireEventDispatchThread();
        if (resourcesClosed) {
            return;
        }
        resourcesClosed = true;
        pendingUserSelectionIndex = -1;
        pendingModelSelectionIndex = -1;
        choiceList.setEnabled(false);
        choiceList.getList().setEnabled(false);
        refreshButton.setEnabled(false);
        addButton.setEnabled(false);
        manageButton.setEnabled(false);

        @Nullable Throwable failure = null;
        failure = attempt(failure, () -> unsubscribeNullable(managementHostLease));
        failure = attempt(failure, modelSubscription::unsubscribe);
        failure = attempt(
                failure,
                () -> choiceList.getChoiceModel().removeListDataListener(listDataListener));
        failure = attempt(failure, choiceList::close);
        if (failure != null) {
            rethrowUnchecked(failure);
        }
    }

    /// Cancels a nullable construction or host subscription when present.
    ///
    /// @param subscription subscription to cancel, or null when no resource was acquired
    private static void unsubscribeNullable(@Nullable Subscription subscription) {
        if (subscription != null) {
            subscription.unsubscribe();
        }
    }

    /// Runs one cleanup action while retaining the first failure and suppressing later failures.
    ///
    /// @param primary first failure, or null before any failure
    /// @param action cleanup action to attempt
    /// @return first failure with later distinct failures suppressed, or null after success
    private static @Nullable Throwable attempt(
            @Nullable Throwable primary,
            Runnable action) {
        try {
            action.run();
            return primary;
        } catch (RuntimeException | Error failure) {
            if (primary == null) {
                return failure;
            }
            if (primary != failure) {
                primary.addSuppressed(failure);
            }
            return primary;
        }
    }

    /// Rethrows one unchecked lifecycle failure without changing its identity.
    ///
    /// @param failure lifecycle failure to propagate
    private static void rethrowUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Unexpected checked panel lifecycle failure", failure);
    }

    /// Card host implementation that never assumes ownership of a management view.
    @NotNullByDefault
    private final class PanelManagementHost implements InstanceManagementHost {
        /// Mounts one coordinator-owned component and reveals the management card.
        ///
        /// @param component dynamic management root
        @Override
        public void showManagementView(JComponent component) {
            EdtDispatcher.requireEventDispatchThread();
            Objects.requireNonNull(component, "component");
            managementWorkspace.removeAll();
            managementWorkspace.add(component, BorderLayout.CENTER);
            ((CardLayout) InstancesPanel.this.getLayout()).show(
                    InstancesPanel.this,
                    MANAGEMENT_VIEW_CARD);
            refreshManagementCards();
        }

        /// Removes the dynamic component and reveals the stable instances-list card.
        @Override
        public void showInstanceList() {
            EdtDispatcher.requireEventDispatchThread();
            managementWorkspace.removeAll();
            ((CardLayout) InstancesPanel.this.getLayout()).show(
                    InstancesPanel.this,
                    INSTANCES_VIEW_CARD);
            refreshManagementCards();
        }

        /// Revalidates and repaints the dynamic mount and root card container.
        private void refreshManagementCards() {
            managementWorkspace.revalidate();
            managementWorkspace.repaint();
            InstancesPanel.this.revalidate();
            InstancesPanel.this.repaint();
        }
    }
}

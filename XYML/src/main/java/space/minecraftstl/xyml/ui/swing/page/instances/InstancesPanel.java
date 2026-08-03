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
import space.minecraftstl.xyml.game.GameInstanceID;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingContentTransition;
import space.minecraftstl.xyml.ui.swing.SwingTextFields;
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
import javax.swing.SwingUtilities;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

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

    /// Card name used when the current search hides every installed instance.
    private static final String NO_SEARCH_RESULTS_CARD = "no-search-results";

    /// Toolkit-neutral instance source and command model.
    private final InstancesModel model;

    /// View-local name-and-ID projection that preserves viewport-driven row loading.
    private final FilteredInstancesDataSource filteredSource;

    /// Localized page text.
    private final InstancesStrings strings;

    /// Non-owning coordinator used to leave a dynamic management view from shell navigation.
    private final InstanceManagementCoordinator managementCoordinator;

    /// Viewport-measured single-choice list.
    private final ViewportChoiceList<InstanceListItem> choiceList;

    /// Visible instance-name search field.
    private final JTextField searchField = new JTextField();

    /// Stable workspace containing the list toolbar, lazy list, and status controls.
    private final JPanel instancesWorkspace = new JPanel(new MigLayout(
            "insets 0, fill, wrap 1",
            "[grow,fill]",
            "[]16[grow,fill]16[]"));

    /// Empty host whose sole child is the current coordinator-owned management component.
    private final JPanel managementWorkspace = new JPanel(new BorderLayout());

    /// Cards that switch between the lazy list and exact empty state.
    private final JPanel listCards = new JPanel(new CardLayout());

    /// Snapshot-composited transition between the instance list and management details.
    private final SwingContentTransition rootTransition;

    /// Refresh command.
    private final JButton refreshButton = new JButton();

    /// Add-instance command.
    private final JButton addButton = new JButton();

    /// Empty-state command that opens the same new-instance workflow.
    private final JButton emptyButton = new JButton();

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

    /// Listener that updates only the cheap search-index projection for any text edit.
    private final DocumentListener searchListener = new DocumentListener() {
        /// Applies an inserted query fragment.
        @Override
        public void insertUpdate(DocumentEvent event) {
            scheduleSearchChanged();
        }

        /// Applies a removed query fragment.
        @Override
        public void removeUpdate(DocumentEvent event) {
            scheduleSearchChanged();
        }

        /// Applies an attribute change for document implementations that publish one.
        @Override
        public void changedUpdate(DocumentEvent event) {
            scheduleSearchChanged();
        }
    };

    /// Snapshot currently represented by controls, or null before initialization.
    private @Nullable InstancesSnapshot displayedSnapshot;

    /// User-selected logical row waiting for its loaded value, or -1 when none is pending.
    private int pendingUserSelectionIndex = -1;

    /// Loaded user selection awaiting a matching model snapshot, or null when none is pending.
    private @Nullable String pendingModelSelectionId;

    /// Whether programmatic selection restoration is suppressing command delegation.
    private boolean applyingSnapshot;

    /// Whether one coalesced search update is already queued behind the current document mutation.
    private boolean searchUpdateQueued;

    /// Whether repository selection changes should keep the persistent management surface synchronized.
    private boolean persistentManagementRequested;

    /// Whether the next coordinator-provided management component should transition into view.
    private boolean animateNextManagementMount = true;

    /// Whether the instance-list side page is currently exposed by the shell.
    private boolean instanceListPageVisible;

    /// Shell callback clearing side-page selection when a list command opens instance management.
    private Runnable revealDefaultPageCommand = () -> { };

    /// Repository-selection context represented by the mounted management view.
    private long managementContextRevision = Long.MIN_VALUE;

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
        this.managementCoordinator = Objects.requireNonNull(managementCoordinator, "managementCoordinator");
        rootTransition = new SwingContentTransition(this);
        filteredSource = new FilteredInstancesDataSource(model);
        choiceList = new ViewportChoiceList<>(filteredSource, new InstanceListCellRenderer());

        @Nullable Subscription createdModelSubscription = null;
        @Nullable Subscription createdManagementHostLease = null;
        try {
            configureComponents();
            createdModelSubscription = Objects.requireNonNull(
                    model.subscribe(this::modelChanged),
                    "model returned null subscription");
            createdManagementHostLease = Objects.requireNonNull(
                    this.managementCoordinator.attachHost(managementHost),
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
            cleanupFailure = attempt(
                    cleanupFailure,
                    () -> searchField.getDocument().removeDocumentListener(searchListener));
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

    /// Returns whether the list and management-details root cards are currently transitioning.
    ///
    /// @return true while a cached outgoing root card remains active
    boolean isRootTransitionRunning() {
        EdtDispatcher.requireEventDispatchThread();
        return rootTransition.isRunning();
    }

    /// Closes any active instance-management view and restores the installed-instance list.
    ///
    /// @return asynchronous coordinator completion
    public CompletionStage<@Nullable Void> showInstanceList() {
        return managementCoordinator.returnToInstanceList();
    }

    /// Installs the shell callback used when a list action reveals the persistent management page.
    ///
    /// @param command non-owning shell navigation callback
    public void setRevealDefaultPageCommand(Runnable command) {
        EdtDispatcher.requireEventDispatchThread();
        revealDefaultPageCommand = Objects.requireNonNull(command, "command");
    }

    /// Reveals the installed-instance list as a side page without disposing persistent management state.
    public void showInstanceListPage() {
        EdtDispatcher.requireEventDispatchThread();
        persistentManagementRequested = false;
        instanceListPageVisible = true;
        if (!instancesWorkspace.isVisible()) {
            showRootCard(INSTANCES_VIEW_CARD, true, () -> { });
        }
    }

    /// Reveals management for the model-selected instance, creating or replacing it only when necessary.
    ///
    /// @return completion of any required coordinator transition
    public CompletionStage<@Nullable Void> showSelectedInstanceManagement() {
        return showSelectedInstanceManagement(true);
    }

    /// Reveals management while optionally suppressing a shell-preparation transition.
    ///
    /// @param animate whether a visible list-to-management replacement should animate
    /// @return completion of any required coordinator transition
    public CompletionStage<@Nullable Void> showSelectedInstanceManagement(boolean animate) {
        EdtDispatcher.requireEventDispatchThread();
        persistentManagementRequested = true;
        instanceListPageVisible = false;
        @Nullable String serializedSelectedId = selectedInstanceId(displayedSnapshot());
        if (serializedSelectedId == null) {
            managementContextRevision = Long.MIN_VALUE;
            animateNextManagementMount = animate;
            CompletionStage<@Nullable Void> completion = managementCoordinator.returnToInstanceList();
            persistentManagementRequested = true;
            return completion;
        }
        GameInstanceID selectedId = new GameInstanceID(serializedSelectedId);
        long requestedContextRevision = model.selectionContextRevision();
        if (selectedId.equals(managementCoordinator.currentInstanceId())
                && managementContextRevision == requestedContextRevision
                && managementWorkspace.getComponentCount() > 0) {
            if (!managementWorkspace.isVisible()) {
                showRootCard(MANAGEMENT_VIEW_CARD, animate, () -> { });
            }
            animateNextManagementMount = true;
            return CompletableFuture.completedFuture(null);
        }
        animateNextManagementMount = animate;
        CompletionStage<@Nullable Void> completion = managementCoordinator.open(selectedId);
        completion.whenComplete((@Nullable Void ignored, @Nullable Throwable failure) -> EdtDispatcher.execute(() -> {
            if (!closed) {
                if (failure == null
                        && selectedId.equals(managementCoordinator.currentInstanceId())
                        && requestedContextRevision == model.selectionContextRevision()) {
                    managementContextRevision = requestedContextRevision;
                } else if (failure != null) {
                    animateNextManagementMount = true;
                }
            }
        }));
        return completion;
    }

    /// Releases the model subscription and viewport requests from any caller thread.
    @Override
    public void close() {
        closed = true;
        EdtDispatcher.executeAndWait(this::closeOnEventDispatchThread);
    }

    /// Paints cached list or management frames during a transition and otherwise paints live details.
    ///
    /// @param graphics instance-page graphics
    @Override
    protected void paintChildren(Graphics graphics) {
        if (!rootTransition.paintFrames(graphics)) {
            super.paintChildren(graphics);
        }
    }

    /// Releases a cached internal transition before the instance page leaves the display hierarchy.
    @Override
    public void removeNotify() {
        rootTransition.settle();
        super.removeNotify();
    }

    /// Builds the stable title, command, list, and status layout.
    private void configureComponents() {
        setOpaque(false);
        instancesWorkspace.setOpaque(false);
        managementWorkspace.setOpaque(false);
        listCards.setName("instancesListCards");
        listCards.setOpaque(false);

        JPanel toolbar = new JPanel(new MigLayout(
                "insets 0, fillx",
                "[grow,fill][160:280:360,fill]12[]12[]",
                "[]"));
        toolbar.setOpaque(false);

        JLabel heading = new JLabel(strings.pageTitle());
        heading.setName("instancesPageTitle");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 28.0F));
        toolbar.add(heading);

        searchField.setName("instancesSearch");
        SwingTextFields.showClearButton(searchField);
        searchField.putClientProperty("JTextField.placeholderText", strings.searchText());
        searchField.getAccessibleContext().setAccessibleName(strings.searchText());
        searchField.getDocument().addDocumentListener(searchListener);
        toolbar.add(searchField, "h 40!");

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
        addButton.addActionListener(event -> requestAddInstance());
        toolbar.add(addButton, "h 40!");
        instancesWorkspace.setName("instancesListWorkspace");
        managementWorkspace.setName("instancesManagementHost");
        instancesWorkspace.add(toolbar, "growx");

        choiceList.setName("instancesList");
        choiceList.setOpaque(false);
        choiceList.getViewport().setOpaque(false);
        JList<ChoiceListEntry<InstanceListItem>> list = choiceList.getList();
        list.setName("instancesListView");
        list.setOpaque(false);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addListSelectionListener(event -> {
            if (!closed && !event.getValueIsAdjusting() && !applyingSnapshot) {
                pendingUserSelectionIndex = list.getSelectedIndex();
                pendingModelSelectionId = null;
                manageButton.setEnabled(false);
                submitPendingUserSelection();
            }
        });
        choiceList.getChoiceModel().addListDataListener(listDataListener);

        emptyButton.setName("instancesEmpty");
        emptyButton.setText(strings.emptyText());
        emptyButton.setBorderPainted(false);
        emptyButton.setContentAreaFilled(false);
        emptyButton.setFocusPainted(false);
        emptyButton.setOpaque(false);
        emptyButton.addActionListener(event -> requestAddInstance());
        JLabel noSearchResultsLabel = new JLabel(strings.noSearchResultsText(), SwingConstants.CENTER);
        noSearchResultsLabel.setName("instancesNoSearchResults");
        listCards.add(choiceList, LIST_CARD);
        listCards.add(emptyButton, EMPTY_CARD);
        listCards.add(noSearchResultsLabel, NO_SEARCH_RESULTS_CARD);
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
            pendingModelSelectionId = null;
            filteredSource.refreshSource();
            choiceList.reloadData();
        }

        @Nullable String selectedInstanceId = selectedInstanceId(snapshot);
        if (pendingModelSelectionId != null
                && pendingModelSelectionId.equals(selectedInstanceId)) {
            pendingModelSelectionId = null;
        }

        OptionalInt visibleSelectedIndex = selectedInstanceId == null
                ? OptionalInt.empty()
                : filteredSource.displayIndexOf(selectedInstanceId);

        ((CardLayout) listCards.getLayout()).show(
                listCards,
                snapshot.itemCount() == 0
                        ? EMPTY_CARD
                        : filteredSource.exactItemCount().orElseThrow() == 0
                                ? NO_SEARCH_RESULTS_CARD
                                : LIST_CARD);
        if (pendingUserSelectionIndex < 0 && pendingModelSelectionId == null) {
            restoreSelection(visibleSelectedIndex);
        }

        choiceList.setEnabled(snapshot.listEnabled());
        choiceList.getList().setEnabled(snapshot.listEnabled());
        searchField.setEnabled(snapshot.listEnabled());
        refreshButton.setText(snapshot.refreshing()
                ? strings.refreshingAction()
                : strings.refreshAction());
        refreshButton.setEnabled(snapshot.refreshEnabled());
        addButton.setEnabled(snapshot.addEnabled());
        emptyButton.setEnabled(snapshot.addEnabled());
        manageButton.setEnabled(snapshot.manageEnabled()
                && visibleSelectedIndex.isPresent()
                && pendingUserSelectionIndex < 0
                && pendingModelSelectionId == null);
        statusLabel.setText(snapshot.statusText());
        statusLabel.setToolTipText(snapshot.statusText());
        if (persistentManagementRequested
                && (!Objects.equals(selectedInstanceId, managementCoordinator.currentInstanceId())
                        || managementContextRevision != model.selectionContextRevision())) {
            showSelectedInstanceManagement().toCompletableFuture().join();
        }
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
            pendingModelSelectionId = selected.id().id();
            pendingUserSelectionIndex = -1;
            model.selectInstance(selected.id());
        }
    }

    /// Coalesces compound document mutations such as `setText` into one filtered-list reload.
    private void scheduleSearchChanged() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || searchUpdateQueued) {
            return;
        }
        searchUpdateQueued = true;
        SwingUtilities.invokeLater(this::applySearchQuery);
    }

    /// Applies the latest user query to the cheap search index without resolving hidden row details.
    private void applySearchQuery() {
        EdtDispatcher.requireEventDispatchThread();
        searchUpdateQueued = false;
        if (closed || !filteredSource.setQuery(searchField.getText())) {
            return;
        }
        pendingUserSelectionIndex = -1;
        pendingModelSelectionId = null;
        choiceList.reloadData();
        @Nullable InstancesSnapshot snapshot = displayedSnapshot;
        if (snapshot != null) {
            applySnapshot(snapshot);
        }
    }

    /// Resolves the model's selected source index to its stable repository identifier.
    ///
    /// @param snapshot current model snapshot
    /// @return selected stable identifier, or null when selection is absent or inconsistent
    private @Nullable String selectedInstanceId(InstancesSnapshot snapshot) {
        OptionalInt selectedIndex = snapshot.selectedIndex();
        if (selectedIndex.isEmpty()) {
            return null;
        }
        @Unmodifiable List<String> stableIds = model.stableItemIds();
        int index = selectedIndex.getAsInt();
        return index < stableIds.size() ? stableIds.get(index) : null;
    }

    /// Attempts all owned cleanup in deterministic order on the EDT.
    private void closeOnEventDispatchThread() {
        EdtDispatcher.requireEventDispatchThread();
        if (resourcesClosed) {
            return;
        }
        resourcesClosed = true;
        rootTransition.settle();
        revealDefaultPageCommand = () -> { };
        pendingUserSelectionIndex = -1;
        pendingModelSelectionId = null;
        choiceList.setEnabled(false);
        choiceList.getList().setEnabled(false);
        searchField.setEnabled(false);
        refreshButton.setEnabled(false);
        addButton.setEnabled(false);
        emptyButton.setEnabled(false);
        manageButton.setEnabled(false);

        @Nullable Throwable failure = null;
        failure = attempt(failure, () -> unsubscribeNullable(managementHostLease));
        failure = attempt(failure, modelSubscription::unsubscribe);
        failure = attempt(
                failure,
                () -> choiceList.getChoiceModel().removeListDataListener(listDataListener));
        failure = attempt(
                failure,
                () -> searchField.getDocument().removeDocumentListener(searchListener));
        failure = attempt(failure, choiceList::close);
        if (failure != null) {
            rethrowUnchecked(failure);
        }
    }

    /// Opens the shared new-instance workflow only while this panel remains active.
    private void requestAddInstance() {
        if (!closed) {
            model.addInstance();
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

    /// Revalidates and repaints both root cards after a direct shell-level page switch.
    private void refreshRootCards() {
        managementWorkspace.revalidate();
        managementWorkspace.repaint();
        revalidate();
        repaint();
    }

    /// Applies one root-card replacement with optional inherited snapshot animation.
    ///
    /// @param card destination card identifier
    /// @param animate whether to animate between the currently visible and destination cards
    /// @param preparation content mutation that must run immediately before card selection
    private void showRootCard(String card, boolean animate, Runnable preparation) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(card, "card");
        Objects.requireNonNull(preparation, "preparation");
        @Nullable JComponent outgoing = instancesWorkspace.isVisible()
                ? instancesWorkspace
                : managementWorkspace.isVisible() ? managementWorkspace : null;
        Runnable replacement = () -> {
            preparation.run();
            ((CardLayout) getLayout()).show(this, card);
        };
        if (animate) {
            SwingContentTransition.Direction direction = MANAGEMENT_VIEW_CARD.equals(card)
                    ? SwingContentTransition.Direction.HORIZONTAL_FORWARD
                    : SwingContentTransition.Direction.HORIZONTAL_BACKWARD;
            rootTransition.transitionFrom(outgoing, direction, replacement);
        } else {
            rootTransition.settle();
            replacement.run();
        }
        refreshRootCards();
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
            boolean revealDefaultPage = instanceListPageVisible;
            persistentManagementRequested = true;
            instanceListPageVisible = false;
            managementContextRevision = model.selectionContextRevision();
            boolean animate = animateNextManagementMount;
            animateNextManagementMount = true;
            showRootCard(MANAGEMENT_VIEW_CARD, animate, () -> {
                managementWorkspace.removeAll();
                managementWorkspace.add(component, BorderLayout.CENTER);
            });
            if (revealDefaultPage) {
                revealDefaultPageCommand.run();
            }
        }

        /// Removes the dynamic component and reveals the stable instances-list card.
        @Override
        public void showInstanceList() {
            EdtDispatcher.requireEventDispatchThread();
            if (!persistentManagementRequested) {
                instanceListPageVisible = true;
            }
            managementContextRevision = Long.MIN_VALUE;
            boolean animate = animateNextManagementMount;
            animateNextManagementMount = true;
            showRootCard(INSTANCES_VIEW_CARD, animate, managementWorkspace::removeAll);
        }
    }
}

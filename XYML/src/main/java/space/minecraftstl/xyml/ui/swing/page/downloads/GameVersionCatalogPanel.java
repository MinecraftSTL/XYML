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
package space.minecraftstl.xyml.ui.swing.page.downloads;

import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.install.GameInstallAlreadyRunningException;
import space.minecraftstl.xyml.game.install.GameInstallRequest;
import space.minecraftstl.xyml.game.install.GameInstallRequestRejectedException;
import space.minecraftstl.xyml.game.install.GameInstallService;
import space.minecraftstl.xyml.game.install.GameInstallSession;
import space.minecraftstl.xyml.game.install.GameInstallStatus;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;
import space.minecraftstl.xyml.ui.swing.choice.ChoiceListEntry;
import space.minecraftstl.xyml.ui.swing.choice.ViewportChoiceList;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressHostPanel;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Font;
import java.time.Duration;
import java.util.Objects;
import java.util.OptionalInt;

/// Presents a lazily loaded game-version catalog and one vanilla installation workflow.
///
/// Construction performs no catalog I/O. The first Swing display notification asks the model to
/// load if still idle. Installation consumes only a visible loaded choice and never changes the
/// viewport strategy. The panel owns subscriptions and progress views, while the caller retains
/// ownership of the catalog model, its source, and the installation service.
@NotNullByDefault
public final class GameVersionCatalogPanel extends JPanel implements AutoCloseable {
    /// Card shown before the lazy load starts and while it is active.
    private static final String LOADING_CARD = "loading";

    /// Card shown when an uncached catalog load fails.
    private static final String FAILED_CARD = "failed";

    /// Card shown after a successful query has no visible result.
    private static final String EMPTY_CARD = "empty";

    /// Card shown when visible catalog rows are available.
    private static final String LIST_CARD = "list";

    /// Card containing catalog selection and the installation request controls.
    private static final String CATALOG_VIEW = "catalog";

    /// Card containing the current installation task and terminal return command.
    private static final String TASK_VIEW = "task";

    /// Lock guarding close state and queued model-change revisions.
    private final Object stateLock = new Object();

    /// Serializes EDT state application with synchronous close cleanup.
    private final Object publicationLock = new Object();

    /// Toolkit-neutral catalog model and viewport source.
    private final GameVersionCatalogModel model;

    /// Application-owned single-flight vanilla installation service.
    private final GameInstallService installService;

    /// Localized control text.
    private final GameVersionCatalogStrings strings;

    /// Localized installation controls, task text, and validation feedback.
    private final GameInstallStrings installStrings;

    /// Viewport-measured single-choice list.
    private final ViewportChoiceList<GameVersionCatalogItem> choiceList;

    /// Cards representing lazy loading, failure, empty results, and visible rows.
    private final JPanel contentCards = new JPanel(new CardLayout());

    /// Stable card host switching between the catalog and retained task details.
    private final JPanel workflowCards = new JPanel(new CardLayout());

    /// Owns the current installation task presentation panel.
    private final TaskProgressHostPanel taskProgressHost;

    /// Version-ID query editor.
    private final JTextField searchField = new JTextField();

    /// Game-version kind selector.
    private final JComboBox<GameVersionFilter> filterBox = new JComboBox<>(GameVersionFilter.values());

    /// Source refresh command.
    private final JButton refreshButton = new JButton();

    /// Current model status, including retained-content refresh failures.
    private final JLabel statusLabel = new JLabel();

    /// Exact destination instance-name editor.
    private final JTextField instanceNameField = new JTextField();

    /// Command that captures the loaded selected version and exact instance name.
    private final JButton installButton = new JButton();

    /// Command that dismisses a terminal task and restores the catalog card.
    private final JButton backToCatalogButton = new JButton();

    /// Localized installation validation or terminal-failure feedback.
    private final JLabel installStatusLabel = new JLabel();

    /// Localized terminal installation feedback shown beside the return command.
    private final JLabel taskStatusLabel = new JLabel();

    /// Status text displayed inside the loading card.
    private final JLabel loadingLabel = stateLabel("gameVersionsLoading");

    /// Status text displayed inside the uncached failure card.
    private final JLabel failedLabel = stateLabel("gameVersionsFailed");

    /// Status text displayed inside the exact empty card.
    private final JLabel emptyLabel = stateLabel("gameVersionsEmpty");

    /// Listener that propagates user query edits to the model.
    private final DocumentListener searchListener = new DocumentListener() {
        /// Delegates inserted query text.
        @Override
        public void insertUpdate(DocumentEvent event) {
            queryChanged();
        }

        /// Delegates removed query text.
        @Override
        public void removeUpdate(DocumentEvent event) {
            queryChanged();
        }

        /// Delegates changed query attributes.
        @Override
        public void changedUpdate(DocumentEvent event) {
            queryChanged();
        }
    };

    /// Listener that preserves user-authored names and updates installation eligibility.
    private final DocumentListener instanceNameListener = new DocumentListener() {
        /// Reconciles inserted destination text.
        @Override
        public void insertUpdate(DocumentEvent event) {
            instanceNameChanged();
        }

        /// Reconciles removed destination text.
        @Override
        public void removeUpdate(DocumentEvent event) {
            instanceNameChanged();
        }

        /// Reconciles changed destination attributes.
        @Override
        public void changedUpdate(DocumentEvent event) {
            instanceNameChanged();
        }
    };

    /// Listener that commits a user-selected placeholder after its row finishes loading.
    private final ListDataListener listDataListener = new ListDataListener() {
        /// Rechecks a changed loaded row.
        @Override
        public void intervalAdded(ListDataEvent event) {
            submitPendingUserSelection();
            synchronizeLoadedSelection();
        }

        /// Rechecks a changed loaded row.
        @Override
        public void intervalRemoved(ListDataEvent event) {
            submitPendingUserSelection();
            synchronizeLoadedSelection();
        }

        /// Rechecks a changed loaded row.
        @Override
        public void contentsChanged(ListDataEvent event) {
            submitPendingUserSelection();
            synchronizeLoadedSelection();
        }
    };

    /// Owned model listener registration.
    private final Subscription modelSubscription;

    /// Owned status listener for the currently displayed installation session.
    private @Nullable Subscription installStatusSubscription;

    /// Snapshot currently represented by controls, or null before initialization.
    private @Nullable GameVersionCatalogSnapshot displayedSnapshot;

    /// User-selected logical row waiting for its loaded value, or -1 when none is pending.
    private int pendingUserSelectionIndex = -1;

    /// Stable version ID represented by the current visible loaded choice, or null otherwise.
    private @Nullable String selectedVersionId;

    /// Last version-derived instance name, or null after the user authored a different value.
    private @Nullable String suggestedInstanceName;

    /// Installation session retained by the task card, or null while the catalog card is active.
    private @Nullable GameInstallSession displayedInstallSession;

    /// Whether programmatic control restoration suppresses model commands.
    private boolean applyingSnapshot;

    /// Whether a version-derived destination update suppresses user-edit detection.
    private boolean applyingInstanceNameSuggestion;

    /// Revision invalidating older worker-to-EDT state applications.
    private long updateRevision;

    /// Revision invalidating queued installation-status applications.
    private long installRevision;

    /// Whether this panel has already delegated its one lazy initial-load request.
    private boolean initialLoadRequested;

    /// Whether the catalog card rather than task progress is currently visible.
    private boolean catalogViewVisible = true;

    /// Whether this panel has released its subscription and viewport resources.
    private boolean closed;

    /// Creates a game-version catalog panel on the Swing event dispatch thread.
    ///
    /// @param model toolkit-neutral lazy catalog model
    /// @param installService application-owned single-flight vanilla installer
    /// @param strings localized catalog text
    /// @param installStrings localized installation text
    /// @param taskProgressStrings localized task-progress controls and lifecycle states
    /// @param animator optional shared progress animator
    /// @param progressAnimationDuration non-negative installation-progress animation duration
    public GameVersionCatalogPanel(
            GameVersionCatalogModel model,
            GameInstallService installService,
            GameVersionCatalogStrings strings,
            GameInstallStrings installStrings,
            TaskProgressStrings taskProgressStrings,
            @Nullable SwingAnimator animator,
            Duration progressAnimationDuration) {
        super(new MigLayout(
                "insets 0, fill, wrap 1",
                "[grow,fill]",
                "[]12[grow,fill]"));
        EdtDispatcher.requireEventDispatchThread();
        this.model = Objects.requireNonNull(model, "model");
        this.installService = Objects.requireNonNull(installService, "installService");
        this.strings = Objects.requireNonNull(strings, "strings");
        this.installStrings = Objects.requireNonNull(installStrings, "installStrings");
        taskProgressHost = new TaskProgressHostPanel(
                Objects.requireNonNull(taskProgressStrings, "taskProgressStrings"),
                animator,
                Objects.requireNonNull(progressAnimationDuration, "progressAnimationDuration"));
        choiceList = new ViewportChoiceList<>(model, GameVersionCatalogItem::versionId);

        configureComponents();
        modelSubscription = model.subscribe(this::modelChanged);
        applySnapshot(model.snapshot());
    }

    /// Returns the immutable snapshot currently represented by the page.
    ///
    /// @return displayed catalog state
    public GameVersionCatalogSnapshot displayedSnapshot() {
        EdtDispatcher.requireEventDispatchThread();
        return Objects.requireNonNull(displayedSnapshot, "initial catalog snapshot was not applied");
    }

    /// Returns the viewport list for shell integration and focused verification.
    ///
    /// @return viewport-driven game-version list
    public ViewportChoiceList<GameVersionCatalogItem> choiceList() {
        return choiceList;
    }

    /// Starts the lazy source load after this page first becomes displayable.
    @Override
    public void addNotify() {
        super.addNotify();
        synchronized (stateLock) {
            if (closed || initialLoadRequested) {
                return;
            }
            initialLoadRequested = true;
        }
        model.loadIfNeeded();
    }

    /// Synchronously gates future updates and releases Swing resources from any caller thread.
    @Override
    public void close() {
        synchronized (stateLock) {
            if (!closed) {
                closed = true;
                updateRevision++;
                installRevision++;
            }
        }

        @Nullable Throwable cleanupFailure = null;
        try {
            EdtDispatcher.executeAndWait(this::closeOnEventDispatchThread);
        } catch (RuntimeException | Error failure) {
            cleanupFailure = combineUncheckedFailures(cleanupFailure, failure);
        }
        throwUncheckedFailure(cleanupFailure);
    }

    /// Builds the stable title, catalog workspace, and installation-task workspace.
    private void configureComponents() {
        JPanel headingBand = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill][]", "[]"));
        headingBand.setOpaque(false);

        JLabel heading = new JLabel(strings.pageTitle());
        heading.setName("gameVersionsPageTitle");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 28.0F));
        headingBand.add(heading);

        refreshButton.setName("gameVersionsRefresh");
        refreshButton.addActionListener(event -> {
            if (isOpen() && catalogViewVisible) {
                model.refresh();
            }
        });
        headingBand.add(refreshButton, "h 40!");
        add(headingBand, "growx");

        JPanel catalogWorkspace = new JPanel(new MigLayout(
                "insets 0, fill, wrap 1",
                "[grow,fill]",
                "[]12[grow,fill]8[]8[]"));
        catalogWorkspace.setOpaque(false);
        catalogWorkspace.setName("gameVersionsCatalogWorkspace");

        JPanel filterBand = new JPanel(new MigLayout(
                "insets 0, fillx",
                "[][grow,fill]16[][220!]",
                "[40!]"));
        filterBand.setOpaque(false);

        JLabel searchLabel = new JLabel(strings.searchLabel());
        searchLabel.setLabelFor(searchField);
        filterBand.add(searchLabel);
        searchField.setName("gameVersionsSearch");
        searchField.getDocument().addDocumentListener(searchListener);
        filterBand.add(searchField, "growx, h 40!");

        JLabel filterLabel = new JLabel(strings.filterLabel());
        filterLabel.setLabelFor(filterBox);
        filterBand.add(filterLabel);
        filterBox.setName("gameVersionsFilter");
        filterBox.setRenderer(new FilterRenderer());
        filterBox.addActionListener(event -> filterChanged());
        filterBand.add(filterBox, "h 40!");
        catalogWorkspace.add(filterBand, "growx");

        choiceList.setName("gameVersionsList");
        JList<ChoiceListEntry<GameVersionCatalogItem>> list = choiceList.getList();
        list.setName("gameVersionsListView");
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !applyingSnapshot && isOpen()) {
                pendingUserSelectionIndex = list.getSelectedIndex();
                submitPendingUserSelection();
                synchronizeLoadedSelection();
            }
        });
        choiceList.getChoiceModel().addListDataListener(listDataListener);

        contentCards.add(loadingLabel, LOADING_CARD);
        contentCards.add(failedLabel, FAILED_CARD);
        contentCards.add(emptyLabel, EMPTY_CARD);
        contentCards.add(choiceList, LIST_CARD);
        catalogWorkspace.add(contentCards, "grow");

        statusLabel.setName("gameVersionsStatus");
        catalogWorkspace.add(statusLabel, "growx, h 28!");

        JPanel installBand = new JPanel(new MigLayout(
                "insets 0, fillx, wrap 3",
                "[][grow,fill]16[220!]",
                "[40!]4[]"));
        installBand.setOpaque(false);
        installBand.setName("gameVersionsInstallBand");

        JLabel instanceNameLabel = new JLabel(installStrings.instanceNameLabel());
        instanceNameLabel.setLabelFor(instanceNameField);
        installBand.add(instanceNameLabel);
        instanceNameField.setName("gameVersionsInstanceName");
        instanceNameField.getDocument().addDocumentListener(instanceNameListener);
        installBand.add(instanceNameField, "growx, h 40!");
        installButton.setName("gameVersionsInstall");
        installButton.setText(installStrings.installAction());
        installButton.putClientProperty("JButton.buttonType", "roundRect");
        installButton.addActionListener(event -> startInstallation());
        installBand.add(installButton, "grow, h 40!");

        installStatusLabel.setName("gameVersionsInstallStatus");
        installBand.add(installStatusLabel, "skip 1, span 2, growx, h 24!");
        catalogWorkspace.add(installBand, "growx");

        JPanel taskWorkspace = new JPanel(new MigLayout(
                "insets 0, fill, wrap 1",
                "[grow,fill]",
                "[grow,fill]12[]"));
        taskWorkspace.setOpaque(false);
        taskWorkspace.setName("gameVersionsTaskWorkspace");
        taskProgressHost.setName("gameVersionsInstallProgress");
        taskWorkspace.add(taskProgressHost, "grow");

        JPanel taskActions = new JPanel(new MigLayout(
                "insets 0, fillx",
                "[grow,fill][220!]",
                "[40!]"));
        taskActions.setOpaque(false);
        taskStatusLabel.setName("gameVersionsInstallTaskStatus");
        taskActions.add(taskStatusLabel, "growx");
        backToCatalogButton.setName("gameVersionsBackToCatalog");
        backToCatalogButton.setText(installStrings.backToCatalogAction());
        backToCatalogButton.addActionListener(event -> showCatalogAfterTerminalTask());
        taskActions.add(backToCatalogButton, "grow, h 40!");
        taskWorkspace.add(taskActions, "growx");

        workflowCards.setOpaque(false);
        workflowCards.setName("gameVersionsWorkflowCards");
        workflowCards.add(catalogWorkspace, CATALOG_VIEW);
        workflowCards.add(taskWorkspace, TASK_VIEW);
        add(workflowCards, "grow");

        updateInstallAction();
    }

    /// Coalesces a model transition to its latest snapshot on the EDT.
    ///
    /// @param change transition that invalidated the displayed page
    private void modelChanged(ValueChange<GameVersionCatalogSnapshot> change) {
        Objects.requireNonNull(change, "change");
        long requestedRevision;
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            requestedRevision = ++updateRevision;
        }
        EdtDispatcher.execute(() -> applyLatestSnapshot(requestedRevision));
    }

    /// Applies the latest model snapshot when no newer update or close superseded it.
    ///
    /// @param requestedRevision revision captured by the model notification
    private void applyLatestSnapshot(long requestedRevision) {
        EdtDispatcher.requireEventDispatchThread();
        synchronized (publicationLock) {
            synchronized (stateLock) {
                if (closed || requestedRevision != updateRevision) {
                    return;
                }
            }
            applySnapshot(model.snapshot());
        }
    }

    /// Applies one immutable state and reloads only when indexed content changed.
    ///
    /// @param snapshot latest catalog state
    private void applySnapshot(GameVersionCatalogSnapshot snapshot) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(snapshot, "snapshot");
        @Nullable GameVersionCatalogSnapshot previous = displayedSnapshot;
        boolean contentChanged = previous == null
                || previous.contentRevision() != snapshot.contentRevision();
        displayedSnapshot = snapshot;

        applyingSnapshot = true;
        try {
            if (!searchField.getText().equals(snapshot.query())) {
                searchField.setText(snapshot.query());
            }
            if (filterBox.getSelectedItem() != snapshot.filter()) {
                filterBox.setSelectedItem(snapshot.filter());
            }
            if (contentChanged) {
                pendingUserSelectionIndex = -1;
                choiceList.reloadData();
            }
            restoreSelection(snapshot.selectedIndex());
        } finally {
            applyingSnapshot = false;
        }

        String contentCard = selectContentCard(snapshot);
        loadingLabel.setText(snapshot.statusText());
        failedLabel.setText(snapshot.statusText());
        emptyLabel.setText(snapshot.statusText());
        ((CardLayout) contentCards.getLayout()).show(contentCards, contentCard);

        choiceList.setEnabled(snapshot.listEnabled());
        choiceList.getList().setEnabled(snapshot.listEnabled());
        refreshButton.setText(snapshot.status() == GameVersionCatalogStatus.LOADING
                ? strings.refreshingAction()
                : strings.refreshAction());
        refreshButton.setEnabled(catalogViewVisible && snapshot.refreshEnabled());
        statusLabel.setText(snapshot.statusText());
        statusLabel.setToolTipText(snapshot.statusText());
        synchronizeLoadedSelection();
        updateInstallAction();
    }

    /// Restores the model-selected row without delegating it back as a user command.
    ///
    /// @param selectedIndex selected filtered index, or empty for no visible selection
    private void restoreSelection(OptionalInt selectedIndex) {
        int targetIndex = selectedIndex.orElse(-1);
        if (targetIndex >= choiceList.getChoiceModel().getSize()) {
            targetIndex = -1;
        }
        if (choiceList.getList().getSelectedIndex() == targetIndex) {
            return;
        }

        pendingUserSelectionIndex = -1;
        choiceList.getList().setSelectedIndex(targetIndex);
        if (targetIndex >= 0) {
            choiceList.getList().ensureIndexIsVisible(targetIndex);
        }
        choiceList.refreshLoadPlan();
    }

    /// Delegates the current query after a user document edit.
    private void queryChanged() {
        if (!applyingSnapshot && isOpen()) {
            model.setQuery(searchField.getText());
        }
    }

    /// Delegates the current kind after a user combo-box change.
    private void filterChanged() {
        if (applyingSnapshot || !isOpen()) {
            return;
        }
        @Nullable Object selected = filterBox.getSelectedItem();
        if (selected instanceof GameVersionFilter filter) {
            model.setFilter(filter);
        }
    }

    /// Commits a pending user selection once its sparse row has loaded.
    private void submitPendingUserSelection() {
        EdtDispatcher.requireEventDispatchThread();
        synchronized (stateLock) {
            if (closed) {
                return;
            }
        }
        if (applyingSnapshot || pendingUserSelectionIndex < 0
                || choiceList.getList().getSelectedIndex() != pendingUserSelectionIndex) {
            return;
        }

        @Nullable GameVersionCatalogItem selected = choiceList.getSelectedValue();
        if (selected != null) {
            pendingUserSelectionIndex = -1;
            model.selectVersion(selected.versionId());
        }
    }

    /// Synchronizes installation input with the visible loaded single-choice row.
    ///
    /// A sparse placeholder never becomes installable. The suggested name follows version changes only
    /// while the field is blank or still equals the previous suggestion, preserving user-authored text.
    private void synchronizeLoadedSelection() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable GameVersionCatalogSnapshot snapshot = displayedSnapshot;
        @Nullable GameVersionCatalogItem selected = snapshot != null && snapshot.listEnabled()
                ? choiceList.getSelectedValue()
                : null;
        if (selected == null) {
            selectedVersionId = null;
            updateInstallAction();
            return;
        }

        String versionId = selected.versionId();
        if (!versionId.equals(selectedVersionId)) {
            installStatusLabel.setText("");
            installStatusLabel.setToolTipText(null);
            String currentName = instanceNameField.getText();
            boolean mayReplace = currentName.isBlank()
                    || Objects.equals(currentName, suggestedInstanceName);
            selectedVersionId = versionId;
            if (mayReplace) {
                applyInstanceNameSuggestion(versionId);
            } else {
                suggestedInstanceName = null;
            }
        }
        updateInstallAction();
    }

    /// Applies one version-derived destination without classifying its document events as user edits.
    ///
    /// @param versionId exact selected version ID used as the complete suggestion
    private void applyInstanceNameSuggestion(String versionId) {
        suggestedInstanceName = Objects.requireNonNull(versionId, "versionId");
        applyingInstanceNameSuggestion = true;
        try {
            instanceNameField.setText(versionId);
        } finally {
            applyingInstanceNameSuggestion = false;
        }
    }

    /// Records intentional destination edits and refreshes installation eligibility.
    private void instanceNameChanged() {
        EdtDispatcher.requireEventDispatchThread();
        if (!applyingInstanceNameSuggestion
                && suggestedInstanceName != null
                && !instanceNameField.getText().equals(suggestedInstanceName)) {
            suggestedInstanceName = null;
        }
        installStatusLabel.setText("");
        updateInstallAction();
    }

    /// Starts installation from the exact loaded choice and exact destination field value.
    private void startInstallation() {
        EdtDispatcher.requireEventDispatchThread();
        if (!installButton.isEnabled() || !isOpen() || !catalogViewVisible) {
            return;
        }

        @Nullable GameVersionCatalogItem selected = choiceList.getSelectedValue();
        @Nullable String versionId = selectedVersionId;
        if (selected == null || versionId == null || !versionId.equals(selected.versionId())) {
            synchronizeLoadedSelection();
            return;
        }

        GameInstallRequest request = new GameInstallRequest(instanceNameField.getText(), versionId);
        final GameInstallSession session;
        try {
            session = Objects.requireNonNull(
                    installService.install(request),
                    "game install service returned null session");
        } catch (GameInstallAlreadyRunningException conflict) {
            installStatusLabel.setText(installStrings.installationAlreadyRunningStatus());
            installStatusLabel.setToolTipText(installStatusLabel.getText());
            updateInstallAction();
            return;
        } catch (RuntimeException failure) {
            installStatusLabel.setText(installStrings.installationFailedStatus());
            installStatusLabel.setToolTipText(installStatusLabel.getText());
            updateInstallAction();
            return;
        }

        installStatusLabel.setText("");
        installStatusLabel.setToolTipText(null);
        presentInstallSession(session);
    }

    /// Binds one returned installation session and switches to its retained task card.
    ///
    /// @param session newly returned installation session
    private void presentInstallSession(GameInstallSession session) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(session, "session");

        @Nullable Subscription replacementSubscription = null;
        try {
            replacementSubscription = session.statusProperty().subscribe(
                    change -> installStatusChanged(session, change));
            taskProgressHost.bind(session);
        } catch (RuntimeException | Error bindingFailure) {
            rollbackUnpresentedSession(session, replacementSubscription, bindingFailure);
            throw new AssertionError("installation presentation failure was lost", bindingFailure);
        }

        Subscription installedSubscription = Objects.requireNonNull(
                replacementSubscription,
                "installation status subscription was not acquired");

        @Nullable Subscription previousSubscription;
        boolean rejectedByClose;
        synchronized (stateLock) {
            rejectedByClose = closed;
            if (rejectedByClose) {
                previousSubscription = null;
            } else {
                previousSubscription = installStatusSubscription;
                installStatusSubscription = installedSubscription;
                displayedInstallSession = session;
                installRevision++;
            }
        }
        if (rejectedByClose) {
            @Nullable Throwable cleanupFailure = null;
            cleanupFailure = attemptCleanup(cleanupFailure, installedSubscription::unsubscribe);
            cleanupFailure = attemptCleanup(cleanupFailure, taskProgressHost::clear);
            cleanupFailure = attemptCleanup(cleanupFailure, session::cancel);
            throwUncheckedFailure(cleanupFailure);
            return;
        }
        unsubscribe(previousSubscription);

        catalogViewVisible = false;
        ((CardLayout) workflowCards.getLayout()).show(workflowCards, TASK_VIEW);
        refreshButton.setEnabled(false);
        applyInstallStatus(session);
    }

    /// Cancels a session that cannot be represented and restores visible catalog failure state.
    ///
    /// Every cleanup step is attempted before the original unchecked failure is rethrown. This prevents
    /// a started installation from retaining the single-flight slot without a progress or cancellation UI.
    ///
    /// @param session session that could not be presented
    /// @param statusSubscription partially acquired status subscription, or null
    /// @param bindingFailure original presentation failure
    private void rollbackUnpresentedSession(
            GameInstallSession session,
            @Nullable Subscription statusSubscription,
            Throwable bindingFailure) {
        @Nullable Throwable combinedFailure = Objects.requireNonNull(bindingFailure, "bindingFailure");
        combinedFailure = attemptCleanup(
                combinedFailure,
                () -> unsubscribe(statusSubscription));
        combinedFailure = attemptCleanup(combinedFailure, taskProgressHost::clear);
        combinedFailure = attemptCleanup(combinedFailure, session::cancel);
        installStatusLabel.setText(installStrings.installationFailedStatus());
        installStatusLabel.setToolTipText(installStatusLabel.getText());
        updateInstallAction();
        throwUncheckedFailure(combinedFailure);
    }

    /// Coalesces a worker-published installation transition to the current session on the EDT.
    ///
    /// @param session session whose status changed
    /// @param change status transition that invalidated task actions and feedback
    private void installStatusChanged(
            GameInstallSession session,
            ValueChange<GameInstallStatus> change) {
        Objects.requireNonNull(change, "change");
        long requestedRevision;
        synchronized (stateLock) {
            if (closed || displayedInstallSession != session) {
                return;
            }
            requestedRevision = installRevision;
        }
        SwingUiDispatcher.INSTANCE.dispatchOrRun(
                () -> applyLatestInstallStatus(session, requestedRevision));
    }

    /// Applies a queued installation transition only while its identity and revision remain current.
    ///
    /// @param session session captured by the worker callback
    /// @param requestedRevision installation revision captured by the callback
    private void applyLatestInstallStatus(
            GameInstallSession session,
            long requestedRevision) {
        EdtDispatcher.requireEventDispatchThread();
        synchronized (publicationLock) {
            synchronized (stateLock) {
                if (closed
                        || displayedInstallSession != session
                        || installRevision != requestedRevision) {
                    return;
                }
            }
            applyInstallStatus(session);
        }
    }

    /// Updates task feedback and terminal return availability from authoritative session state.
    ///
    /// @param session currently displayed installation session
    private void applyInstallStatus(GameInstallSession session) {
        EdtDispatcher.requireEventDispatchThread();
        GameInstallStatus status = session.status();
        taskStatusLabel.setText(status == GameInstallStatus.FAILED
                ? localizedInstallFailure(session)
                : "");
        taskStatusLabel.setToolTipText(taskStatusLabel.getText().isBlank()
                ? null
                : taskStatusLabel.getText());
        backToCatalogButton.setEnabled(status.isTerminal());
        updateInstallAction();
    }

    /// Maps a typed terminal failure without parsing exception message text.
    ///
    /// @param session failed installation session
    /// @return localized failure feedback
    private String localizedInstallFailure(GameInstallSession session) {
        @Nullable Throwable failure = session.failure().orElse(null);
        if (failure instanceof GameInstallRequestRejectedException rejected) {
            return switch (rejected.reason()) {
                case INVALID_INSTANCE_NAME -> installStrings.invalidInstanceNameStatus();
                case INSTANCE_ALREADY_EXISTS -> installStrings.instanceAlreadyExistsStatus();
            };
        }
        return installStrings.installationFailedStatus();
    }

    /// Dismisses a terminal task, releases its status subscription, and restores measured viewport demand.
    private void showCatalogAfterTerminalTask() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable GameInstallSession session = displayedInstallSession;
        if (session == null || !session.status().isTerminal() || !isOpen()) {
            return;
        }

        @Nullable Subscription previousSubscription;
        synchronized (stateLock) {
            if (closed || displayedInstallSession != session) {
                return;
            }
            previousSubscription = installStatusSubscription;
            installStatusSubscription = null;
            displayedInstallSession = null;
            installRevision++;
        }

        @Nullable Throwable cleanupFailure = null;
        cleanupFailure = attemptCleanup(
                cleanupFailure,
                () -> unsubscribe(previousSubscription));
        cleanupFailure = attemptCleanup(cleanupFailure, taskProgressHost::clear);

        taskStatusLabel.setText("");
        taskStatusLabel.setToolTipText(null);
        catalogViewVisible = true;
        ((CardLayout) workflowCards.getLayout()).show(workflowCards, CATALOG_VIEW);
        @Nullable GameVersionCatalogSnapshot snapshot = displayedSnapshot;
        refreshButton.setEnabled(snapshot != null && snapshot.refreshEnabled());
        synchronizeLoadedSelection();
        choiceList.refreshLoadPlan();
        throwUncheckedFailure(cleanupFailure);
    }

    /// Enables installation only for an open catalog view with an exact loaded choice and nonblank name.
    private void updateInstallAction() {
        EdtDispatcher.requireEventDispatchThread();
        installButton.setEnabled(
                isOpen()
                        && catalogViewVisible
                        && selectedVersionId != null
                        && !instanceNameField.getText().isBlank()
                        && displayedInstallSession == null);
    }

    /// Removes one optional status listener.
    ///
    /// @param subscription listener to remove, or null when none is owned
    private static void unsubscribe(@Nullable Subscription subscription) {
        if (subscription != null) {
            subscription.unsubscribe();
        }
    }

    /// Releases list listeners and viewport requests on the EDT.
    private void closeOnEventDispatchThread() {
        EdtDispatcher.requireEventDispatchThread();
        synchronized (publicationLock) {
            @Nullable Subscription statusSubscription;
            synchronized (stateLock) {
                statusSubscription = installStatusSubscription;
                installStatusSubscription = null;
                displayedInstallSession = null;
            }
            @Nullable Throwable cleanupFailure = null;
            cleanupFailure = attemptCleanup(cleanupFailure, modelSubscription::unsubscribe);
            final @Nullable Subscription detachedStatusSubscription = statusSubscription;
            cleanupFailure = attemptCleanup(
                    cleanupFailure,
                    () -> unsubscribe(detachedStatusSubscription));
            cleanupFailure = attemptCleanup(
                    cleanupFailure,
                    () -> searchField.getDocument().removeDocumentListener(searchListener));
            cleanupFailure = attemptCleanup(
                    cleanupFailure,
                    () -> instanceNameField.getDocument().removeDocumentListener(instanceNameListener));
            cleanupFailure = attemptCleanup(
                    cleanupFailure,
                    () -> choiceList.getChoiceModel().removeListDataListener(listDataListener));
            cleanupFailure = attemptCleanup(cleanupFailure, () -> refreshButton.setEnabled(false));
            cleanupFailure = attemptCleanup(cleanupFailure, () -> searchField.setEnabled(false));
            cleanupFailure = attemptCleanup(cleanupFailure, () -> filterBox.setEnabled(false));
            cleanupFailure = attemptCleanup(cleanupFailure, () -> instanceNameField.setEnabled(false));
            cleanupFailure = attemptCleanup(cleanupFailure, () -> installButton.setEnabled(false));
            cleanupFailure = attemptCleanup(cleanupFailure, () -> backToCatalogButton.setEnabled(false));
            cleanupFailure = attemptCleanup(cleanupFailure, () -> choiceList.setEnabled(false));
            cleanupFailure = attemptCleanup(cleanupFailure, () -> choiceList.getList().setEnabled(false));
            cleanupFailure = attemptCleanup(cleanupFailure, taskProgressHost::close);
            cleanupFailure = attemptCleanup(cleanupFailure, choiceList::close);
            throwUncheckedFailure(cleanupFailure);
        }
    }

    /// Returns whether user commands may still reach the caller-owned model.
    ///
    /// @return whether this panel has not closed
    private boolean isOpen() {
        synchronized (stateLock) {
            return !closed;
        }
    }

    /// Attempts one cleanup step and combines its unchecked failure with any earlier failure.
    ///
    /// @param current failure already captured, or null
    /// @param cleanup cleanup step to attempt
    /// @return combined failure, or null when every attempted step succeeded
    private static @Nullable Throwable attemptCleanup(
            @Nullable Throwable current,
            Runnable cleanup) {
        try {
            cleanup.run();
            return current;
        } catch (RuntimeException | Error failure) {
            return combineUncheckedFailures(current, failure);
        }
    }

    /// Combines cleanup failures while ensuring that an [Error] remains the propagated primary failure.
    ///
    /// @param current failure already captured, or null
    /// @param next later unchecked failure
    /// @return combined primary failure
    private static Throwable combineUncheckedFailures(
            @Nullable Throwable current,
            Throwable next) {
        if (current == null) {
            return next;
        }
        if (current == next) {
            return current;
        }
        if (!(current instanceof Error) && next instanceof Error) {
            next.addSuppressed(current);
            return next;
        }
        current.addSuppressed(next);
        return current;
    }

    /// Propagates a captured cleanup failure without changing its unchecked type.
    ///
    /// @param failure cleanup failure, or null after success
    private static void throwUncheckedFailure(@Nullable Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    /// Selects the page card for one exact catalog state.
    ///
    /// @param snapshot current catalog state
    /// @return stable card identifier
    private static String selectContentCard(GameVersionCatalogSnapshot snapshot) {
        if (snapshot.status() == GameVersionCatalogStatus.LOADING
                || snapshot.status() == GameVersionCatalogStatus.IDLE) {
            return LOADING_CARD;
        }
        if (snapshot.status() == GameVersionCatalogStatus.FAILED && snapshot.itemCount() == 0) {
            return FAILED_CARD;
        }
        return snapshot.itemCount() == 0 ? EMPTY_CARD : LIST_CARD;
    }

    /// Creates one centered state label with a stable component name.
    ///
    /// @param name stable component name
    /// @return centered state label
    private static JLabel stateLabel(String name) {
        JLabel label = new JLabel("", SwingConstants.CENTER);
        label.setName(name);
        return label;
    }

    /// Renders filter enum values through localized page text.
    @NotNullByDefault
    private final class FilterRenderer extends DefaultListCellRenderer {
        /// Presents one filter value in the combo-box popup or selected-value area.
        ///
        /// @param list owning list
        /// @param value filter value
        /// @param index row index, or -1 for the selected-value area
        /// @param selected whether this row is selected
        /// @param focused whether this row owns focus
        /// @return configured renderer component
        @Override
        public Component getListCellRendererComponent(
                JList<?> list,
                @Nullable Object value,
                int index,
                boolean selected,
                boolean focused) {
            Object displayedValue = value instanceof GameVersionFilter filter
                    ? strings.filterText(filter)
                    : value;
            return super.getListCellRendererComponent(
                    list,
                    displayedValue,
                    index,
                    selected,
                    focused);
        }
    }
}

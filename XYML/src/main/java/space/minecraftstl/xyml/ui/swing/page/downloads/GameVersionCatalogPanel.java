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

import com.formdev.flatlaf.extras.FlatSVGIcon;
import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.download.LibraryAnalyzer;
import space.minecraftstl.xyml.download.RemoteVersion;
import space.minecraftstl.xyml.game.install.GameInstallAlreadyRunningException;
import space.minecraftstl.xyml.game.install.GameInstallRequest;
import space.minecraftstl.xyml.game.install.GameInstallRequestRejectedException;
import space.minecraftstl.xyml.game.install.GameInstallService;
import space.minecraftstl.xyml.game.install.GameInstallSession;
import space.minecraftstl.xyml.game.install.GameInstallStatus;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.ui.swing.AnimatedTabbedPane;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.SwingTextFields;
import space.minecraftstl.xyml.ui.swing.SwingTransparency;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;
import space.minecraftstl.xyml.ui.swing.choice.ChoiceListEntry;
import space.minecraftstl.xyml.ui.swing.choice.ChoiceLoadStatus;
import space.minecraftstl.xyml.ui.swing.choice.ViewportChoiceList;
import space.minecraftstl.xyml.ui.swing.page.downloads.loaders.LoaderSelectionListener;
import space.minecraftstl.xyml.ui.swing.page.downloads.loaders.LoaderSelectionSnapshot;
import space.minecraftstl.xyml.ui.swing.page.downloads.loaders.LoaderSelectionWizardPanel;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressHostPanel;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings;
import space.minecraftstl.xyml.util.i18n.I18n;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Duration;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Presents a lazy game-version catalog, optional loader-selection workflow, and game installation task.
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

    /// Card containing the optional compatible loader-selection workflow.
    private static final String LOADER_VIEW = "loaders";

    /// Card containing the current installation task and terminal return command.
    private static final String TASK_VIEW = "task";

    /// Action-map key that opens the selected version's visible installation configuration.
    private static final String ACTIVATE_INSTALL_CONFIGURATION = "activateInstallConfiguration";

    /// Lock guarding close state and queued model-change revisions.
    private final Object stateLock = new Object();

    /// Serializes EDT state application with synchronous close cleanup.
    private final Object publicationLock = new Object();

    /// Toolkit-neutral catalog model and viewport source.
    private final GameVersionCatalogModel model;

    /// Application-owned single-flight game installation service.
    private final GameInstallService installService;

    /// Localized control text.
    private final GameVersionCatalogStrings strings;

    /// Localized installation controls, task text, and validation feedback.
    private final GameInstallStrings installStrings;

    /// Viewport-measured single-choice list with version metadata rows.
    private final ViewportChoiceList<GameVersionCatalogItem> choiceList;

    /// Cards representing lazy loading, failure, empty results, and visible rows.
    private final JPanel contentCards = new JPanel(new CardLayout());

    /// Stable card host switching between the catalog and retained task details.
    private final JPanel workflowCards = new JPanel(new CardLayout());

    /// Owns the current installation task presentation panel.
    private final TaskProgressHostPanel taskProgressHost;

    /// Secondary download-center categories retained beside the vanilla game-version workflow.
    private final DownloadCategoryPanel downloadCategoryPanel;

    /// Explicitly searched remote CurseForge and Modrinth modpack catalog retained beside local imports.
    private final RemoteModpackCatalogPanel remoteModpackCatalogPanel;

    /// Optional loader-selection workflow that remains offline until its explicit refresh command.
    private final LoaderSelectionWizardPanel loaderSelectionPanel;

    /// Listener retaining the loader selection selected by the embedded workflow.
    private final LoaderSelectionListener loaderSelectionListener = this::loaderSelectionChanged;

    /// Top-level tabs preserving the original game installer while exposing restored content categories.
    private final JTabbedPane downloadCenterTabs = new AnimatedTabbedPane();

    /// Version-ID query editor.
    private final JTextField searchField = new JTextField();

    /// Mutually exclusive visible version-kind controls keyed by their exact model filter.
    private final Map<GameVersionFilter, JToggleButton> filterButtons =
            new EnumMap<>(GameVersionFilter.class);

    /// Selection group preventing more than one visible version-kind control from being active.
    private final ButtonGroup filterButtonGroup = new ButtonGroup();

    /// Source refresh command.
    private final JButton refreshButton = new JButton();

    /// Current model status, including retained-content refresh failures.
    private final JLabel statusLabel = new JLabel();

    /// Exact destination instance-name editor.
    private final JTextField instanceNameField = new JTextField();

    /// Restores the version-and-loader-derived destination name after a manual edit.
    private final JButton resetInstanceNameButton = new JButton();

    /// Fixed-width installation configuration kept beside the scrollable version catalog.
    private final JPanel installConfigurationPanel = new JPanel();

    /// Command that captures the loaded selected version and exact instance name.
    private final JButton installButton = new JButton();

    /// Command that opens the optional compatible loader-selection card.
    private final JButton selectLoadersButton = new JButton();

    /// Command that dismisses a terminal task and restores the catalog card.
    private final JButton backToCatalogButton = new JButton();

    /// Command that returns from the loader-selection card to the game-version catalog.
    private final JButton backFromLoadersButton = new JButton();

    /// Localized installation validation or terminal-failure feedback.
    private final JLabel installStatusLabel = new JLabel();

    /// Localized terminal installation feedback shown beside the return command.
    private final JLabel taskStatusLabel = new JLabel();

    /// Concise current loader selection retained beside the installation command.
    private final JLabel loaderSummaryLabel = new JLabel();

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

    /// Reusable double-click listener that activates only a concrete version row.
    private final VersionActivationMouseListener versionActivationMouseListener =
            new VersionActivationMouseListener();

    /// Reusable keyboard command mapped to Enter while the version list owns focus.
    private final ActivateInstallConfigurationAction activateInstallConfigurationAction =
            new ActivateInstallConfigurationAction();

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

    /// Base game version whose optional loader selection is retained across transient list states.
    private @Nullable String loaderGameVersionId;

    /// Exact selected loader objects retained for the currently selected base game version.
    private @Unmodifiable List<RemoteVersion> selectedRemoteVersions = List.of();

    /// Last version-derived instance name, or null after the user authored a different value.
    private @Nullable String suggestedInstanceName;

    /// Installation session retained by the task card, or null while the catalog card is active.
    private @Nullable GameInstallSession displayedInstallSession;

    /// Whether programmatic control restoration suppresses model commands.
    private boolean applyingSnapshot;

    /// Whether a version-derived destination update suppresses user-edit detection.
    private boolean applyingInstanceNameSuggestion;

    /// Activated sparse row whose eventual load may focus the installation configuration, or -1.
    private int installConfigurationActivationIndex = -1;

    /// Revision invalidating older worker-to-EDT state applications.
    private long updateRevision;

    /// Revision invalidating queued installation-status applications.
    private long installRevision;

    /// Whether this panel has already delegated its one lazy initial-load request.
    private boolean initialLoadRequested;

    /// Exact child workflow card currently shown below the game-version heading.
    private WorkflowView workflowView = WorkflowView.CATALOG;

    /// Whether this panel has released its subscription and viewport resources.
    private boolean closed;

    /// Creates a production game-version catalog panel on the Swing event dispatch thread.
    ///
    /// @param model toolkit-neutral lazy catalog model
    /// @param installService application-owned single-flight game installer
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
        this(
                model,
                installService,
                strings,
                installStrings,
                taskProgressStrings,
                animator,
                progressAnimationDuration,
                LoaderSelectionWizardPanel.createForLauncher());
    }

    /// Creates a catalog panel with an explicit zero-I/O loader-selection control for focused integration tests.
    ///
    /// @param model toolkit-neutral lazy catalog model
    /// @param installService application-owned single-flight game installer
    /// @param strings localized catalog text
    /// @param installStrings localized installation text
    /// @param taskProgressStrings localized task-progress controls and lifecycle states
    /// @param animator optional shared progress animator
    /// @param progressAnimationDuration non-negative installation-progress animation duration
    /// @param loaderSelectionPanel embedded loader-selection workflow
    GameVersionCatalogPanel(
            GameVersionCatalogModel model,
            GameInstallService installService,
            GameVersionCatalogStrings strings,
            GameInstallStrings installStrings,
            TaskProgressStrings taskProgressStrings,
            @Nullable SwingAnimator animator,
            Duration progressAnimationDuration,
            LoaderSelectionWizardPanel loaderSelectionPanel) {
        super(new MigLayout(
                "insets 0, fill",
                "[grow,fill]",
                "[grow,fill]"));
        EdtDispatcher.requireEventDispatchThread();
        this.model = Objects.requireNonNull(model, "model");
        this.installService = Objects.requireNonNull(installService, "installService");
        this.strings = Objects.requireNonNull(strings, "strings");
        this.installStrings = Objects.requireNonNull(installStrings, "installStrings");
        TaskProgressStrings resolvedTaskProgressStrings = Objects.requireNonNull(
                taskProgressStrings, "taskProgressStrings");
        Duration resolvedProgressAnimationDuration = Objects.requireNonNull(
                progressAnimationDuration, "progressAnimationDuration");
        taskProgressHost = new TaskProgressHostPanel(
                resolvedTaskProgressStrings,
                animator,
                resolvedProgressAnimationDuration);
        downloadCategoryPanel = new DownloadCategoryPanel(
                resolvedTaskProgressStrings,
                animator,
                resolvedProgressAnimationDuration);
        remoteModpackCatalogPanel = new RemoteModpackCatalogPanel(
                RemoteModpackCatalogStrings.launcherLocalized(),
                resolvedTaskProgressStrings,
                animator,
                resolvedProgressAnimationDuration);
        this.loaderSelectionPanel = Objects.requireNonNull(
                loaderSelectionPanel,
                "loaderSelectionPanel");
        choiceList = new ViewportChoiceList<>(model, new GameVersionEntryRenderer());

        configureComponents();
        this.loaderSelectionPanel.addSelectionListener(loaderSelectionListener);
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
        setOpaque(false);
        JPanel gameVersionsPanel = new JPanel(new MigLayout(
                "insets 0, fill, wrap 1",
                "[grow,fill]",
                "[]12[grow,fill]"));
        gameVersionsPanel.setOpaque(false);
        gameVersionsPanel.setName("gameVersionsDownloadCenter");

        JPanel headingBand = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill][]", "[]"));
        headingBand.setOpaque(false);

        JLabel heading = new JLabel(strings.pageTitle());
        heading.setName("gameVersionsPageTitle");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 28.0F));
        headingBand.add(heading);

        refreshButton.setName("gameVersionsRefresh");
        refreshButton.addActionListener(event -> {
            if (isOpen() && workflowView == WorkflowView.CATALOG) {
                model.refresh();
            }
        });
        headingBand.add(refreshButton, "h 40!");
        gameVersionsPanel.add(headingBand, "growx");

        JPanel catalogWorkspace = new JPanel(new MigLayout(
                "insets 0, fill, wrap 1",
                "[grow,fill]",
                "[]8[]12[grow,fill]"));
        catalogWorkspace.setOpaque(false);
        catalogWorkspace.setName("gameVersionsCatalogWorkspace");

        JPanel searchBand = new JPanel(new MigLayout(
                "insets 0, fillx",
                "[][grow,fill]",
                "[40!]"));
        searchBand.setOpaque(false);

        JLabel searchLabel = new JLabel(strings.searchLabel());
        searchLabel.setLabelFor(searchField);
        searchBand.add(searchLabel);
        searchField.setName("gameVersionsSearch");
        SwingTextFields.showClearButton(searchField);
        searchField.getDocument().addDocumentListener(searchListener);
        searchBand.add(searchField, "growx, h 40!");
        catalogWorkspace.add(searchBand, "growx");

        JPanel filterBand = new JPanel(new MigLayout(
                "insets 0, fillx",
                "[][grow,fill]",
                "[36!]"));
        filterBand.setOpaque(false);

        JLabel filterLabel = new JLabel(strings.filterLabel());
        filterBand.add(filterLabel);
        JPanel filterOptionsPanel = new JPanel(new MigLayout(
                "insets 0, fillx",
                "[grow,fill][grow,fill][grow,fill][grow,fill][grow,fill]",
                "[36!]"));
        filterOptionsPanel.setOpaque(false);
        filterOptionsPanel.setName("gameVersionsFilterOptions");
        for (GameVersionFilter filter : GameVersionFilter.values()) {
            JToggleButton button = new JToggleButton(strings.filterText(filter));
            button.setName("gameVersionsFilter_" + filter.name());
            button.putClientProperty("JButton.buttonType", "tab");
            button.addActionListener(event -> filterChanged(filter));
            filterButtonGroup.add(button);
            filterButtons.put(filter, button);
            filterOptionsPanel.add(button, "grow");
        }
        filterLabel.setLabelFor(filterButton(GameVersionFilter.RELEASE));
        filterBand.add(filterOptionsPanel, "growx");
        catalogWorkspace.add(filterBand, "growx");

        JPanel selectionWorkspace = new JPanel(new MigLayout(
                "insets 0, fill",
                "[grow,fill]12[300:340:420,fill]",
                "[grow,fill]"));
        selectionWorkspace.setOpaque(false);
        selectionWorkspace.setName("gameVersionsSelectionWorkspace");

        JPanel versionListPanel = new JPanel(new MigLayout(
                "insets 0, fill, wrap 1",
                "[grow,fill]",
                "[grow,fill]4[]"));
        versionListPanel.setOpaque(false);
        versionListPanel.setName("gameVersionsListWorkspace");

        choiceList.setName("gameVersionsList");
        choiceList.setMinimumSize(new java.awt.Dimension(0, 0));
        choiceList.setOpaque(false);
        choiceList.getViewport().setOpaque(false);
        JList<ChoiceListEntry<GameVersionCatalogItem>> list = choiceList.getList();
        list.setName("gameVersionsListView");
        list.setOpaque(false);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !applyingSnapshot && isOpen()) {
                pendingUserSelectionIndex = list.getSelectedIndex();
                submitPendingUserSelection();
                synchronizeLoadedSelection();
            }
        });
        list.addMouseListener(versionActivationMouseListener);
        list.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
                ACTIVATE_INSTALL_CONFIGURATION);
        list.getActionMap().put(
                ACTIVATE_INSTALL_CONFIGURATION,
                activateInstallConfigurationAction);
        choiceList.getChoiceModel().addListDataListener(listDataListener);

        contentCards.add(loadingLabel, LOADING_CARD);
        contentCards.add(failedLabel, FAILED_CARD);
        contentCards.add(emptyLabel, EMPTY_CARD);
        contentCards.add(choiceList, LIST_CARD);
        contentCards.setName("gameVersionsContentCards");
        contentCards.setOpaque(false);
        contentCards.setMinimumSize(new java.awt.Dimension(0, 0));
        versionListPanel.add(contentCards, "grow");

        statusLabel.setName("gameVersionsStatus");
        versionListPanel.add(statusLabel, "growx, h 24!");
        selectionWorkspace.add(versionListPanel, "grow");

        installConfigurationPanel.setLayout(new MigLayout(
                "insets 12, fillx, wrap 2",
                "[][grow,fill]",
                "[40!]8[40!]6[]4[]"));
        installConfigurationPanel.setOpaque(false);
        installConfigurationPanel.setName("gameVersionsInstallConfiguration");
        installConfigurationPanel.setBorder(BorderFactory.createTitledBorder(installStrings.taskTitle()));

        JLabel instanceNameLabel = new JLabel(installStrings.instanceNameLabel());
        instanceNameLabel.setLabelFor(instanceNameField);
        installConfigurationPanel.add(instanceNameLabel);
        instanceNameField.setName("gameVersionsInstanceName");
        SwingTextFields.showClearButton(instanceNameField);
        instanceNameField.getDocument().addDocumentListener(instanceNameListener);
        JPanel instanceNameRow = new JPanel(new MigLayout(
                "insets 0, fillx",
                "[grow,fill]6[40!]",
                "[40!]"));
        instanceNameRow.setOpaque(false);
        instanceNameRow.add(instanceNameField, "grow, h 40!");
        resetInstanceNameButton.setName("gameVersionsResetInstanceName");
        resetInstanceNameButton.setIcon(new FlatSVGIcon("assets/swing/icons/restore.svg", 18, 18));
        resetInstanceNameButton.setToolTipText(i18n("button.reset"));
        resetInstanceNameButton.getAccessibleContext().setAccessibleName(i18n("button.reset"));
        resetInstanceNameButton.addActionListener(event -> resetInstanceName());
        instanceNameRow.add(resetInstanceNameButton, "grow");
        installConfigurationPanel.add(instanceNameRow, "growx, h 40!");

        JPanel installActions = new JPanel(new MigLayout(
                "insets 0, fillx",
                "[grow,fill]8[grow,fill]",
                "[40!]"));
        installActions.setOpaque(false);
        selectLoadersButton.setName("gameVersionsLoaders");
        selectLoadersButton.setText(i18n("settings.tabs.installers"));
        selectLoadersButton.addActionListener(event -> showLoaderSelection());
        installActions.add(selectLoadersButton, "grow, h 40!");
        installButton.setName("gameVersionsInstall");
        installButton.setText(installStrings.installAction());
        installButton.putClientProperty("JButton.buttonType", "roundRect");
        installButton.addActionListener(event -> startInstallation());
        installActions.add(installButton, "grow, h 40!");
        installConfigurationPanel.add(installActions, "span 2, growx");

        installStatusLabel.setName("gameVersionsInstallStatus");
        installConfigurationPanel.add(installStatusLabel, "span 2, growx, h 24!");
        loaderSummaryLabel.setName("gameVersionsLoaderSummary");
        loaderSummaryLabel.setText(formatLoaderSummary(loaderSelectionPanel.selectionSummary()));
        installConfigurationPanel.add(loaderSummaryLabel, "span 2, growx, h 24!");
        selectionWorkspace.add(installConfigurationPanel, "grow");
        catalogWorkspace.add(selectionWorkspace, "grow");

        JPanel loaderWorkspace = new JPanel(new MigLayout(
                "insets 0, fill, wrap 1",
                "[grow,fill]",
                "[grow,fill]12[]"));
        loaderWorkspace.setOpaque(false);
        loaderWorkspace.setName("gameVersionsLoaderWorkspace");
        loaderSelectionPanel.setOpaque(false);
        loaderWorkspace.add(loaderSelectionPanel, "grow");

        JPanel loaderActions = new JPanel(new MigLayout(
                "insets 0, fillx",
                "[grow,fill][220!]",
                "[40!]"));
        loaderActions.setOpaque(false);
        loaderSummaryLabel.setToolTipText(loaderSelectionPanel.selectionSummary());
        loaderActions.add(new JLabel(), "growx");
        backFromLoadersButton.setName("gameVersionsBackFromLoaders");
        backFromLoadersButton.setText(installStrings.backToCatalogAction());
        backFromLoadersButton.addActionListener(event -> showCatalogAfterLoaderSelection());
        loaderActions.add(backFromLoadersButton, "grow, h 40!");
        loaderWorkspace.add(loaderActions, "growx");

        JScrollPane loaderScroll = new JScrollPane(loaderWorkspace);
        loaderScroll.setName("gameVersionsLoaderScroll");
        loaderScroll.setBorder(BorderFactory.createEmptyBorder());
        loaderScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        loaderScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        loaderScroll.getVerticalScrollBar().setUnitIncrement(16);
        loaderScroll.setMinimumSize(new java.awt.Dimension(0, 0));
        SwingTransparency.revealBackgroundThroughScrollPane(loaderScroll);

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
        workflowCards.setMinimumSize(new java.awt.Dimension(0, 0));
        workflowCards.add(catalogWorkspace, CATALOG_VIEW);
        workflowCards.add(loaderScroll, LOADER_VIEW);
        workflowCards.add(taskWorkspace, TASK_VIEW);
        gameVersionsPanel.add(workflowCards, "grow");

        downloadCenterTabs.setName("downloadCenterTabs");
        SwingTransparency.revealBackgroundThroughTabs(downloadCenterTabs);
        downloadCenterTabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        downloadCenterTabs.setMinimumSize(new java.awt.Dimension(0, 0));
        downloadCenterTabs.addTab(strings.pageTitle(), gameVersionsPanel);
        downloadCenterTabs.addTab(i18n("download.content"), downloadCategoryPanel);
        downloadCenterTabs.addTab(i18n("modpack.download"), remoteModpackCatalogPanel);
        add(downloadCenterTabs, "grow");

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
            JToggleButton selectedFilterButton = filterButton(snapshot.filter());
            if (!selectedFilterButton.isSelected()) {
                selectedFilterButton.setSelected(true);
            }
            if (contentChanged) {
                pendingUserSelectionIndex = -1;
                installConfigurationActivationIndex = -1;
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
        refreshButton.setEnabled(workflowView == WorkflowView.CATALOG && snapshot.refreshEnabled());
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

    /// Delegates one explicit visible kind after a user changes the segmented filter.
    ///
    /// @param filter exact requested catalog projection
    private void filterChanged(GameVersionFilter filter) {
        if (applyingSnapshot || !isOpen()) {
            return;
        }
        model.setFilter(Objects.requireNonNull(filter, "filter"));
    }

    /// Returns the installed visible control for one exhaustive version filter.
    ///
    /// @param filter filter whose control is required
    /// @return non-null registered toggle button
    private JToggleButton filterButton(GameVersionFilter filter) {
        return Objects.requireNonNull(
                filterButtons.get(Objects.requireNonNull(filter, "filter")),
                "missing game-version filter button");
    }

    /// Enables or disables all visible kind filters without altering their selected state.
    ///
    /// @param enabled whether user filter commands are accepted
    private void setFilterControlsEnabled(boolean enabled) {
        for (JToggleButton button : filterButtons.values()) {
            button.setEnabled(enabled);
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

    /// Activates the selected sparse row and focuses its always-visible installation configuration.
    ///
    /// A row that is still loading retains the focus request until its exact value is published. This
    /// does not change viewport demand or start installation; it only makes the next explicit step clear.
    private void activateSelectedVersion() {
        EdtDispatcher.requireEventDispatchThread();
        if (!isOpen() || workflowView != WorkflowView.CATALOG) {
            return;
        }
        int selectedIndex = choiceList.getList().getSelectedIndex();
        if (selectedIndex < 0) {
            return;
        }
        installConfigurationActivationIndex = selectedIndex;
        pendingUserSelectionIndex = selectedIndex;
        submitPendingUserSelection();
        synchronizeLoadedSelection();
    }

    /// Focuses and selects the destination field after an activated row has resolved.
    private void focusInstallConfigurationIfRequested() {
        if (installConfigurationActivationIndex < 0) {
            return;
        }
        if (choiceList.getList().getSelectedIndex() != installConfigurationActivationIndex) {
            installConfigurationActivationIndex = -1;
            return;
        }
        if (selectedVersionId == null) {
            return;
        }
        installConfigurationActivationIndex = -1;
        installConfigurationPanel.scrollRectToVisible(new Rectangle(
                0,
                0,
                installConfigurationPanel.getWidth(),
                installConfigurationPanel.getHeight()));
        instanceNameField.requestFocusInWindow();
        instanceNameField.selectAll();
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
            if (shouldDiscardUnavailableLoaderSelection(snapshot)) {
                clearLoaderSelection();
            }
            updateInstallAction();
            return;
        }

        String versionId = selected.versionId();
        boolean loaderGameVersionChanged = !versionId.equals(loaderGameVersionId);
        selectedVersionId = versionId;
        if (loaderGameVersionChanged) {
            installStatusLabel.setText("");
            installStatusLabel.setToolTipText(null);
            String currentName = instanceNameField.getText();
            boolean mayReplace = currentName.isBlank()
                    || Objects.equals(currentName, suggestedInstanceName);
            loaderGameVersionId = versionId;
            selectedRemoteVersions = List.of();
            loaderSelectionPanel.selectGameVersion(versionId);
            if (mayReplace) {
                applyInstanceNameSuggestion(versionId);
            } else {
                suggestedInstanceName = null;
            }
        }
        updateInstallAction();
        focusInstallConfigurationIfRequested();
    }

    /// Determines whether a completed unfiltered catalog proves the bound game version disappeared.
    ///
    /// Loading, filtering, querying, and sparse viewport placeholders are transient and retain loader
    /// choices. A ready full unfiltered catalog with no stable selection is the only state proving that
    /// the previous base version is no longer available.
    ///
    /// @param snapshot latest catalog state, or null before initialization
    /// @return whether the retained loader selection must be discarded
    private boolean shouldDiscardUnavailableLoaderSelection(
            @Nullable GameVersionCatalogSnapshot snapshot) {
        return loaderGameVersionId != null
                && snapshot != null
                && snapshot.status() == GameVersionCatalogStatus.READY
                && snapshot.query().isBlank()
                && snapshot.filter() == GameVersionFilter.ALL
                && snapshot.selectedIndex().isEmpty();
    }

    /// Clears the base-version binding and all exact selected loader objects.
    private void clearLoaderSelection() {
        if (loaderGameVersionId == null && selectedRemoteVersions.isEmpty()) {
            return;
        }
        loaderGameVersionId = null;
        selectedRemoteVersions = List.of();
        loaderSelectionPanel.clearGameVersion();
    }

    /// Opens the optional loader-selection card for the exact currently loaded Minecraft version.
    private void showLoaderSelection() {
        EdtDispatcher.requireEventDispatchThread();
        if (!selectLoadersButton.isEnabled() || !isOpen() || workflowView != WorkflowView.CATALOG) {
            return;
        }
        @Nullable GameVersionCatalogItem selected = choiceList.getSelectedValue();
        @Nullable String versionId = selectedVersionId;
        if (selected == null || versionId == null || !versionId.equals(selected.versionId())) {
            synchronizeLoadedSelection();
            return;
        }

        LoaderSelectionSnapshot loaderSnapshot = loaderSelectionPanel.selectionSnapshot();
        if (!versionId.equals(loaderSnapshot.gameVersion().orElse(null))) {
            loaderSelectionPanel.selectGameVersion(versionId);
        }
        workflowView = WorkflowView.LOADERS;
        ((CardLayout) workflowCards.getLayout()).show(workflowCards, LOADER_VIEW);
        refreshButton.setEnabled(false);
        updateInstallAction();
    }

    /// Returns from loader selection while retaining the exact selected loader objects for installation.
    private void showCatalogAfterLoaderSelection() {
        EdtDispatcher.requireEventDispatchThread();
        if (!isOpen() || workflowView != WorkflowView.LOADERS) {
            return;
        }
        workflowView = WorkflowView.CATALOG;
        ((CardLayout) workflowCards.getLayout()).show(workflowCards, CATALOG_VIEW);
        @Nullable GameVersionCatalogSnapshot snapshot = displayedSnapshot;
        refreshButton.setEnabled(snapshot != null && snapshot.refreshEnabled());
        synchronizeLoadedSelection();
        choiceList.refreshLoadPlan();
        updateInstallAction();
    }

    /// Retains a wizard-published selection only when it belongs to the currently selected base game version.
    ///
    /// @param snapshot immutable loader-selection state published on the event dispatch thread
    private void loaderSelectionChanged(LoaderSelectionSnapshot snapshot) {
        EdtDispatcher.requireEventDispatchThread();
        LoaderSelectionSnapshot nonNullSnapshot = Objects.requireNonNull(snapshot, "snapshot");
        @Nullable String versionId = loaderGameVersionId;
        boolean belongsToSelectedGame = versionId != null
                && versionId.equals(nonNullSnapshot.gameVersion().orElse(null));
        boolean mayReplaceSuggestedName = instanceNameField.getText().isBlank()
                || Objects.equals(instanceNameField.getText(), suggestedInstanceName);
        selectedRemoteVersions = belongsToSelectedGame
                ? nonNullSnapshot.selectedRemoteVersions()
                : List.of();
        if (belongsToSelectedGame && mayReplaceSuggestedName && versionId != null) {
            applyInstanceNameSuggestion(defaultInstanceName(versionId, selectedRemoteVersions));
        } else if (!mayReplaceSuggestedName) {
            suggestedInstanceName = null;
        }
        String summary = belongsToSelectedGame
                ? nonNullSnapshot.summary()
                : loaderSelectionPanel.selectionSummary();
        loaderSummaryLabel.setText(formatLoaderSummary(summary));
        loaderSummaryLabel.setToolTipText(summary);
        updateInstallAction();
    }

    /// Formats the concise embedded-wizard summary for the surrounding installation controls.
    ///
    /// @param summary non-blank or empty loader-selection summary
    /// @return localized surrounding-label text
    private static String formatLoaderSummary(String summary) {
        return i18n("settings.tabs.installers") + ": " + Objects.requireNonNull(summary, "summary");
    }

    /// Applies one version-and-loader-derived destination without classifying its document events as user edits.
    ///
    /// @param suggestion complete suggested instance name
    private void applyInstanceNameSuggestion(String suggestion) {
        suggestedInstanceName = Objects.requireNonNull(suggestion, "suggestion");
        applyingInstanceNameSuggestion = true;
        try {
            instanceNameField.setText(suggestion);
        } finally {
            applyingInstanceNameSuggestion = false;
        }
    }

    /// Restores the exact default name derived from the selected game and primary loader kinds.
    private void resetInstanceName() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable String versionId = selectedVersionId;
        if (versionId == null) {
            return;
        }
        applyInstanceNameSuggestion(defaultInstanceName(versionId, selectedRemoteVersions));
        updateInstallAction();
    }

    /// Builds the historical concise instance name from Minecraft and selected primary loaders.
    ///
    /// @param versionId exact selected Minecraft version
    /// @param loaders immutable selected loader and companion versions
    /// @return suggested instance name
    static String defaultInstanceName(
            String versionId,
            @Unmodifiable List<RemoteVersion> loaders) {
        StringBuilder name = new StringBuilder(Objects.requireNonNull(versionId, "versionId"));
        for (RemoteVersion loader : Objects.requireNonNull(loaders, "loaders")) {
            @Nullable LibraryAnalyzer.LibraryType type =
                    LibraryAnalyzer.LibraryType.fromPatchId(loader.getLibraryId());
            @Nullable String suffix = type == null ? null : switch (type) {
                case FORGE -> "Forge";
                case NEO_FORGE -> "NeoForge";
                case CLEANROOM -> "Cleanroom";
                case LEGACY_FABRIC -> "LegacyFabric";
                case FABRIC -> "Fabric";
                case LITELOADER -> "LiteLoader";
                case QUILT -> "Quilt";
                case OPTIFINE -> "OptiFine";
                default -> null;
            };
            if (suffix != null) {
                name.append('-').append(suffix);
            }
        }
        return name.toString();
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
        if (!installButton.isEnabled() || !isOpen() || workflowView != WorkflowView.CATALOG) {
            return;
        }

        @Nullable GameVersionCatalogItem selected = choiceList.getSelectedValue();
        @Nullable String versionId = selectedVersionId;
        if (selected == null || versionId == null || !versionId.equals(selected.versionId())) {
            synchronizeLoadedSelection();
            return;
        }

        GameInstallRequest request = new GameInstallRequest(
                instanceNameField.getText(),
                versionId,
                selectedRemoteVersions);
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

        workflowView = WorkflowView.TASK;
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
        workflowView = WorkflowView.CATALOG;
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
        resetInstanceNameButton.setEnabled(
                isOpen()
                        && workflowView == WorkflowView.CATALOG
                        && selectedVersionId != null
                        && displayedInstallSession == null);
        selectLoadersButton.setEnabled(
                isOpen()
                        && workflowView == WorkflowView.CATALOG
                        && selectedVersionId != null
                        && displayedInstallSession == null);
        installButton.setEnabled(
                isOpen()
                        && workflowView == WorkflowView.CATALOG
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
            cleanupFailure = attemptCleanup(
                    cleanupFailure,
                    () -> choiceList.getList().removeMouseListener(versionActivationMouseListener));
            cleanupFailure = attemptCleanup(
                    cleanupFailure,
                    () -> choiceList.getList().getInputMap(JComponent.WHEN_FOCUSED).remove(
                            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)));
            cleanupFailure = attemptCleanup(
                    cleanupFailure,
                    () -> choiceList.getList().getActionMap().remove(ACTIVATE_INSTALL_CONFIGURATION));
            cleanupFailure = attemptCleanup(
                    cleanupFailure,
                    () -> loaderSelectionPanel.removeSelectionListener(loaderSelectionListener));
            cleanupFailure = attemptCleanup(cleanupFailure, () -> refreshButton.setEnabled(false));
            cleanupFailure = attemptCleanup(cleanupFailure, () -> searchField.setEnabled(false));
            cleanupFailure = attemptCleanup(cleanupFailure, () -> setFilterControlsEnabled(false));
            cleanupFailure = attemptCleanup(cleanupFailure, () -> instanceNameField.setEnabled(false));
            cleanupFailure = attemptCleanup(cleanupFailure, () -> resetInstanceNameButton.setEnabled(false));
            cleanupFailure = attemptCleanup(cleanupFailure, () -> selectLoadersButton.setEnabled(false));
            cleanupFailure = attemptCleanup(cleanupFailure, () -> installButton.setEnabled(false));
            cleanupFailure = attemptCleanup(cleanupFailure, () -> backToCatalogButton.setEnabled(false));
            cleanupFailure = attemptCleanup(cleanupFailure, () -> backFromLoadersButton.setEnabled(false));
            cleanupFailure = attemptCleanup(cleanupFailure, () -> choiceList.setEnabled(false));
            cleanupFailure = attemptCleanup(cleanupFailure, () -> choiceList.getList().setEnabled(false));
            cleanupFailure = attemptCleanup(cleanupFailure, loaderSelectionPanel::close);
            cleanupFailure = attemptCleanup(cleanupFailure, remoteModpackCatalogPanel::close);
            cleanupFailure = attemptCleanup(cleanupFailure, downloadCategoryPanel::close);
            cleanupFailure = attemptCleanup(cleanupFailure, taskProgressHost::close);
            cleanupFailure = attemptCleanup(cleanupFailure, choiceList::close);
            installConfigurationActivationIndex = -1;
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

    /// Distinguishes the three retained child workflows sharing the stable page host.
    @NotNullByDefault
    private enum WorkflowView {
        /// Base game-version catalog and installation controls.
        CATALOG,

        /// Optional loader-selection subflow for the selected base game version.
        LOADERS,

        /// Active or terminal installation-task presentation.
        TASK
    }

    /// Activates only a concrete double-clicked version row.
    @NotNullByDefault
    private final class VersionActivationMouseListener extends MouseAdapter {
        /// Moves selection to the clicked row and opens its visible configuration on a primary double click.
        ///
        /// @param event mouse event delivered by the version list
        @Override
        public void mouseClicked(MouseEvent event) {
            if (event.getClickCount() != 2 || !SwingUtilities.isLeftMouseButton(event)) {
                return;
            }
            JList<ChoiceListEntry<GameVersionCatalogItem>> list = choiceList.getList();
            int clickedIndex = list.locationToIndex(event.getPoint());
            @Nullable Rectangle clickedBounds = clickedIndex < 0
                    ? null
                    : list.getCellBounds(clickedIndex, clickedIndex);
            if (clickedBounds == null || !clickedBounds.contains(event.getPoint())) {
                return;
            }
            list.setSelectedIndex(clickedIndex);
            activateSelectedVersion();
        }
    }

    /// Keyboard action focusing installation configuration for the selected version.
    @NotNullByDefault
    private final class ActivateInstallConfigurationAction extends AbstractAction {
        /// Serialization version for Swing action compatibility.
        private static final long serialVersionUID = 1L;

        /// Delegates the bound Enter command to the same row-activation path as double click.
        ///
        /// @param event action event emitted by the list input map
        @Override
        public void actionPerformed(ActionEvent event) {
            activateSelectedVersion();
        }
    }

    /// Reusable two-line renderer exposing each loaded version's classification and publication date.
    @NotNullByDefault
    private final class GameVersionEntryRenderer extends JPanel
            implements ListCellRenderer<ChoiceListEntry<GameVersionCatalogItem>> {
        /// Serialization version for Swing renderer compatibility.
        private static final long serialVersionUID = 1L;

        /// Primary stable version identifier.
        private final JLabel versionLabel = new JLabel();

        /// Secondary localized kind and release-date metadata.
        private final JLabel metadataLabel = new JLabel();

        /// Creates the reusable stable-height row renderer.
        private GameVersionEntryRenderer() {
            super(new MigLayout(
                    "insets 7 10 7 10, fillx, wrap 1",
                    "[grow,fill]",
                    "[][]"));
            setOpaque(false);
            versionLabel.setName("gameVersionRowTitle");
            versionLabel.setFont(versionLabel.getFont().deriveFont(Font.BOLD));
            metadataLabel.setName("gameVersionRowMetadata");
            metadataLabel.setFont(metadataLabel.getFont().deriveFont(
                    Math.max(10.0F, metadataLabel.getFont().getSize2D() - 1.0F)));
            add(versionLabel, "growx");
            add(metadataLabel, "growx");
        }

        /// Configures the reusable row for loaded, loading, and failed sparse entries.
        ///
        /// @param list owning version list
        /// @param entry sparse row state
        /// @param index logical row index
        /// @param selected whether the row is selected
        /// @param focused whether the row owns keyboard focus
        /// @return this configured reusable renderer
        @Override
        public Component getListCellRendererComponent(
                JList<? extends ChoiceListEntry<GameVersionCatalogItem>> list,
                ChoiceListEntry<GameVersionCatalogItem> entry,
                int index,
                boolean selected,
                boolean focused) {
            setComponentOrientation(list.getComponentOrientation());
            setOpaque(selected);
            applyPalette(list, selected, focused);
            setToolTipText(null);

            @Nullable GameVersionCatalogItem item = entry.value();
            if (entry.status() == ChoiceLoadStatus.LOADED && item != null) {
                versionLabel.setText(item.versionId());
                metadataLabel.setText(formatVersionMetadata(item));
                setEnabled(list.isEnabled());
            } else if (entry.status() == ChoiceLoadStatus.ERROR) {
                versionLabel.setText("!");
                metadataLabel.setText(" ");
                setEnabled(false);
                @Nullable Throwable failure = entry.failure();
                setToolTipText(failure == null ? null : failure.getMessage());
            } else {
                versionLabel.setText("...");
                metadataLabel.setText(" ");
                setEnabled(false);
            }
            versionLabel.setEnabled(isEnabled());
            metadataLabel.setEnabled(isEnabled());
            return this;
        }

        /// Applies selection and focus colors without changing the renderer's measured height.
        ///
        /// @param list owning version list
        /// @param selected whether the row is selected
        /// @param focused whether the row owns keyboard focus
        private void applyPalette(
                JList<? extends ChoiceListEntry<GameVersionCatalogItem>> list,
                boolean selected,
                boolean focused) {
            Color background = selected ? list.getSelectionBackground() : list.getBackground();
            Color foreground = selected ? list.getSelectionForeground() : list.getForeground();
            @Nullable Color secondary = selected ? foreground : UIManager.getColor("Label.disabledForeground");
            setBackground(background);
            versionLabel.setForeground(foreground);
            metadataLabel.setForeground(secondary == null ? foreground : secondary);
            setBorder(UIManager.getBorder(focused
                    ? "List.focusCellHighlightBorder"
                    : "List.cellNoFocusBorder"));
        }

        /// Formats one loaded row from existing catalog metadata without additional I/O.
        ///
        /// @param item loaded immutable version metadata
        /// @return localized kind and optional release date
        private String formatVersionMetadata(GameVersionCatalogItem item) {
            String kind = strings.kindText(item.kind());
            return item.releaseDate()
                    .map(releaseDate -> kind + " | " + I18n.formatDateTime(
                            releaseDate.atZone(ZoneId.systemDefault())))
                    .orElse(kind);
        }
    }
}

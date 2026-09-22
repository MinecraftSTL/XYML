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
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.addon.RemoteAddon;
import space.minecraftstl.xyml.addon.RemoteAddonRepository;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskExecutor;
import space.minecraftstl.xyml.task.TaskListener;
import space.minecraftstl.xyml.task.presentation.TaskExecutorPresentationModel;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.SwingTextFields;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;
import space.minecraftstl.xyml.ui.swing.choice.ChoiceListEntry;
import space.minecraftstl.xyml.ui.swing.choice.RichChoiceListCellRenderer;
import space.minecraftstl.xyml.ui.swing.choice.RowBoundsCheckedList;
import space.minecraftstl.xyml.ui.swing.choice.ViewportChoiceList;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressHostPanel;
import space.minecraftstl.xyml.ui.swing.task.TaskLaunchController;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Standalone Swing catalog for discovering and installing remote CurseForge or Modrinth modpacks.
///
/// Construction is offline. Once displayable, the panel loads only the selected provider's category
/// metadata; project discovery still waits for Search. A request's server page size is measured from
/// the live result viewport, while local rendering remains lazy through `ViewportChoiceList`.
@NotNullByDefault
public final class RemoteModpackCatalogPanel extends JPanel implements AutoCloseable {
    /// Gateway to blocking Core category, project, and version requests on the background executor.
    private final RemoteModpackCatalogBackend backend;

    /// Factory for the selected-version FileDownloadTask and ModpackHelper installation chain.
    private final RemoteModpackInstallLauncher installLauncher;

    /// Fixed existing update target, or null when the user is creating a new instance.
    private final @Nullable GameInstanceID fixedInstanceId;

    /// Caller-owned worker executor for search and selected-project version resolution.
    private final Executor workerExecutor;

    /// Lazy provider-icon cache sharing the catalog worker boundary.
    private final RemoteAddonIconCache iconCache;

    /// Injected visible text for this catalog surface.
    private final RemoteModpackCatalogStrings strings;

    /// Retained in-memory server-page snapshot exposed through the sparse result list.
    private final RemoteModpackViewportDataSource dataSource = new RemoteModpackViewportDataSource();

    /// Result list that materializes only rows required by the measured viewport.
    private final ViewportChoiceList<RemoteModpackCatalogItem> choiceList;

    /// Progress host for one selected-version install task at a time.
    private final TaskProgressHostPanel progressHost;

    /// Shared confirmed-task submission and navigation controller.
    private final TaskLaunchController taskLaunchController;

    /// Source selector that refreshes category metadata without starting a project search.
    private final JComboBox<RemoteModpackCatalogSource> sourceBox = new JComboBox<>(
            RemoteModpackCatalogSource.values());

    /// Optional project-name or keyword filter editor.
    private final JTextField searchField = new JTextField();

    /// Editable Minecraft-version filter with common launcher versions as suggestions.
    private final JComboBox<String> gameVersionField = new JComboBox<>();

    /// Provider category selector populated asynchronously after the panel becomes displayable.
    private final JComboBox<RemoteCatalogCategoryOption> categoryBox = new JComboBox<>();

    /// Core-supported server result ordering selector.
    private final JComboBox<RemoteAddonRepository.SortType> sortBox = new JComboBox<>();

    /// Exact destination instance-name editor.
    private final JTextField instanceNameField = new JTextField();

    /// Version selector populated only after a loaded project is selected.
    private final JComboBox<RemoteAddon.Version> versionBox = new JComboBox<>();

    /// Explicit ordering selector for the selected project's installable versions.
    private final JComboBox<RemoteAddonVersionSortMode> versionSortBox = new JComboBox<>(
            RemoteAddonVersionSortMode.values());

    /// Renderer that keeps the recommended version visible while the selector is open or closed.
    private final RemoteModpackVersionRenderer versionRenderer = new RemoteModpackVersionRenderer();

    /// Explicit first-page source query command.
    private final JButton searchButton = new JButton();

    /// Direct first server-page navigation command for the completed query.
    private final JButton firstPageButton = new JButton();

    /// Explicit previous server page command.
    private final JButton previousPageButton = new JButton();

    /// Explicit next server page command.
    private final JButton nextPageButton = new JButton();

    /// Direct last server-page navigation command for the completed query.
    private final JButton lastPageButton = new JButton();

    /// Selected-version installation command.
    private final JButton installButton = new JButton();

    /// User-visible request, loading, validation, and terminal-task feedback.
    private final JLabel statusLabel = new JLabel();

    /// Action exposed by the current retryable or returnable status, or null for ordinary feedback.
    private @Nullable Runnable statusAction;

    /// Handles primary clicks on the status text without changing the label-based panel API.
    private final MouseAdapter statusMouseListener = new MouseAdapter() {
        @Override
        public void mouseClicked(MouseEvent event) {
            activateStatusAction(event);
        }
    };

    /// Latest background-search identity; newer criteria or page requests invalidate older callbacks.
    private final AtomicLong catalogRequestRevision = new AtomicLong();

    /// Latest selected-project identity; a newly selected row invalidates older version callbacks.
    private final AtomicLong selectionRequestRevision = new AtomicLong();

    /// Latest provider-category identity; a source change invalidates older category callbacks.
    private final AtomicLong categoryRequestRevision = new AtomicLong();

    /// Listener clearing stale source results after criteria changes without querying the network.
    private final DocumentListener criteriaListener = new CatalogCriteriaListener();

    /// Listener reevaluating install eligibility after the user changes the destination identifier.
    private final DocumentListener instanceNameListener = new InstanceNameListener();

    /// Listener that retries selection once a clicked sparse placeholder row becomes loaded.
    private final ListDataListener listDataListener = new CatalogListDataListener();

    /// Last completed query used to derive explicit next and previous page requests, or null before search.
    private @Nullable RemoteModpackCatalogQuery completedQuery;

    /// Last completed source page, or null before a successful query.
    private @Nullable RemoteModpackCatalogPage displayedPage;

    /// Currently selected loaded project, or null after criteria changes and while no row is selected.
    private @Nullable RemoteModpackCatalogItem selectedItem;

    /// Exact suggested destination generated by the current project, or null after a user edit.
    private @Nullable String suggestedInstanceName;

    /// Current task executor, or null while the catalog accepts a new installation.
    private @Nullable TaskExecutor activeExecutor;

    /// Current task presentation retained until another task replaces it or this panel closes.
    private @Nullable TaskExecutorPresentationModel activePresentation;

    /// Completion subscription owned by the active task executor, or null while idle.
    private @Nullable Subscription activeCompletionSubscription;

    /// Provider whose categories currently populate the selector, or null before a successful load.
    private @Nullable RemoteModpackCatalogSource loadedCategorySource;

    /// Whether category discovery for the selected provider most recently failed.
    private boolean categoryLoadFailed;

    /// Whether a catalog query is waiting for a background result.
    private boolean catalogLoading;

    /// Whether the selected item is waiting for its background version list.
    private boolean versionLoading;

    /// Whether the selected provider's category tree is currently loading.
    private boolean categoryLoading;

    /// Whether category combo-box changes are internal publication rather than user edits.
    private boolean applyingCategoryOptions;

    /// Whether sort combo-box changes are internal source publication rather than user edits.
    private boolean applyingSortOptions;

    /// Provider versions retained for local reordering after the user changes version sort mode.
    private @Unmodifiable List<RemoteAddon.Version> loadedVersions = List.of();

    /// Stable recommendation retained independently from the selected browsing order.
    private @Nullable RemoteAddon.Version recommendedVersion;

    /// Suppresses version-combo callbacks while a new local order is being published.
    private boolean applyingVersionSort;

    /// Whether programmatic destination suggestions should not be treated as user edits.
    private boolean applyingSuggestedInstanceName;

    /// Whether this panel has rejected future user commands and worker callbacks.
    private volatile boolean closed;

    /// Creates a production catalog using Core sources, the shared I/O scheduler, and task-backed installation.
    ///
    /// @param strings visible catalog text
    /// @param taskProgressStrings localized task-progress controls and lifecycle text
    /// @param animator optional shared determinate-progress animator
    /// @param progressAnimationDuration non-negative progress animation duration
    public RemoteModpackCatalogPanel(
            RemoteModpackCatalogStrings strings,
            TaskProgressStrings taskProgressStrings,
            @Nullable SwingAnimator animator,
            Duration progressAnimationDuration) {
        this(
                new CoreRemoteModpackCatalogBackend(),
                new DefaultRemoteModpackInstallLauncher(),
                Schedulers.io(),
                strings,
                taskProgressStrings,
                animator,
                progressAnimationDuration,
                null);
    }

    /// Creates a production catalog using shared confirmed-task navigation.
    ///
    /// @param strings visible catalog text
    /// @param taskProgressStrings localized task-progress controls and lifecycle text
    /// @param animator optional shared determinate-progress animator
    /// @param progressAnimationDuration non-negative progress animation duration
    /// @param taskLaunchController shared confirmed-task submission controller
    public RemoteModpackCatalogPanel(
            RemoteModpackCatalogStrings strings,
            TaskProgressStrings taskProgressStrings,
            @Nullable SwingAnimator animator,
            Duration progressAnimationDuration,
            TaskLaunchController taskLaunchController) {
        this(
                new CoreRemoteModpackCatalogBackend(),
                new DefaultRemoteModpackInstallLauncher(),
                Schedulers.io(),
                strings,
                taskProgressStrings,
                animator,
                progressAnimationDuration,
                null,
                taskLaunchController);
    }

    /// Creates a production repository catalog fixed to one existing modpack instance.
    ///
    /// @param fixedInstanceId existing instance that receives the selected remote version
    /// @param installLauncher update-task factory bound to the same repository and instance
    /// @param strings visible update-catalog text
    /// @param taskProgressStrings localized task-progress controls and lifecycle text
    /// @param animator optional shared determinate-progress animator
    /// @param progressAnimationDuration non-negative progress animation duration
    public RemoteModpackCatalogPanel(
            GameInstanceID fixedInstanceId,
            RemoteModpackInstallLauncher installLauncher,
            RemoteModpackCatalogStrings strings,
            TaskProgressStrings taskProgressStrings,
            @Nullable SwingAnimator animator,
            Duration progressAnimationDuration) {
        this(
                new CoreRemoteModpackCatalogBackend(),
                installLauncher,
                Schedulers.io(),
                strings,
                taskProgressStrings,
                animator,
                progressAnimationDuration,
                Objects.requireNonNull(fixedInstanceId, "fixedInstanceId"));
    }

    /// Creates a production repository catalog with shared confirmed-task navigation.
    public RemoteModpackCatalogPanel(
            GameInstanceID fixedInstanceId,
            RemoteModpackInstallLauncher installLauncher,
            RemoteModpackCatalogStrings strings,
            TaskProgressStrings taskProgressStrings,
            @Nullable SwingAnimator animator,
            Duration progressAnimationDuration,
            TaskLaunchController taskLaunchController) {
        this(
                new CoreRemoteModpackCatalogBackend(),
                installLauncher,
                Schedulers.io(),
                strings,
                taskProgressStrings,
                animator,
                progressAnimationDuration,
                Objects.requireNonNull(fixedInstanceId, "fixedInstanceId"),
                taskLaunchController);
    }

    /// Creates a catalog with explicit Core and task boundaries for focused headless verification.
    ///
    /// The caller retains ownership of the supplied worker executor. The panel closes only its own
    /// sparse list and task presentation resources.
    ///
    /// @param backend blocking source gateway used after explicit user commands
    /// @param installLauncher selected-version task factory
    /// @param workerExecutor background executor for source calls
    /// @param strings visible catalog text
    /// @param taskProgressStrings localized task-progress controls and lifecycle text
    /// @param animator optional shared determinate-progress animator
    /// @param progressAnimationDuration non-negative progress animation duration
    RemoteModpackCatalogPanel(
            RemoteModpackCatalogBackend backend,
            RemoteModpackInstallLauncher installLauncher,
            Executor workerExecutor,
            RemoteModpackCatalogStrings strings,
            TaskProgressStrings taskProgressStrings,
            @Nullable SwingAnimator animator,
            Duration progressAnimationDuration) {
        this(
                backend,
                installLauncher,
                workerExecutor,
                strings,
                taskProgressStrings,
                animator,
                progressAnimationDuration,
                null);
    }

    /// Creates a catalog with optional fixed-instance update semantics.
    ///
    /// @param backend blocking source gateway used after explicit user commands
    /// @param installLauncher selected-version task factory
    /// @param workerExecutor background executor for source calls
    /// @param strings visible catalog text
    /// @param taskProgressStrings localized task-progress controls and lifecycle text
    /// @param animator optional shared determinate-progress animator
    /// @param progressAnimationDuration non-negative progress animation duration
    /// @param fixedInstanceId existing update target, or null for new-instance installation
    RemoteModpackCatalogPanel(
            RemoteModpackCatalogBackend backend,
            RemoteModpackInstallLauncher installLauncher,
            Executor workerExecutor,
            RemoteModpackCatalogStrings strings,
            TaskProgressStrings taskProgressStrings,
            @Nullable SwingAnimator animator,
            Duration progressAnimationDuration,
            @Nullable GameInstanceID fixedInstanceId) {
        this(
                backend,
                installLauncher,
                workerExecutor,
                strings,
                taskProgressStrings,
                animator,
                progressAnimationDuration,
                fixedInstanceId,
                new TaskLaunchController(() -> { }));
    }

    /// Creates a catalog with explicit task navigation ownership.
    RemoteModpackCatalogPanel(
            RemoteModpackCatalogBackend backend,
            RemoteModpackInstallLauncher installLauncher,
            Executor workerExecutor,
            RemoteModpackCatalogStrings strings,
            TaskProgressStrings taskProgressStrings,
            @Nullable SwingAnimator animator,
            Duration progressAnimationDuration,
            @Nullable GameInstanceID fixedInstanceId,
            TaskLaunchController taskLaunchController) {
        super(new MigLayout(
                "insets 0, fill, wrap 1",
                "[grow,fill]",
                "[]8[pref!,shrink 0]8[grow,fill,shrink 100]8[pref!,shrink 0]8[pref!,shrink 0]8[pref!,shrink 0]8[pref!,shrink 0]"));
        EdtDispatcher.requireEventDispatchThread();
        this.backend = Objects.requireNonNull(backend, "backend");
        this.installLauncher = Objects.requireNonNull(installLauncher, "installLauncher");
        this.fixedInstanceId = fixedInstanceId;
        this.workerExecutor = Objects.requireNonNull(workerExecutor, "workerExecutor");
        this.taskLaunchController = Objects.requireNonNull(taskLaunchController, "taskLaunchController");
        iconCache = new RemoteAddonIconCache(this.workerExecutor);
        this.strings = Objects.requireNonNull(strings, "strings");
        TaskProgressStrings resolvedTaskProgressStrings = Objects.requireNonNull(
                taskProgressStrings, "taskProgressStrings");
        Duration resolvedProgressAnimationDuration = Objects.requireNonNull(
                progressAnimationDuration, "progressAnimationDuration");
        if (resolvedProgressAnimationDuration.isNegative()) {
            throw new IllegalArgumentException("progressAnimationDuration must not be negative");
        }
        choiceList = new ViewportChoiceList<>(
                dataSource,
                new RichChoiceListCellRenderer<>(
                        item -> item.addon().title().isBlank() ? item.addon().slug() : item.addon().title(),
                        item -> remoteModpackRowDetail(item.addon()),
                        item -> item.source().displayName(),
                        item -> iconCache.icon(item.addon().iconUrl(), this::repaint),
                        item -> item.addon().pageUrl()), RowBoundsCheckedList.BlankClickPolicy.CLEAR);
        progressHost = new TaskProgressHostPanel(
                resolvedTaskProgressStrings,
                animator,
                resolvedProgressAnimationDuration);
        configureComponents();
        updateControls();
        setStatus(strings.initialStatus());
    }

    /// Returns the viewport-driven remote result list for integration and focused tests.
    ///
    /// @return owned sparse single-choice result list
    public ViewportChoiceList<RemoteModpackCatalogItem> choiceList() {
        return choiceList;
    }

    /// Starts provider category discovery only when this panel receives a peer while visible.
    @Override
    public void addNotify() {
        super.addNotify();
        EdtDispatcher.requireEventDispatchThread();
        if (isVisible()) {
            requestCategoriesForSelectedSource();
        }
    }

    /// Lazily loads provider categories when a previously hidden catalog card becomes visible.
    ///
    /// @param visible requested local visibility
    @Override
    public void setVisible(boolean visible) {
        super.setVisible(visible);
        if (visible && isDisplayable()) {
            EdtDispatcher.requireEventDispatchThread();
            requestCategoriesForSelectedSource();
        }
    }

    /// Synchronously rejects future callbacks, cancels active installation, and releases owned presentation state.
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        catalogRequestRevision.incrementAndGet();
        selectionRequestRevision.incrementAndGet();
        categoryRequestRevision.incrementAndGet();
        SwingUiDispatcher.INSTANCE.dispatchOrRun(this::closeOnEventDispatchThread);
    }

    /// Configures the static shell and listener wiring without issuing any remote request.
    private void configureComponents() {
        setName("remoteModpackCatalog");
        setOpaque(false);
        setMinimumSize(new Dimension(0, 0));

        JPanel headingBand = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill]", "[]"));
        headingBand.setOpaque(false);
        headingBand.setMinimumSize(new Dimension(0, 0));
        JLabel heading = new JLabel(strings.pageTitle());
        heading.setName("remoteModpackCatalogTitle");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 28.0F));
        headingBand.add(heading, "growx");
        add(headingBand, "growx");

        JPanel filterBand = new JPanel(new MigLayout(
                "insets 0, fillx, wrap 1",
                "[grow,fill]",
                "[40!]8[40!]8[40!]"));
        filterBand.setName("remoteModpackFilterBand");
        filterBand.setOpaque(false);
        filterBand.setMinimumSize(new Dimension(0, 0));

        JPanel criteriaBand = new JPanel(new MigLayout(
                "insets 0, fillx, wrap 4",
                "[grow,fill][grow,fill][grow,fill][grow,fill]",
                "[40!]"));
        criteriaBand.setName("remoteModpackCriteriaBand");
        criteriaBand.setOpaque(false);
        criteriaBand.setMinimumSize(new Dimension(0, 0));

        JLabel sourceLabel = new JLabel(strings.sourceLabel());
        sourceLabel.setLabelFor(sourceBox);
        sourceBox.setName("remoteModpackSource");
        sourceBox.addActionListener(event -> sourceChanged());
        criteriaBand.add(RemoteCatalogFilterField.create(sourceLabel, sourceBox), "growx, wmin 0");

        JLabel gameVersionLabel = new JLabel(strings.gameVersionLabel());
        gameVersionLabel.setLabelFor(gameVersionField);
        gameVersionField.setName("remoteModpackGameVersion");
        configureGameVersionSelector();
        criteriaBand.add(RemoteCatalogFilterField.create(gameVersionLabel, gameVersionField), "growx, wmin 0");

        RemoteCatalogFilterStrings filterStrings = strings.filterStrings();
        JLabel categoryLabel = new JLabel(filterStrings.categoryLabel());
        categoryLabel.setLabelFor(categoryBox);
        categoryBox.setName("remoteModpackCategory");
        categoryBox.setRenderer(new RemoteCatalogCategoryRenderer(
                () -> selectedSource() == RemoteModpackCatalogSource.MODRINTH,
                filterStrings));
        resetCategoryOptions();
        categoryBox.addActionListener(event -> categoryChanged());
        criteriaBand.add(RemoteCatalogFilterField.create(categoryLabel, categoryBox), "growx, wmin 0");

        JLabel sortLabel = new JLabel(filterStrings.sortLabel());
        sortLabel.setLabelFor(sortBox);
        sortBox.setName("remoteModpackSort");
        sortBox.setRenderer(new RemoteCatalogSortRenderer(filterStrings));
        resetSortOptions();
        sortBox.addActionListener(event -> sortChanged());
        criteriaBand.add(RemoteCatalogFilterField.create(sortLabel, sortBox), "growx, wmin 0");
        filterBand.add(criteriaBand, "growx, wmin 0");

        JPanel searchBand = new JPanel(new MigLayout(
                "insets 0, fillx, wrap 4",
                "[grow,fill][grow,fill][grow,fill][grow,fill]",
                "[40!]"));
        searchBand.setName("remoteModpackSearchBand");
        searchBand.setOpaque(false);
        searchBand.setMinimumSize(new Dimension(0, 0));

        JLabel searchLabel = new JLabel(strings.searchLabel());
        searchLabel.setLabelFor(searchField);
        searchField.setName("remoteModpackSearch");
        SwingTextFields.showClearButton(searchField);
        searchField.getDocument().addDocumentListener(criteriaListener);
        searchBand.add(RemoteCatalogFilterField.create(searchLabel, searchField), "span 3, growx, wmin 0");

        searchButton.setName("remoteModpackSearchAction");
        searchButton.setText(strings.searchAction());
        searchButton.addActionListener(event -> submitFirstPageSearch());
        searchButton.setMinimumSize(new Dimension(0, 0));
        searchBand.add(searchButton, "grow, wmin 0, h 40!");
        filterBand.add(searchBand, "growx, wmin 0");

        JPanel pageBand = new JPanel(new MigLayout(
                "insets 0, fillx",
                "[grow,fill][grow,fill][grow,fill][grow,fill]",
                "[40!]"));
        pageBand.setName("remoteModpackPageBand");
        pageBand.setOpaque(false);
        pageBand.setMinimumSize(new Dimension(0, 0));

        firstPageButton.setName("remoteModpackFirstPage");
        firstPageButton.setText(i18n("search.first_page"));
        firstPageButton.addActionListener(event -> submitBoundaryPage(false));
        firstPageButton.setMinimumSize(new Dimension(0, 0));
        pageBand.add(firstPageButton, "grow, wmin 0, h 40!");
        previousPageButton.setName("remoteModpackPreviousPage");
        previousPageButton.setText(strings.previousPageAction());
        previousPageButton.addActionListener(event -> submitRelativePage(-1));
        previousPageButton.setMinimumSize(new Dimension(0, 0));
        pageBand.add(previousPageButton, "grow, wmin 0, h 40!");
        nextPageButton.setName("remoteModpackNextPage");
        nextPageButton.setText(strings.nextPageAction());
        nextPageButton.addActionListener(event -> submitRelativePage(1));
        nextPageButton.setMinimumSize(new Dimension(0, 0));
        pageBand.add(nextPageButton, "grow, wmin 0, h 40!");
        lastPageButton.setName("remoteModpackLastPage");
        lastPageButton.setText(i18n("search.last_page"));
        lastPageButton.addActionListener(event -> submitBoundaryPage(true));
        lastPageButton.setMinimumSize(new Dimension(0, 0));
        pageBand.add(lastPageButton, "grow, wmin 0, h 40!");
        filterBand.add(pageBand, "growx, wmin 0");
        add(filterBand, "growx");

        choiceList.setName("remoteModpackResults");
        choiceList.setOpaque(false);
        choiceList.getViewport().setOpaque(false);
        JList<ChoiceListEntry<RemoteModpackCatalogItem>> resultList = choiceList.getList();
        resultList.setName("remoteModpackResultsView");
        resultList.setOpaque(false);
        resultList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                selectedRowChanged();
            }
        });
        choiceList.getChoiceModel().addListDataListener(listDataListener);
        add(choiceList, "grow");

        JPanel installBand = new JPanel(new MigLayout(
                "insets 0, fillx, wrap 6",
                "[pref!][grow,fill][pref!][grow,fill][pref!][grow,fill]",
                "[40!]8[40!]"));
        installBand.setName("remoteModpackInstallBand");
        installBand.setOpaque(false);
        installBand.setMinimumSize(new Dimension(0, 0));
        JLabel versionLabel = new JLabel(strings.versionLabel());
        versionLabel.setLabelFor(versionBox);
        installBand.add(versionLabel);
        versionBox.setName("remoteModpackVersion");
        versionBox.setRenderer(versionRenderer);
        versionBox.addActionListener(event -> updateControls());
        versionBox.setMinimumSize(new Dimension(0, 0));
        installBand.add(versionBox, "growx, wmin 0, h 40!");

        JLabel versionSortLabel = new JLabel(filterStrings.versionSortLabel());
        versionSortLabel.setLabelFor(versionSortBox);
        installBand.add(versionSortLabel);
        versionSortBox.setName("remoteModpackVersionSort");
        versionSortBox.setRenderer(new RemoteAddonVersionSortRenderer(filterStrings));
        versionSortBox.addActionListener(event -> versionSortChanged());
        versionSortBox.setMinimumSize(new Dimension(0, 0));
        installBand.add(versionSortBox, "growx, wmin 0, h 40!");

        JLabel instanceNameLabel = new JLabel(strings.instanceNameLabel());
        instanceNameLabel.setLabelFor(instanceNameField);
        installBand.add(instanceNameLabel);
        instanceNameField.setName("remoteModpackInstanceName");
        if (fixedInstanceId == null) {
            SwingTextFields.showClearButton(instanceNameField);
            instanceNameField.getDocument().addDocumentListener(instanceNameListener);
        } else {
            instanceNameField.setText(fixedInstanceId.id());
            instanceNameField.setEditable(false);
        }
        instanceNameField.setMinimumSize(new Dimension(0, 0));
        installBand.add(instanceNameField, "growx, wmin 0, h 40!");
        installButton.setName("remoteModpackInstall");
        installButton.setText(strings.installAction());
        installButton.addActionListener(event -> beginInstall());
        installButton.setMinimumSize(new Dimension(0, 0));
        installBand.add(installButton, "span 6, grow, wmin 0, h 40!");
        add(installBand, "growx");

        statusLabel.setName("remoteModpackStatus");
        statusLabel.addMouseListener(statusMouseListener);
        add(statusLabel, "growx, h 24!");
        progressHost.setName("remoteModpackInstallProgress");
    }

    /// Configures the editable game-version selector with the launcher's common version choices.
    ///
    /// The empty first item preserves the unfiltered query. The editor remains free-form so a
    /// provider-specific or newly released version can still be entered before the local list is
    /// refreshed.
    private void configureGameVersionSelector() {
        EdtDispatcher.requireEventDispatchThread();
        gameVersionField.setEditable(true);
        gameVersionField.setMaximumRowCount(12);
        if (gameVersionField.getItemCount() == 0) {
            gameVersionField.addItem("");
            for (String version : GameVersionNumber.getDefaultGameVersions()) {
                if (!version.isBlank()) {
                    gameVersionField.addItem(version);
                }
            }
            gameVersionField.setSelectedItem("");
        }
        SwingTextFields.showClearButton(gameVersionField);
        SwingTextFields.textEditor(gameVersionField).getDocument().addDocumentListener(criteriaListener);
        gameVersionField.addActionListener(event -> criteriaChanged());
    }

    /// Formats the description, author, and provider tags already present in one remote modpack.
    ///
    /// @param addon loaded remote project metadata
    /// @return compact metadata line suitable for a narrow result row
    private static String remoteModpackRowDetail(RemoteAddon addon) {
        RemoteAddon selected = Objects.requireNonNull(addon, "addon");
        List<String> values = new java.util.ArrayList<>();
        String description = selected.description().lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .findFirst()
                .orElse("");
        if (!description.isBlank()) {
            values.add(description);
        }
        if (!selected.author().isBlank()) {
            values.add(selected.author());
        }
        if (!selected.categories().isEmpty()) {
            values.add(String.join(", ", selected.categories()));
        }
        if (values.isEmpty()) {
            values.add(selected.slug());
        }
        return String.join(" | ", values);
    }

    /// Invalidates source-specific categories, clears stale results, and loads the new tree when visible.
    private void sourceChanged() {
        EdtDispatcher.requireEventDispatchThread();
        categoryRequestRevision.incrementAndGet();
        categoryLoading = false;
        loadedCategorySource = null;
        categoryLoadFailed = false;
        resetCategoryOptions();
        resetSortOptions();
        criteriaChanged();
        if (isDisplayable()) {
            requestCategoriesForSelectedSource();
        }
    }

    /// Clears stale results after a user category selection while ignoring internal option publication.
    private void categoryChanged() {
        EdtDispatcher.requireEventDispatchThread();
        if (!applyingCategoryOptions) {
            criteriaChanged();
        }
    }

    /// Clears stale results after a user sort selection while ignoring internal source publication.
    private void sortChanged() {
        EdtDispatcher.requireEventDispatchThread();
        if (!applyingSortOptions) {
            criteriaChanged();
        }
    }

    /// Schedules category discovery for the selected available provider at most once per successful load.
    private void requestCategoriesForSelectedSource() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || categoryLoading) {
            return;
        }
        RemoteModpackCatalogSource source = selectedSource();
        if (!source.isAvailable()) {
            setStatus(strings.sourceUnavailableStatus());
            updateControls();
            return;
        }
        if (loadedCategorySource == source) {
            updateControls();
            return;
        }
        long requestRevision = categoryRequestRevision.incrementAndGet();
        categoryLoading = true;
        categoryLoadFailed = false;
        updateControls();
        try {
            workerExecutor.execute(() -> loadCategories(source, requestRevision));
        } catch (RuntimeException schedulingFailure) {
            LOG.warning("Failed to schedule remote modpack category loading", schedulingFailure);
            applyCategoryFailure(source, requestRevision);
        }
    }

    /// Loads one provider category tree away from the EDT.
    ///
    /// @param source selected provider captured before worker scheduling
    /// @param requestRevision category request identity
    private void loadCategories(RemoteModpackCatalogSource source, long requestRevision) {
        try {
            @Unmodifiable List<RemoteAddonRepository.Category> categories = backend.loadCategories(source);
            SwingUiDispatcher.INSTANCE.dispatchOrRun(
                    () -> applyCategories(source, categories, requestRevision));
        } catch (IOException | RuntimeException failure) {
            LOG.warning("Failed to load remote modpack categories", failure);
            applyCategoryFailure(source, requestRevision);
        }
    }

    /// Publishes a provider category tree only while its source and request remain current.
    ///
    /// @param source provider that produced the categories
    /// @param categories immutable provider category roots
    /// @param requestRevision category request identity
    private void applyCategories(
            RemoteModpackCatalogSource source,
            @Unmodifiable List<RemoteAddonRepository.Category> categories,
            long requestRevision) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || categoryRequestRevision.get() != requestRevision || selectedSource() != source) {
            return;
        }
        boolean failureStatusVisible = strings.categoryLoadFailedStatus().equals(statusLabel.getText());
        categoryLoading = false;
        loadedCategorySource = source;
        categoryLoadFailed = false;
        applyCategoryOptions(RemoteCatalogCategoryOption.flatten(categories));
        if (failureStatusVisible) {
            setStatus(catalogIdleStatus());
        }
        updateControls();
    }

    /// Restores the all-categories selector after a current provider category request fails.
    ///
    /// @param source provider whose category request failed
    /// @param requestRevision category request identity
    private void applyCategoryFailure(RemoteModpackCatalogSource source, long requestRevision) {
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (closed || categoryRequestRevision.get() != requestRevision || selectedSource() != source) {
                return;
            }
            categoryLoading = false;
            loadedCategorySource = null;
            categoryLoadFailed = true;
            resetCategoryOptions();
            if (canShowCategoryFailure()) {
                setStatus(strings.categoryLoadFailedStatus(), this::retryCategories);
            }
            updateControls();
        });
    }

    /// Retries loading category metadata for the still-selected provider.
    private void retryCategories() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || categoryLoading) {
            return;
        }
        requestCategoriesForSelectedSource();
    }

    /// Replaces category options without interpreting combo-box events as user filter edits.
    ///
    /// @param options immutable flattened provider category options
    private void applyCategoryOptions(@Unmodifiable List<RemoteCatalogCategoryOption> options) {
        applyingCategoryOptions = true;
        try {
            categoryBox.removeAllItems();
            for (RemoteCatalogCategoryOption option : Objects.requireNonNull(options, "options")) {
                categoryBox.addItem(option);
            }
            if (categoryBox.getItemCount() > 0) {
                categoryBox.setSelectedIndex(0);
            }
        } finally {
            applyingCategoryOptions = false;
        }
    }

    /// Restores the selector's local all-categories option without performing provider work.
    private void resetCategoryOptions() {
        applyCategoryOptions(List.of(RemoteCatalogCategoryOption.all()));
    }

    /// Publishes every ordering exposed by the current provider catalog control.
    private void resetSortOptions() {
        applyingSortOptions = true;
        try {
            sortBox.removeAllItems();
            for (RemoteAddonRepository.SortType sortType : selectedSource().supportedSortTypes()) {
                sortBox.addItem(sortType);
            }
            sortBox.setSelectedItem(RemoteAddonRepository.SortType.POPULARITY);
        } finally {
            applyingSortOptions = false;
        }
    }

    /// Starts an explicit user-requested first-page remote source query.
    private void submitFirstPageSearch() {
        EdtDispatcher.requireEventDispatchThread();
        submitSearch(0);
    }

    /// Starts direct first- or last-page navigation for the last completed query.
    ///
    /// @param lastPage true to request the last page, or false to request the first page
    private void submitBoundaryPage(boolean lastPage) {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable RemoteModpackCatalogPage page = displayedPage;
        if (page == null) {
            return;
        }
        submitCompletedQueryPage(lastPage ? page.totalPages() - 1 : 0);
    }

    /// Starts a user-requested adjacent page query based on the last completed query filters.
    ///
    /// @param direction negative one for previous and positive one for next
    private void submitRelativePage(int direction) {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable RemoteModpackCatalogPage previousPage = displayedPage;
        if (previousPage == null) {
            return;
        }
        submitCompletedQueryPage(previousPage.pageOffset() + direction);
    }

    /// Validates and starts an exact page request against the last completed query criteria.
    ///
    /// @param pageOffset zero-based server page to request
    private void submitCompletedQueryPage(int pageOffset) {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable RemoteModpackCatalogQuery previousQuery = completedQuery;
        @Nullable RemoteModpackCatalogPage previousPage = displayedPage;
        if (previousQuery == null || previousPage == null || catalogLoading || activeExecutor != null
                || pageOffset < 0 || pageOffset >= previousPage.totalPages()) {
            return;
        }
        submitSearch(pageOffset);
    }

    /// Measures the current result viewport and schedules one remote search only after an explicit command.
    ///
    /// @param pageOffset zero-based source page requested by the user
    private void submitSearch(int pageOffset) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || catalogLoading || activeExecutor != null) {
            return;
        }
        RemoteModpackCatalogSource source = selectedSource();
        if (!source.isAvailable()) {
            setStatus(strings.sourceUnavailableStatus());
            return;
        }
        int pageSize = measuredPageSize();
        if (pageSize == 0) {
            setStatus(strings.viewportUnavailableStatus());
            return;
        }

        RemoteModpackCatalogQuery query = new RemoteModpackCatalogQuery(
                source,
                searchField.getText(),
                SwingTextFields.comboText(gameVersionField),
                selectedCategory(),
                selectedSortType(),
                pageOffset,
                pageSize);
        long requestRevision = catalogRequestRevision.incrementAndGet();
        selectionRequestRevision.incrementAndGet();
        catalogLoading = true;
        completedQuery = null;
        displayedPage = null;
        clearSelectedProject();
        dataSource.replaceItems(List.of());
        choiceList.reloadData();
        setStatus(strings.loadingStatus());
        updateControls();
        try {
            workerExecutor.execute(() -> loadCatalogPage(query, requestRevision));
        } catch (RuntimeException schedulingFailure) {
            LOG.warning("Failed to schedule a remote modpack catalog request", schedulingFailure);
            applyCatalogFailure(requestRevision);
        }
    }

    /// Runs one Core source query away from the EDT and routes its result to the current panel revision.
    ///
    /// @param query explicit source request assembled on the EDT
    /// @param requestRevision revision paired with this worker invocation
    private void loadCatalogPage(RemoteModpackCatalogQuery query, long requestRevision) {
        try {
            RemoteModpackCatalogPage page = backend.search(query);
            SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> applyCatalogPage(query, page, requestRevision));
        } catch (IOException | RuntimeException failure) {
            LOG.warning("Failed to search remote modpack catalog", failure);
            applyCatalogFailure(requestRevision);
        }
    }

    /// Applies a completed remote result page only when it remains this panel's newest request.
    ///
    /// @param query request that produced the page
    /// @param page immutable returned source page
    /// @param requestRevision request identity captured before background work
    private void applyCatalogPage(
            RemoteModpackCatalogQuery query,
            RemoteModpackCatalogPage page,
            long requestRevision) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || catalogRequestRevision.get() != requestRevision) {
            return;
        }
        catalogLoading = false;
        completedQuery = Objects.requireNonNull(query, "query");
        displayedPage = Objects.requireNonNull(page, "page");
        dataSource.replaceItems(page.items());
        choiceList.reloadData();
        setCatalogIdleStatus(page.items().isEmpty() ? strings.noResultsStatus() : "");
        updateControls();
    }

    /// Restores editable controls after a current background source request fails.
    ///
    /// @param requestRevision request identity that failed
    private void applyCatalogFailure(long requestRevision) {
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (closed || catalogRequestRevision.get() != requestRevision) {
                return;
            }
            catalogLoading = false;
            setStatus(strings.searchFailedStatus(), this::retryCatalogSearch);
            updateControls();
        });
    }

    /// Starts background resolution only when a selected sparse list row has materialized a true project value.
    private void selectedRowChanged() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || catalogLoading || activeExecutor != null) {
            return;
        }
        @Nullable RemoteModpackCatalogItem item = choiceList.getSelectedValue();
        if (item == null || item == selectedItem) {
            return;
        }
        long requestRevision = selectionRequestRevision.incrementAndGet();
        selectedItem = item;
        versionLoading = true;
        loadedVersions = List.of();
        recommendedVersion = null;
        versionBox.removeAllItems();
        suggestInstanceName(item);
        setStatus(strings.loadingVersionsStatus());
        updateControls();
        try {
            workerExecutor.execute(() -> loadSelectedVersions(item, requestRevision));
        } catch (RuntimeException schedulingFailure) {
            LOG.warning("Failed to schedule remote modpack version loading", schedulingFailure);
            applyVersionFailure(item, requestRevision);
        }
    }

    /// Retries the failed first-page search using the current criteria and measured viewport.
    private void retryCatalogSearch() {
        EdtDispatcher.requireEventDispatchThread();
        submitFirstPageSearch();
    }

    /// Retries loading versions for the currently selected project.
    private void retrySelectedVersions() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || catalogLoading || activeExecutor != null || versionLoading) {
            return;
        }
        @Nullable RemoteModpackCatalogItem item = selectedItem;
        if (item == null) {
            return;
        }
        long requestRevision = selectionRequestRevision.incrementAndGet();
        versionLoading = true;
        loadedVersions = List.of();
        recommendedVersion = null;
        versionBox.removeAllItems();
        setStatus(strings.loadingVersionsStatus());
        updateControls();
        try {
            workerExecutor.execute(() -> loadSelectedVersions(item, requestRevision));
        } catch (RuntimeException schedulingFailure) {
            LOG.warning("Failed to schedule remote modpack version loading retry", schedulingFailure);
            applyVersionFailure(item, requestRevision);
        }
    }

    /// Returns from an empty selected-project version list to the loaded project results.
    private void returnFromEmptyVersions() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || catalogLoading || activeExecutor != null) {
            return;
        }
        selectionRequestRevision.incrementAndGet();
        clearSelectedProject();
        setCatalogIdleStatus("");
        updateControls();
    }

    /// Dispatches one primary status-label click to its current retry or return action.
    ///
    /// @param event mouse event delivered by the status label
    private void activateStatusAction(MouseEvent event) {
        EdtDispatcher.requireEventDispatchThread();
        if (event.getClickCount() != 1 || event.getButton() != MouseEvent.BUTTON1) {
            return;
        }
        @Nullable Runnable action = statusAction;
        if (action == null || closed) {
            return;
        }
        statusAction = null;
        statusLabel.setCursor(Cursor.getDefaultCursor());
        action.run();
    }

    /// Reorders the retained selected-project versions without issuing another provider request.
    private void versionSortChanged() {
        EdtDispatcher.requireEventDispatchThread();
        if (applyingVersionSort || loadedVersions.isEmpty() || selectedItem == null) {
            return;
        }
        @Nullable RemoteAddon.Version previousSelection =
                (RemoteAddon.Version) versionBox.getSelectedItem();
        @Unmodifiable List<RemoteAddon.Version> orderedVersions = orderedLoadedVersions();
        versionRenderer.setSelectionContext(this.recommendedVersion, SwingTextFields.comboText(gameVersionField));
        applyingVersionSort = true;
        try {
            versionBox.removeAllItems();
            for (RemoteAddon.Version version : orderedVersions) {
                versionBox.addItem(version);
            }
            if (previousSelection != null && orderedVersions.contains(previousSelection)) {
                versionBox.setSelectedItem(previousSelection);
            } else if (this.recommendedVersion != null) {
                versionBox.setSelectedItem(this.recommendedVersion);
            }
        } finally {
            applyingVersionSort = false;
        }
        updateControls();
    }

    /// Returns the retained versions in the currently selected user-facing order.
    ///
    /// @return immutable ordered version snapshot
    private @Unmodifiable List<RemoteAddon.Version> orderedLoadedVersions() {
        return RemoteAddonVersionOrdering.order(
                loadedVersions,
                SwingTextFields.comboText(gameVersionField),
                Objects.requireNonNull(
                        (RemoteAddonVersionSortMode) versionSortBox.getSelectedItem(),
                        "remote modpack version sort mode"));
    }

    /// Loads versions for one user-selected project away from the EDT.
    ///
    /// @param item selected loaded project
    /// @param requestRevision selection identity captured before worker scheduling
    private void loadSelectedVersions(RemoteModpackCatalogItem item, long requestRevision) {
        try {
            List<RemoteAddon.Version> versions = backend.loadVersions(item);
            SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> applyVersions(item, versions, requestRevision));
        } catch (IOException | RuntimeException failure) {
            LOG.warning("Failed to load remote modpack versions", failure);
            applyVersionFailure(item, requestRevision);
        }
    }

    /// Publishes a selected project's versions only when that project remains selected.
    ///
    /// @param item selected project represented by the loaded versions
    /// @param versions provider-ordered installable versions
    /// @param requestRevision selection identity captured before worker scheduling
    private void applyVersions(
            RemoteModpackCatalogItem item,
            List<RemoteAddon.Version> versions,
            long requestRevision) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || selectionRequestRevision.get() != requestRevision || selectedItem != item) {
            return;
        }
        versionLoading = false;
        loadedVersions = List.copyOf(Objects.requireNonNull(versions, "versions"));
        @Unmodifiable List<RemoteAddon.Version> orderedVersions = orderedLoadedVersions();
        String requestedGameVersion = SwingTextFields.comboText(gameVersionField);
        recommendedVersion = RemoteAddonVersionOrdering.recommended(
                loadedVersions,
                requestedGameVersion);
        versionRenderer.setSelectionContext(recommendedVersion, requestedGameVersion);
        for (RemoteAddon.Version version : orderedVersions) {
            versionBox.addItem(version);
        }
        if (versionBox.getItemCount() > 0) {
            if (recommendedVersion == null) {
                versionBox.setSelectedIndex(0);
            } else {
                versionBox.setSelectedItem(recommendedVersion);
            }
            setStatus("");
        } else {
            setStatus(strings.noVersionsStatus(), this::returnFromEmptyVersions);
        }
        updateControls();
    }

    /// Publishes selected-project version failure feedback only while its selection remains current.
    ///
    /// @param item selected project whose version request failed
    /// @param requestRevision selection identity captured before worker scheduling
    private void applyVersionFailure(RemoteModpackCatalogItem item, long requestRevision) {
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (closed || selectionRequestRevision.get() != requestRevision || selectedItem != item) {
                return;
            }
            versionLoading = false;
            loadedVersions = List.of();
            recommendedVersion = null;
            versionBox.removeAllItems();
            setStatus(strings.versionLoadFailedStatus(), this::retrySelectedVersions);
            updateControls();
        });
    }

    /// Validates a user-confirmed selection and starts its existing task-based installation workflow.
    private void beginInstall() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || !installButton.isEnabled()) {
            return;
        }
        @Nullable RemoteModpackCatalogItem item = selectedItem;
        @Nullable RemoteAddon.Version version = (RemoteAddon.Version) versionBox.getSelectedItem();
        String instanceName = instanceNameField.getText().trim();
        @Nullable GameInstanceID targetInstance = fixedInstanceId;
        if (targetInstance == null && XYMLGameRepository.isValidInstanceId(instanceName)) {
            targetInstance = new GameInstanceID(instanceName);
        }
        if (item == null || version == null || targetInstance == null) {
            setStatus(strings.invalidInstanceNameStatus());
            updateControls();
            return;
        }

        releaseCompletedPresentation();
        final Task<?> task;
        try {
            setStatus(strings.preparingInstallStatus());
            task = installLauncher.createInstallTask(new RemoteModpackInstallRequest(
                    item,
                    version,
                    targetInstance));
        } catch (IOException | RuntimeException preparationFailure) {
            LOG.warning("Failed to prepare a selected remote modpack installation", preparationFailure);
            setStatus(strings.installFailedStatus());
            updateControls();
            return;
        }

        TaskExecutor executor = task.executor();
        TaskExecutorPresentationModel presentation = new TaskExecutorPresentationModel(
                executor,
                strings.installingStatus(),
                strings.preparingInstallStatus());
        Subscription completionSubscription = executor.subscribeTaskListener(
                new InstallCompletionListener(executor));
        activeExecutor = executor;
        activePresentation = presentation;
        activeCompletionSubscription = completionSubscription;
        setStatus(strings.installingStatus());
        updateControls();
        try {
            taskLaunchController.launch(executor, strings.installingStatus(), () -> { });
        } catch (RuntimeException | Error startFailure) {
            LOG.warning("Failed to start selected remote modpack installation", startFailure);
            cleanupFailedTaskStart(presentation, completionSubscription);
            setStatus(strings.installFailedStatus());
            updateControls();
        }
    }

    /// Publishes a terminal selected-modpack task result and reopens catalog controls.
    ///
    /// @param executor task executor that reached its terminal state
    /// @param succeeded whether the complete installation chain succeeded
    private void installCompleted(TaskExecutor executor, boolean succeeded) {
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (closed || activeExecutor != executor) {
                return;
            }
            unsubscribe(activeCompletionSubscription);
            activeCompletionSubscription = null;
            activeExecutor = null;
            releaseCompletedPresentation();
            setStatus(succeeded ? strings.installSucceededStatus() : strings.installFailedStatus());
            updateControls();
        });
    }

    /// Clears a terminal task presentation before constructing a later installation task.
    private void releaseCompletedPresentation() {
        EdtDispatcher.requireEventDispatchThread();
        if (activeExecutor != null) {
            return;
        }
        @Nullable TaskExecutorPresentationModel previousPresentation = activePresentation;
        activePresentation = null;
        progressHost.clear();
        if (previousPresentation != null) {
            previousPresentation.close();
        }
    }

    /// Releases a task presentation when startup fails before its executor reaches a terminal callback.
    ///
    /// @param presentation presentation created for the failed executor startup
    /// @param completionSubscription completion listener created for the failed executor startup
    private void cleanupFailedTaskStart(
            TaskExecutorPresentationModel presentation,
            Subscription completionSubscription) {
        unsubscribe(completionSubscription);
        activeCompletionSubscription = null;
        activeExecutor = null;
        if (activePresentation == presentation) {
            activePresentation = null;
        }
        progressHost.clear();
        presentation.close();
    }

    /// Removes stale result state after filter or provider changes without making a source request.
    private void criteriaChanged() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || catalogLoading || activeExecutor != null) {
            return;
        }
        catalogRequestRevision.incrementAndGet();
        selectionRequestRevision.incrementAndGet();
        completedQuery = null;
        displayedPage = null;
        clearSelectedProject();
        dataSource.replaceItems(List.of());
        choiceList.reloadData();
        setCatalogIdleStatus(strings.initialStatus());
        updateControls();
    }

    /// Shows category retry feedback whenever an otherwise passive catalog status is published.
    ///
    /// @param status ordinary passive catalog status
    private void setCatalogIdleStatus(String status) {
        if (categoryLoadFailed) {
            setStatus(strings.categoryLoadFailedStatus(), this::retryCategories);
        } else {
            setStatus(status);
        }
    }

    /// Returns the ordinary passive status appropriate for the currently displayed result page.
    ///
    /// @return initial, empty-result, or blank loaded-result status
    private String catalogIdleStatus() {
        if (completedQuery == null || displayedPage == null) {
            return strings.initialStatus();
        }
        return displayedPage.items().isEmpty() ? strings.noResultsStatus() : "";
    }

    /// Tests whether category feedback can replace the current status without hiding active work or recovery.
    ///
    /// @return true when the visible status is passive catalog feedback
    private boolean canShowCategoryFailure() {
        @Nullable String status = statusLabel.getText();
        return status == null
                || status.isBlank()
                || status.equals(strings.initialStatus())
                || status.equals(strings.noResultsStatus())
                || status.equals(strings.categoryLoadFailedStatus());
    }

    /// Clears list selection, version state, and project-derived instance-name ownership.
    private void clearSelectedProject() {
        EdtDispatcher.requireEventDispatchThread();
        choiceList.getList().clearSelection();
        selectedItem = null;
        versionLoading = false;
        loadedVersions = List.of();
        recommendedVersion = null;
        versionBox.removeAllItems();
        versionRenderer.setSelectionContext(null, "");
        suggestedInstanceName = null;
    }

    /// Suggests a destination identifier without overwriting independently authored user text.
    ///
    /// @param item selected project supplying a stable slug suggestion
    private void suggestInstanceName(RemoteModpackCatalogItem item) {
        EdtDispatcher.requireEventDispatchThread();
        if (fixedInstanceId != null) {
            return;
        }
        String existing = instanceNameField.getText().trim();
        @Nullable String previousSuggestion = suggestedInstanceName;
        String suggestion = item.suggestedInstanceName();
        if (existing.isEmpty() || Objects.equals(existing, previousSuggestion)) {
            applyingSuggestedInstanceName = true;
            try {
                instanceNameField.setText(suggestion);
            } finally {
                applyingSuggestedInstanceName = false;
            }
        }
        suggestedInstanceName = suggestion;
    }

    /// Returns the selected source while preserving the non-null combo-box value invariant.
    ///
    /// @return selected remote source
    private RemoteModpackCatalogSource selectedSource() {
        return Objects.requireNonNull(
                (RemoteModpackCatalogSource) sourceBox.getSelectedItem(),
                "remote modpack source selection");
    }

    /// Returns the selected provider category, or null for the explicit all-categories option.
    ///
    /// @return selected provider category or null
    private @Nullable RemoteAddonRepository.Category selectedCategory() {
        @Nullable RemoteCatalogCategoryOption option =
                (RemoteCatalogCategoryOption) categoryBox.getSelectedItem();
        return option == null ? null : option.category();
    }

    /// Returns the selected Core result ordering while preserving the combo-box non-null invariant.
    ///
    /// @return selected provider-supported sort
    private RemoteAddonRepository.SortType selectedSortType() {
        return Objects.requireNonNull(
                (RemoteAddonRepository.SortType) sortBox.getSelectedItem(),
                "remote modpack sort selection");
    }

    /// Returns the server page size derived from currently visible result rows, or zero before layout exists.
    ///
    /// @return positive measured visible row count, or zero when no actual viewport can be measured
    private int measuredPageSize() {
        Dimension extent = choiceList.getViewport().getExtentSize();
        int rowHeight = choiceList.getList().getFixedCellHeight();
        if (extent.height <= 0 || rowHeight <= 0) {
            return 0;
        }
        return Math.max(1, Math.floorDiv(extent.height + rowHeight - 1, rowHeight));
    }

    /// Reconciles the enabled state of every command from current query, selection, and task state.
    private void updateControls() {
        EdtDispatcher.requireEventDispatchThread();
        boolean inputsEnabled = !closed && activeExecutor == null;
        boolean criteriaEnabled = inputsEnabled && !catalogLoading;
        sourceBox.setEnabled(criteriaEnabled);
        searchField.setEnabled(criteriaEnabled);
        gameVersionField.setEnabled(criteriaEnabled);
        categoryBox.setEnabled(criteriaEnabled && !categoryLoading);
        sortBox.setEnabled(criteriaEnabled);
        searchButton.setEnabled(criteriaEnabled);

        @Nullable RemoteModpackCatalogPage page = displayedPage;
        boolean pageButtonsEnabled = inputsEnabled && !catalogLoading && page != null;
        firstPageButton.setEnabled(pageButtonsEnabled && page.pageOffset() > 0);
        previousPageButton.setEnabled(pageButtonsEnabled && page.pageOffset() > 0);
        nextPageButton.setEnabled(pageButtonsEnabled && page.pageOffset() + 1 < page.totalPages());
        lastPageButton.setEnabled(pageButtonsEnabled && page.pageOffset() + 1 < page.totalPages());

        versionBox.setEnabled(inputsEnabled
                && selectedItem != null
                && !versionLoading
                && versionBox.getItemCount() > 0);
        versionSortBox.setEnabled(inputsEnabled
                && selectedItem != null
                && !versionLoading
                && !loadedVersions.isEmpty());
        instanceNameField.setEnabled(inputsEnabled);
        installButton.setEnabled(inputsEnabled
                && !catalogLoading
                && !versionLoading
                && selectedItem != null
                && versionBox.getSelectedItem() != null
                && (fixedInstanceId != null
                        || XYMLGameRepository.isValidInstanceId(instanceNameField.getText().trim())));
    }

    /// Updates visible lifecycle feedback and its accessible tooltip on the EDT.
    ///
    /// @param status non-null current feedback text, or empty text to clear it
    private void setStatus(String status) {
        EdtDispatcher.requireEventDispatchThread();
        setStatus(status, null);
    }

    /// Updates visible feedback and installs the optional primary-click action for that state.
    ///
    /// @param status non-null current feedback text, or empty to clear it
    /// @param action retry or return action, or null for ordinary feedback
    private void setStatus(String status, @Nullable Runnable action) {
        EdtDispatcher.requireEventDispatchThread();
        String text = Objects.requireNonNull(status, "status");
        statusAction = action;
        statusLabel.setCursor(action == null
                ? Cursor.getDefaultCursor()
                : Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        statusLabel.setText(text);
        statusLabel.setToolTipText(text.isBlank() ? null : text);
    }

    /// Clears status interaction when the panel releases its Swing resources.
    private void clearStatusAction() {
        statusAction = null;
        statusLabel.removeMouseListener(statusMouseListener);
        statusLabel.setCursor(Cursor.getDefaultCursor());
    }

    /// Cancels live task execution and releases all listeners and child presentation resources on the EDT.
    private void closeOnEventDispatchThread() {
        EdtDispatcher.requireEventDispatchThread();
        clearStatusAction();
        iconCache.close();
        @Nullable TaskExecutor executor = activeExecutor;
        activeExecutor = null;
        if (executor != null) {
            try {
                executor.cancel();
            } catch (RuntimeException cancellationFailure) {
                LOG.warning("Failed to cancel remote modpack installation during panel close", cancellationFailure);
            }
        }
        unsubscribe(activeCompletionSubscription);
        activeCompletionSubscription = null;
        @Nullable TaskExecutorPresentationModel presentation = activePresentation;
        activePresentation = null;
        if (presentation != null) {
            presentation.close();
        }
        searchField.getDocument().removeDocumentListener(criteriaListener);
        SwingTextFields.textEditor(gameVersionField).getDocument().removeDocumentListener(criteriaListener);
        if (fixedInstanceId == null) {
            instanceNameField.getDocument().removeDocumentListener(instanceNameListener);
        }
        choiceList.getChoiceModel().removeListDataListener(listDataListener);
        choiceList.close();
        progressHost.close();
        sourceBox.setEnabled(false);
        searchField.setEnabled(false);
        gameVersionField.setEnabled(false);
        categoryBox.setEnabled(false);
        sortBox.setEnabled(false);
        versionBox.setEnabled(false);
        versionSortBox.setEnabled(false);
        instanceNameField.setEnabled(false);
        searchButton.setEnabled(false);
        firstPageButton.setEnabled(false);
        previousPageButton.setEnabled(false);
        nextPageButton.setEnabled(false);
        lastPageButton.setEnabled(false);
        installButton.setEnabled(false);
    }

    /// Removes one optional task-listener registration.
    ///
    /// @param subscription registration to remove, or null when no registration exists
    private static void unsubscribe(@Nullable Subscription subscription) {
        if (subscription != null) {
            subscription.unsubscribe();
        }
    }

    /// Routes any criteria text mutation to local stale-result invalidation without network access.
    @NotNullByDefault
    private final class CatalogCriteriaListener implements DocumentListener {
        /// Invalidates stale results after a criteria text insertion.
        ///
        /// @param event changed document event
        @Override
        public void insertUpdate(DocumentEvent event) {
            criteriaChanged();
        }

        /// Invalidates stale results after a criteria text removal.
        ///
        /// @param event changed document event
        @Override
        public void removeUpdate(DocumentEvent event) {
            criteriaChanged();
        }

        /// Invalidates stale results after a criteria text attribute update.
        ///
        /// @param event changed document event
        @Override
        public void changedUpdate(DocumentEvent event) {
            criteriaChanged();
        }
    }

    /// Reconciles install eligibility after user-authored destination identifier changes.
    @NotNullByDefault
    private final class InstanceNameListener implements DocumentListener {
        /// Reconciles eligibility after a text insertion.
        ///
        /// @param event changed document event
        @Override
        public void insertUpdate(DocumentEvent event) {
            instanceNameChanged();
        }

        /// Reconciles eligibility after a text removal.
        ///
        /// @param event changed document event
        @Override
        public void removeUpdate(DocumentEvent event) {
            instanceNameChanged();
        }

        /// Reconciles eligibility after a text attribute update.
        ///
        /// @param event changed document event
        @Override
        public void changedUpdate(DocumentEvent event) {
            instanceNameChanged();
        }
    }

    /// Rechecks a selected sparse row when a viewport completion changes list contents.
    @NotNullByDefault
    private final class CatalogListDataListener implements ListDataListener {
        /// Rechecks selection after logical rows are inserted.
        ///
        /// @param event changed list data event
        @Override
        public void intervalAdded(ListDataEvent event) {
            selectedRowChanged();
        }

        /// Rechecks selection after logical rows are removed.
        ///
        /// @param event changed list data event
        @Override
        public void intervalRemoved(ListDataEvent event) {
            selectedRowChanged();
        }

        /// Rechecks selection after a placeholder row becomes a loaded project row.
        ///
        /// @param event changed list data event
        @Override
        public void contentsChanged(ListDataEvent event) {
            selectedRowChanged();
        }
    }

    /// Clears suggested-name ownership after a real user destination edit and refreshes install eligibility.
    private void instanceNameChanged() {
        EdtDispatcher.requireEventDispatchThread();
        if (!applyingSuggestedInstanceName) {
            suggestedInstanceName = null;
        }
        updateControls();
    }

    /// Receives one active task's terminal lifecycle event and routes it back to the EDT.
    @NotNullByDefault
    private final class InstallCompletionListener extends TaskListener {
        /// Executor whose terminal result this listener represents.
        private final TaskExecutor sourceExecutor;

        /// Creates a listener bound to exactly one installation executor.
        ///
        /// @param sourceExecutor active selected-modpack task executor
        private InstallCompletionListener(TaskExecutor sourceExecutor) {
            this.sourceExecutor = Objects.requireNonNull(sourceExecutor, "sourceExecutor");
        }

        /// Publishes terminal task status only for this listener's exact executor.
        ///
        /// @param succeeded whether the whole task graph succeeded
        /// @param executor executor reporting the terminal transition
        @Override
        public void onStop(boolean succeeded, TaskExecutor executor) {
            if (executor == sourceExecutor) {
                installCompleted(sourceExecutor, succeeded);
            }
        }
    }

    /// Renders a Core version record as concise project-version text in the selector.
    @NotNullByDefault
    private static final class RemoteModpackVersionRenderer extends DefaultListCellRenderer {
        /// Version selected as the current compatibility recommendation, or null before loading.
        private @Nullable RemoteAddon.Version recommendedVersion;

        /// Game-version search context placed first in every compatible selector row.
        private String requestedGameVersion = "";

        /// Updates recommendation and game-version context without replacing the combo-box model.
        ///
        /// @param version recommended version, or null when no project is selected
        /// @param requestedGameVersion optional exact game-version search context
        private void setSelectionContext(
                @Nullable RemoteAddon.Version version,
                String requestedGameVersion) {
            recommendedVersion = version;
            this.requestedGameVersion = Objects.requireNonNull(
                    requestedGameVersion,
                    "requestedGameVersion").trim();
        }

        /// Renders a version record while leaving empty selector values visually blank.
        ///
        /// @param list owning selector list
        /// @param value version record, or null before selection
        /// @param index row index
        /// @param isSelected whether the row is selected
        /// @param cellHasFocus whether the row has focus
        /// @return configured Swing renderer component
        @Override
        public Component getListCellRendererComponent(
                JList<?> list,
                @Nullable Object value,
                int index,
                boolean isSelected,
                boolean cellHasFocus) {
            Component component = super.getListCellRendererComponent(
                    list,
                    value,
                    index,
                    isSelected,
                    cellHasFocus);
            setIcon(null);
            setText("");
            if (value instanceof RemoteAddon.Version version) {
                setText(RemoteAddonVersionOrdering.displayText(
                        version,
                        Objects.equals(version, recommendedVersion),
                        requestedGameVersion));
                setIcon(RemoteVersionChannelPresentation.icon(version.versionType()));
            }
            return component;
        }
    }
}

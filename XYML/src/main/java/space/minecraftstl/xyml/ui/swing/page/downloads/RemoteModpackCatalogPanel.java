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
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

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

    /// Caller-owned worker executor for search and selected-project version resolution.
    private final Executor workerExecutor;

    /// Injected visible text for this catalog surface.
    private final RemoteModpackCatalogStrings strings;

    /// Retained in-memory server-page snapshot exposed through the sparse result list.
    private final RemoteModpackViewportDataSource dataSource = new RemoteModpackViewportDataSource();

    /// Result list that materializes only rows required by the measured viewport.
    private final ViewportChoiceList<RemoteModpackCatalogItem> choiceList;

    /// Progress host for one selected-version install task at a time.
    private final TaskProgressHostPanel progressHost;

    /// Source selector that refreshes category metadata without starting a project search.
    private final JComboBox<RemoteModpackCatalogSource> sourceBox = new JComboBox<>(
            RemoteModpackCatalogSource.values());

    /// Optional project-name or keyword filter editor.
    private final JTextField searchField = new JTextField();

    /// Optional exact Minecraft-version filter editor.
    private final JTextField gameVersionField = new JTextField();

    /// Provider category selector populated asynchronously after the panel becomes displayable.
    private final JComboBox<RemoteCatalogCategoryOption> categoryBox = new JComboBox<>();

    /// Core-supported server result ordering selector.
    private final JComboBox<RemoteAddonRepository.SortType> sortBox = new JComboBox<>();

    /// Exact destination instance-name editor.
    private final JTextField instanceNameField = new JTextField();

    /// Version selector populated only after a loaded project is selected.
    private final JComboBox<RemoteAddon.Version> versionBox = new JComboBox<>();

    /// Explicit first-page source query command.
    private final JButton searchButton = new JButton();

    /// Explicit previous server page command.
    private final JButton previousPageButton = new JButton();

    /// Explicit next server page command.
    private final JButton nextPageButton = new JButton();

    /// Selected-version installation command.
    private final JButton installButton = new JButton();

    /// User-visible request, loading, validation, and terminal-task feedback.
    private final JLabel statusLabel = new JLabel();

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
                progressAnimationDuration);
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
        super(new MigLayout(
                "insets 0, fill, wrap 1",
                "[grow,fill]",
                "[]8[]8[grow,fill]8[]8[]"));
        EdtDispatcher.requireEventDispatchThread();
        this.backend = Objects.requireNonNull(backend, "backend");
        this.installLauncher = Objects.requireNonNull(installLauncher, "installLauncher");
        this.workerExecutor = Objects.requireNonNull(workerExecutor, "workerExecutor");
        this.strings = Objects.requireNonNull(strings, "strings");
        TaskProgressStrings resolvedTaskProgressStrings = Objects.requireNonNull(
                taskProgressStrings, "taskProgressStrings");
        Duration resolvedProgressAnimationDuration = Objects.requireNonNull(
                progressAnimationDuration, "progressAnimationDuration");
        if (resolvedProgressAnimationDuration.isNegative()) {
            throw new IllegalArgumentException("progressAnimationDuration must not be negative");
        }
        choiceList = new ViewportChoiceList<>(dataSource, RemoteModpackCatalogItem::displayText);
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

        JPanel headingBand = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill]", "[]"));
        headingBand.setOpaque(false);
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

        JPanel searchBand = new JPanel(new MigLayout(
                "insets 0, fill",
                "[][160!]12[][grow,fill]8[110!]",
                "[40!]"));
        searchBand.setName("remoteModpackSearchBand");
        searchBand.setOpaque(false);

        JLabel sourceLabel = new JLabel(strings.sourceLabel());
        sourceLabel.setLabelFor(sourceBox);
        searchBand.add(sourceLabel);
        sourceBox.setName("remoteModpackSource");
        sourceBox.addActionListener(event -> sourceChanged());
        searchBand.add(sourceBox, "growx, h 40!");

        JLabel searchLabel = new JLabel(strings.searchLabel());
        searchLabel.setLabelFor(searchField);
        searchBand.add(searchLabel);
        searchField.setName("remoteModpackSearch");
        SwingTextFields.showClearButton(searchField);
        searchField.getDocument().addDocumentListener(criteriaListener);
        searchBand.add(searchField, "growx, h 40!");

        searchButton.setName("remoteModpackSearchAction");
        searchButton.setText(strings.searchAction());
        searchButton.addActionListener(event -> submitFirstPageSearch());
        searchBand.add(searchButton, "grow, h 40!");
        filterBand.add(searchBand, "growx");

        JPanel criteriaBand = new JPanel(new MigLayout(
                "insets 0, fill",
                "[][grow,fill]12[][grow,fill]12[][180!]",
                "[40!]"));
        criteriaBand.setName("remoteModpackCriteriaBand");
        criteriaBand.setOpaque(false);

        JLabel gameVersionLabel = new JLabel(strings.gameVersionLabel());
        gameVersionLabel.setLabelFor(gameVersionField);
        criteriaBand.add(gameVersionLabel);
        gameVersionField.setName("remoteModpackGameVersion");
        SwingTextFields.showClearButton(gameVersionField);
        gameVersionField.getDocument().addDocumentListener(criteriaListener);
        criteriaBand.add(gameVersionField, "growx, h 40!");

        RemoteCatalogFilterStrings filterStrings = strings.filterStrings();
        JLabel categoryLabel = new JLabel(filterStrings.categoryLabel());
        categoryLabel.setLabelFor(categoryBox);
        criteriaBand.add(categoryLabel);
        categoryBox.setName("remoteModpackCategory");
        categoryBox.setRenderer(new RemoteCatalogCategoryRenderer(
                () -> selectedSource() == RemoteModpackCatalogSource.MODRINTH,
                filterStrings));
        resetCategoryOptions();
        categoryBox.addActionListener(event -> categoryChanged());
        criteriaBand.add(categoryBox, "growx, h 40!");

        JLabel sortLabel = new JLabel(filterStrings.sortLabel());
        sortLabel.setLabelFor(sortBox);
        criteriaBand.add(sortLabel);
        sortBox.setName("remoteModpackSort");
        sortBox.setRenderer(new RemoteCatalogSortRenderer(filterStrings));
        resetSortOptions();
        sortBox.addActionListener(event -> sortChanged());
        criteriaBand.add(sortBox, "growx, h 40!");
        filterBand.add(criteriaBand, "growx");

        JPanel pageBand = new JPanel(new MigLayout(
                "insets 0, fill",
                "[grow,fill][120!]8[120!]",
                "[40!]"));
        pageBand.setName("remoteModpackPageBand");
        pageBand.setOpaque(false);
        pageBand.add(new JLabel(), "growx");

        previousPageButton.setName("remoteModpackPreviousPage");
        previousPageButton.setText(strings.previousPageAction());
        previousPageButton.addActionListener(event -> submitRelativePage(-1));
        pageBand.add(previousPageButton, "grow, h 40!");
        nextPageButton.setName("remoteModpackNextPage");
        nextPageButton.setText(strings.nextPageAction());
        nextPageButton.addActionListener(event -> submitRelativePage(1));
        pageBand.add(nextPageButton, "grow, h 40!");
        filterBand.add(pageBand, "growx");
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
                "insets 0, fillx, wrap 3",
                "[][grow,fill][180!]",
                "[40!]8[40!]"));
        installBand.setOpaque(false);
        JLabel versionLabel = new JLabel(strings.versionLabel());
        versionLabel.setLabelFor(versionBox);
        installBand.add(versionLabel);
        versionBox.setName("remoteModpackVersion");
        versionBox.setRenderer(new RemoteModpackVersionRenderer());
        versionBox.addActionListener(event -> updateControls());
        installBand.add(versionBox, "growx, h 40!");
        installBand.add(new JLabel(), "h 40!");

        JLabel instanceNameLabel = new JLabel(strings.instanceNameLabel());
        instanceNameLabel.setLabelFor(instanceNameField);
        installBand.add(instanceNameLabel);
        instanceNameField.setName("remoteModpackInstanceName");
        SwingTextFields.showClearButton(instanceNameField);
        instanceNameField.getDocument().addDocumentListener(instanceNameListener);
        installBand.add(instanceNameField, "growx, h 40!");
        installButton.setName("remoteModpackInstall");
        installButton.setText(strings.installAction());
        installButton.addActionListener(event -> beginInstall());
        installBand.add(installButton, "grow, h 40!");
        add(installBand, "growx");

        statusLabel.setName("remoteModpackStatus");
        add(statusLabel, "growx, h 24!");
        progressHost.setName("remoteModpackInstallProgress");
        add(progressHost, "growx");
    }

    /// Invalidates source-specific categories, clears stale results, and loads the new tree when visible.
    private void sourceChanged() {
        EdtDispatcher.requireEventDispatchThread();
        categoryRequestRevision.incrementAndGet();
        categoryLoading = false;
        loadedCategorySource = null;
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
        if (loadedCategorySource == source || !source.isAvailable()) {
            updateControls();
            return;
        }
        long requestRevision = categoryRequestRevision.incrementAndGet();
        categoryLoading = true;
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
        categoryLoading = false;
        loadedCategorySource = source;
        applyCategoryOptions(RemoteCatalogCategoryOption.flatten(categories));
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
            resetCategoryOptions();
            updateControls();
        });
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

    /// Publishes only the current provider's distinct server sort behaviors.
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

    /// Starts a user-requested adjacent page query based on the last completed query filters.
    ///
    /// @param direction negative one for previous and positive one for next
    private void submitRelativePage(int direction) {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable RemoteModpackCatalogQuery previousQuery = completedQuery;
        @Nullable RemoteModpackCatalogPage previousPage = displayedPage;
        if (previousQuery == null || previousPage == null || catalogLoading || activeExecutor != null) {
            return;
        }
        int pageOffset = previousPage.pageOffset() + direction;
        if (pageOffset < 0 || pageOffset >= previousPage.totalPages()) {
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
                gameVersionField.getText(),
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
        setStatus(page.items().isEmpty() ? strings.noResultsStatus() : "");
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
            setStatus(strings.searchFailedStatus());
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
        for (RemoteAddon.Version version : List.copyOf(Objects.requireNonNull(versions, "versions"))) {
            versionBox.addItem(version);
        }
        if (versionBox.getItemCount() > 0) {
            versionBox.setSelectedIndex(0);
            setStatus("");
        } else {
            setStatus(strings.noVersionsStatus());
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
            versionBox.removeAllItems();
            setStatus(strings.versionLoadFailedStatus());
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
        if (item == null || version == null || !XYMLGameRepository.isValidInstanceId(instanceName)) {
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
                    new GameInstanceID(instanceName)));
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
            progressHost.bind(presentation);
            executor.start();
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
        setStatus(strings.initialStatus());
        updateControls();
    }

    /// Clears list selection, version state, and project-derived instance-name ownership.
    private void clearSelectedProject() {
        EdtDispatcher.requireEventDispatchThread();
        choiceList.getList().clearSelection();
        selectedItem = null;
        versionLoading = false;
        versionBox.removeAllItems();
        suggestedInstanceName = null;
    }

    /// Suggests a destination identifier without overwriting independently authored user text.
    ///
    /// @param item selected project supplying a stable slug suggestion
    private void suggestInstanceName(RemoteModpackCatalogItem item) {
        EdtDispatcher.requireEventDispatchThread();
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
        previousPageButton.setEnabled(pageButtonsEnabled && page.pageOffset() > 0);
        nextPageButton.setEnabled(pageButtonsEnabled && page.pageOffset() + 1 < page.totalPages());

        versionBox.setEnabled(inputsEnabled && selectedItem != null && !versionLoading && versionBox.getItemCount() > 0);
        instanceNameField.setEnabled(inputsEnabled);
        installButton.setEnabled(inputsEnabled
                && !catalogLoading
                && !versionLoading
                && selectedItem != null
                && versionBox.getSelectedItem() != null
                && XYMLGameRepository.isValidInstanceId(instanceNameField.getText().trim()));
    }

    /// Updates visible lifecycle feedback and its accessible tooltip on the EDT.
    ///
    /// @param status non-null current feedback text, or empty text to clear it
    private void setStatus(String status) {
        EdtDispatcher.requireEventDispatchThread();
        String text = Objects.requireNonNull(status, "status");
        statusLabel.setText(text);
        statusLabel.setToolTipText(text.isBlank() ? null : text);
    }

    /// Cancels live task execution and releases all listeners and child presentation resources on the EDT.
    private void closeOnEventDispatchThread() {
        EdtDispatcher.requireEventDispatchThread();
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
        gameVersionField.getDocument().removeDocumentListener(criteriaListener);
        instanceNameField.getDocument().removeDocumentListener(instanceNameListener);
        choiceList.getChoiceModel().removeListDataListener(listDataListener);
        choiceList.close();
        progressHost.close();
        sourceBox.setEnabled(false);
        searchField.setEnabled(false);
        gameVersionField.setEnabled(false);
        categoryBox.setEnabled(false);
        sortBox.setEnabled(false);
        versionBox.setEnabled(false);
        instanceNameField.setEnabled(false);
        searchButton.setEnabled(false);
        previousPageButton.setEnabled(false);
        nextPageButton.setEnabled(false);
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
            if (value instanceof RemoteAddon.Version version) {
                String displayName = version.name().isBlank() ? version.version() : version.name();
                setText(displayName + " (" + version.version() + ")");
            }
            return component;
        }
    }
}

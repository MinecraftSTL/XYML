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
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Native Swing catalog for searching and acquiring remote add-ons or world archives.
///
/// Construction is fully offline. A provider search starts only after the user explicitly presses
/// Search, and each server request receives the current measured viewport row count. The result
/// list lazily renders only retained rows. Selecting a loaded project then resolves its versions on
/// the worker executor; pressing the acquisition command resolves its target and hands the exact
/// artifact to the existing task presentation pipeline rather than opening a browser.
@NotNullByDefault
public final class RemoteAddonCatalogPanel extends JPanel implements AutoCloseable {
    /// Immutable category represented by this panel and by all its acquisition requests.
    private final RemoteAddonCatalogKind kind;

    /// Blocking Core source gateway used on the worker for categories and explicit content commands.
    private final RemoteAddonCatalogBackend backend;

    /// Task factory responsible for verified artifact download and publication.
    private final RemoteAddonInstallLauncher installLauncher;

    /// Resolver that snapshots the destination immediately before acquisition.
    private final RemoteAddonInstallTargetResolver targetResolver;

    /// Caller-owned worker executor for searches and selected-project version loading.
    private final Executor workerExecutor;

    /// Explicit visible text bundle for this catalog surface.
    private final RemoteAddonCatalogStrings strings;

    /// Retained provider-page data exposed through the sparse result list without network work.
    private final RemoteAddonViewportDataSource dataSource = new RemoteAddonViewportDataSource();

    /// Bounded cache of pages that this panel user has explicitly visited; it never triggers prefetching.
    private final RemoteAddonCatalogPageCache pageCache = new RemoteAddonCatalogPageCache();

    /// Viewport-driven result list that materializes only visible retained project rows.
    private final ViewportChoiceList<RemoteAddonCatalogItem> choiceList;

    /// Presentation host for one active selected-artifact task at a time.
    private final TaskProgressHostPanel progressHost;

    /// Provider selector that refreshes category metadata without starting a project search.
    private final JComboBox<RemoteAddonCatalogSource> sourceBox = new JComboBox<>(
            RemoteAddonCatalogSource.values());

    /// Optional project keyword editor.
    private final JTextField searchField = new JTextField();

    /// Optional Minecraft-version source filter editor.
    private final JTextField gameVersionField = new JTextField();

    /// Provider category selector populated asynchronously after the panel becomes displayable.
    private final JComboBox<RemoteCatalogCategoryOption> categoryBox = new JComboBox<>();

    /// Core-supported server result ordering selector.
    private final JComboBox<RemoteAddonRepository.SortType> sortBox = new JComboBox<>();

    /// Selected project-version selector populated only after a loaded row is selected.
    private final JComboBox<RemoteAddon.Version> versionBox = new JComboBox<>();

    /// Explicit provider first-page command.
    private final JButton searchButton = new JButton();

    /// Direct first provider-page navigation command for the completed query.
    private final JButton firstPageButton = new JButton();

    /// Explicit previous provider-page command.
    private final JButton previousPageButton = new JButton();

    /// Explicit next provider-page command.
    private final JButton nextPageButton = new JButton();

    /// Direct last provider-page navigation command for the completed query.
    private final JButton lastPageButton = new JButton();

    /// Selected-version acquisition command.
    private final JButton installButton = new JButton();

    /// Current catalog, version, selected-target, and task feedback.
    private final JLabel statusLabel = new JLabel();

    /// Monotonic request identity that makes stale search callbacks harmless.
    private final AtomicLong catalogRequestRevision = new AtomicLong();

    /// Monotonic selection identity that makes stale selected-project version callbacks harmless.
    private final AtomicLong selectionRequestRevision = new AtomicLong();

    /// Monotonic category request identity that rejects stale provider trees after source changes.
    private final AtomicLong categoryRequestRevision = new AtomicLong();

    /// Criteria listener that clears stale results without issuing a network request.
    private final DocumentListener criteriaListener = new CatalogCriteriaListener();

    /// Sparse-list listener that retries a user selection after its visible placeholder materializes.
    private final ListDataListener listDataListener = new CatalogListDataListener();

    /// Last successfully completed query, or null before the first source response and after criteria change.
    private @Nullable RemoteAddonCatalogQuery completedQuery;

    /// Last successfully completed provider page, or null before a response and after criteria change.
    private @Nullable RemoteAddonCatalogPage displayedPage;

    /// Selected materialized result, or null before a selection and after criteria change.
    private @Nullable RemoteAddonCatalogItem selectedItem;

    /// Active acquisition executor, or null while the catalog accepts a future task.
    private @Nullable TaskExecutor activeExecutor;

    /// Active task presentation retained until another task replaces it or the panel closes.
    private @Nullable TaskExecutorPresentationModel activePresentation;

    /// Terminal-listener subscription for the active executor, or null while no task is live.
    private @Nullable Subscription activeCompletionSubscription;

    /// Provider whose categories currently populate the selector, or null before a successful load.
    private @Nullable RemoteAddonCatalogSource loadedCategorySource;

    /// Whether a background provider page request is currently outstanding.
    private boolean catalogLoading;

    /// Whether the selected project is waiting for background version resolution.
    private boolean versionLoading;

    /// Whether the current provider category tree is loading in the background.
    private boolean categoryLoading;

    /// Whether selector mutations are internal category publication rather than user criteria edits.
    private boolean applyingCategoryOptions;

    /// Whether sort selector mutations are internal source publication rather than user criteria edits.
    private boolean applyingSortOptions;

    /// Whether this panel has permanently rejected user commands and worker callbacks.
    private volatile boolean closed;

    /// Creates a production catalog with Core sources, category-appropriate targets, and task-backed acquisition.
    ///
    /// @param kind acquisition category represented by the panel
    /// @param strings localized visible text
    /// @param taskProgressStrings localized task lifecycle controls
    /// @param animator optional shared determinate-progress animator
    /// @param progressAnimationDuration non-negative determinate-progress animation duration
    public RemoteAddonCatalogPanel(
            RemoteAddonCatalogKind kind,
            RemoteAddonCatalogStrings strings,
            TaskProgressStrings taskProgressStrings,
            @Nullable SwingAnimator animator,
            Duration progressAnimationDuration) {
        this(
                kind,
                new CoreRemoteAddonCatalogBackend(),
                new DefaultRemoteAddonInstallLauncher(),
                defaultTargetResolver(kind),
                Schedulers.io(),
                strings,
                taskProgressStrings,
                animator,
                progressAnimationDuration);
    }

    /// Selects the production destination policy without performing filesystem or network work.
    ///
    /// @param kind acquisition category represented by the panel
    /// @return save-as world policy or selected-instance managed-directory policy
    private static RemoteAddonInstallTargetResolver defaultTargetResolver(RemoteAddonCatalogKind kind) {
        return Objects.requireNonNull(kind, "kind") == RemoteAddonCatalogKind.WORLD
                ? new SwingRemoteWorldSaveTargetResolver()
                : new LauncherRemoteAddonInstallTargetResolver();
    }

    /// Creates a production catalog with an explicit destination policy.
    ///
    /// This variant is used by the world catalog so its acquisition command opens a save-as chooser
    /// only after a project version is selected. Construction remains fully offline.
    ///
    /// @param kind acquisition category represented by the panel
    /// @param targetResolver destination policy for the selected artifact
    /// @param strings localized visible text
    /// @param taskProgressStrings localized task lifecycle controls
    /// @param animator optional shared determinate-progress animator
    /// @param progressAnimationDuration non-negative determinate-progress animation duration
    public RemoteAddonCatalogPanel(
            RemoteAddonCatalogKind kind,
            RemoteAddonInstallTargetResolver targetResolver,
            RemoteAddonCatalogStrings strings,
            TaskProgressStrings taskProgressStrings,
            @Nullable SwingAnimator animator,
            Duration progressAnimationDuration) {
        this(
                kind,
                new CoreRemoteAddonCatalogBackend(),
                new DefaultRemoteAddonInstallLauncher(),
                targetResolver,
                Schedulers.io(),
                strings,
                taskProgressStrings,
                animator,
                progressAnimationDuration);
    }

    /// Creates a catalog with explicit source, selected-target, task, and executor boundaries for focused tests.
    ///
    /// The caller retains ownership of the supplied worker executor. The panel releases only its
    /// sparse viewport and task-presentation resources during closure.
    ///
    /// @param kind acquisition category represented by the panel
    /// @param backend blocking source gateway invoked only after explicit commands
    /// @param installLauncher selected-artifact task factory
    /// @param targetResolver managed-directory or save-as target resolver
    /// @param workerExecutor background executor for provider calls
    /// @param strings visible catalog text
    /// @param taskProgressStrings localized task lifecycle controls
    /// @param animator optional shared determinate-progress animator
    /// @param progressAnimationDuration non-negative determinate-progress animation duration
    RemoteAddonCatalogPanel(
            RemoteAddonCatalogKind kind,
            RemoteAddonCatalogBackend backend,
            RemoteAddonInstallLauncher installLauncher,
            RemoteAddonInstallTargetResolver targetResolver,
            Executor workerExecutor,
            RemoteAddonCatalogStrings strings,
            TaskProgressStrings taskProgressStrings,
            @Nullable SwingAnimator animator,
            Duration progressAnimationDuration) {
        super(new MigLayout(
                "insets 0, fill, wrap 1",
                "[grow,fill]",
                "[]8[]8[grow,fill]8[]8[]"));
        EdtDispatcher.requireEventDispatchThread();
        this.kind = Objects.requireNonNull(kind, "kind");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.installLauncher = Objects.requireNonNull(installLauncher, "installLauncher");
        this.targetResolver = Objects.requireNonNull(targetResolver, "targetResolver");
        this.workerExecutor = Objects.requireNonNull(workerExecutor, "workerExecutor");
        this.strings = Objects.requireNonNull(strings, "strings");
        TaskProgressStrings resolvedTaskProgressStrings = Objects.requireNonNull(
                taskProgressStrings,
                "taskProgressStrings");
        Duration resolvedProgressAnimationDuration = Objects.requireNonNull(
                progressAnimationDuration,
                "progressAnimationDuration");
        if (resolvedProgressAnimationDuration.isNegative()) {
            throw new IllegalArgumentException("progressAnimationDuration must not be negative");
        }
        choiceList = new ViewportChoiceList<>(dataSource, RemoteAddonCatalogItem::displayText);
        progressHost = new TaskProgressHostPanel(
                resolvedTaskProgressStrings,
                animator,
                resolvedProgressAnimationDuration);
        configureComponents();
        updateControls();
        setStatus(strings.initialStatus());
    }

    /// Returns the owned viewport-driven result list for focused integration and tests.
    ///
    /// @return sparse retained-provider result list
    public ViewportChoiceList<RemoteAddonCatalogItem> choiceList() {
        return choiceList;
    }

    /// Starts category discovery only when this panel receives a peer while visible.
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

    /// Rejects future callbacks, cancels an active task, and releases owned presentation resources.
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

    /// Builds the static Swing shell and listener wiring without querying any source.
    private void configureComponents() {
        setName("remoteAddonCatalog" + kind.name());
        setOpaque(false);

        sourceBox.removeAllItems();
        for (RemoteAddonCatalogSource source : RemoteAddonCatalogSource.values()) {
            if (source.supports(kind)) {
                sourceBox.addItem(source);
            }
        }
        if (sourceBox.getItemCount() == 0) {
            throw new IllegalArgumentException("No remote source supports " + kind.name());
        }

        JPanel headingBand = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill]", "[]"));
        headingBand.setOpaque(false);
        JLabel heading = new JLabel(strings.pageTitle());
        heading.setName("remoteAddonCatalogTitle");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 28.0F));
        headingBand.add(heading, "growx");
        add(headingBand, "growx");

        JPanel filterBand = new JPanel(new MigLayout(
                "insets 0, fillx, wrap 1",
                "[grow,fill]",
                "[40!]8[40!]8[40!]"));
        filterBand.setName("remoteAddonFilterBand");
        filterBand.setOpaque(false);
        JPanel searchBand = new JPanel(new MigLayout(
                "insets 0, fill",
                "[][150!]12[][grow,fill]8[110!]",
                "[40!]"));
        searchBand.setName("remoteAddonSearchBand");
        searchBand.setOpaque(false);

        JLabel sourceLabel = new JLabel(strings.sourceLabel());
        sourceLabel.setLabelFor(sourceBox);
        searchBand.add(sourceLabel);
        sourceBox.setName("remoteAddonSource");
        sourceBox.addActionListener(event -> sourceChanged());
        searchBand.add(sourceBox, "growx, h 40!");

        JLabel searchLabel = new JLabel(strings.searchLabel());
        searchLabel.setLabelFor(searchField);
        searchBand.add(searchLabel);
        searchField.setName("remoteAddonSearch");
        SwingTextFields.showClearButton(searchField);
        searchField.getDocument().addDocumentListener(criteriaListener);
        searchBand.add(searchField, "growx, h 40!");

        searchButton.setName("remoteAddonSearchAction");
        searchButton.setText(strings.searchAction());
        searchButton.addActionListener(event -> submitFirstPageSearch());
        searchBand.add(searchButton, "grow, h 40!");
        filterBand.add(searchBand, "growx");

        JPanel criteriaBand = new JPanel(new MigLayout(
                "insets 0, fill",
                "[][grow,fill]12[][grow,fill]12[][180!]",
                "[40!]"));
        criteriaBand.setName("remoteAddonCriteriaBand");
        criteriaBand.setOpaque(false);

        JLabel gameVersionLabel = new JLabel(strings.gameVersionLabel());
        gameVersionLabel.setLabelFor(gameVersionField);
        criteriaBand.add(gameVersionLabel);
        gameVersionField.setName("remoteAddonGameVersion");
        SwingTextFields.showClearButton(gameVersionField);
        gameVersionField.getDocument().addDocumentListener(criteriaListener);
        criteriaBand.add(gameVersionField, "growx, h 40!");

        RemoteCatalogFilterStrings filterStrings = strings.filterStrings();
        JLabel categoryLabel = new JLabel(filterStrings.categoryLabel());
        categoryLabel.setLabelFor(categoryBox);
        criteriaBand.add(categoryLabel);
        categoryBox.setName("remoteAddonCategory");
        categoryBox.setRenderer(new RemoteCatalogCategoryRenderer(
                () -> selectedSource() == RemoteAddonCatalogSource.MODRINTH,
                filterStrings));
        resetCategoryOptions();
        categoryBox.addActionListener(event -> categoryChanged());
        criteriaBand.add(categoryBox, "growx, h 40!");

        JLabel sortLabel = new JLabel(filterStrings.sortLabel());
        sortLabel.setLabelFor(sortBox);
        criteriaBand.add(sortLabel);
        sortBox.setName("remoteAddonSort");
        sortBox.setRenderer(new RemoteCatalogSortRenderer(filterStrings));
        resetSortOptions();
        sortBox.addActionListener(event -> sortChanged());
        criteriaBand.add(sortBox, "growx, h 40!");
        filterBand.add(criteriaBand, "growx");

        JPanel pageBand = new JPanel(new MigLayout(
                "insets 0, fill",
                "[grow,fill][120!]8[120!]8[120!]8[120!]",
                "[40!]"));
        pageBand.setName("remoteAddonPageBand");
        pageBand.setOpaque(false);
        pageBand.add(new JLabel(), "growx");

        firstPageButton.setName("remoteAddonFirstPage");
        firstPageButton.setText(i18n("search.first_page"));
        firstPageButton.addActionListener(event -> submitBoundaryPage(false));
        pageBand.add(firstPageButton, "grow, h 40!");
        previousPageButton.setName("remoteAddonPreviousPage");
        previousPageButton.setText(strings.previousPageAction());
        previousPageButton.addActionListener(event -> submitRelativePage(-1));
        pageBand.add(previousPageButton, "grow, h 40!");
        nextPageButton.setName("remoteAddonNextPage");
        nextPageButton.setText(strings.nextPageAction());
        nextPageButton.addActionListener(event -> submitRelativePage(1));
        pageBand.add(nextPageButton, "grow, h 40!");
        lastPageButton.setName("remoteAddonLastPage");
        lastPageButton.setText(i18n("search.last_page"));
        lastPageButton.addActionListener(event -> submitBoundaryPage(true));
        pageBand.add(lastPageButton, "grow, h 40!");
        filterBand.add(pageBand, "growx");
        add(filterBand, "growx");

        choiceList.setName("remoteAddonResults");
        choiceList.setOpaque(false);
        choiceList.getViewport().setOpaque(false);
        JList<ChoiceListEntry<RemoteAddonCatalogItem>> resultList = choiceList.getList();
        resultList.setName("remoteAddonResultsView");
        resultList.setOpaque(false);
        resultList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                selectedRowChanged();
            }
        });
        choiceList.getChoiceModel().addListDataListener(listDataListener);
        add(choiceList, "grow");

        JPanel installBand = new JPanel(new MigLayout(
                "insets 0, fillx",
                "[][grow,fill][220!]",
                "[40!]"));
        installBand.setOpaque(false);
        JLabel versionLabel = new JLabel(strings.versionLabel());
        versionLabel.setLabelFor(versionBox);
        installBand.add(versionLabel);
        versionBox.setName("remoteAddonVersion");
        versionBox.setRenderer(new RemoteAddonVersionRenderer());
        versionBox.addActionListener(event -> updateControls());
        installBand.add(versionBox, "growx, h 40!");
        installButton.setName("remoteAddonInstall");
        installButton.setText(strings.installAction());
        installButton.addActionListener(event -> beginInstall());
        installBand.add(installButton, "grow, h 40!");
        add(installBand, "growx");

        statusLabel.setName("remoteAddonStatus");
        add(statusLabel, "growx, h 24!");
        progressHost.setName("remoteAddonInstallProgress");
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
        RemoteAddonCatalogSource source = selectedSource();
        if (loadedCategorySource == source || !source.isAvailable() || !source.supports(kind)) {
            updateControls();
            return;
        }
        long requestRevision = categoryRequestRevision.incrementAndGet();
        categoryLoading = true;
        updateControls();
        try {
            workerExecutor.execute(() -> loadCategories(source, requestRevision));
        } catch (RuntimeException schedulingFailure) {
            LOG.warning("Failed to schedule remote add-on category loading", schedulingFailure);
            applyCategoryFailure(source, requestRevision);
        }
    }

    /// Loads one provider category tree away from the EDT.
    ///
    /// @param source selected provider captured before worker scheduling
    /// @param requestRevision category request identity
    private void loadCategories(RemoteAddonCatalogSource source, long requestRevision) {
        try {
            @Unmodifiable List<RemoteAddonRepository.Category> categories = backend.loadCategories(kind, source);
            SwingUiDispatcher.INSTANCE.dispatchOrRun(
                    () -> applyCategories(source, categories, requestRevision));
        } catch (IOException | RuntimeException failure) {
            LOG.warning("Failed to load remote add-on categories", failure);
            applyCategoryFailure(source, requestRevision);
        }
    }

    /// Publishes a provider category tree only while its source and request remain current.
    ///
    /// @param source provider that produced the categories
    /// @param categories immutable provider category roots
    /// @param requestRevision category request identity
    private void applyCategories(
            RemoteAddonCatalogSource source,
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
    private void applyCategoryFailure(RemoteAddonCatalogSource source, long requestRevision) {
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

    /// Starts an explicit user-requested first provider page query.
    private void submitFirstPageSearch() {
        EdtDispatcher.requireEventDispatchThread();
        submitSearch(0);
    }

    /// Starts direct first- or last-page navigation for the last completed query.
    ///
    /// @param lastPage true to request the last page, or false to request the first page
    private void submitBoundaryPage(boolean lastPage) {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable RemoteAddonCatalogPage page = displayedPage;
        if (page == null) {
            return;
        }
        submitCompletedQueryPage(lastPage ? page.totalPages() - 1 : 0);
    }

    /// Starts an explicit adjacent provider page query using the last completed search criteria.
    ///
    /// @param direction negative one for previous and positive one for next
    private void submitRelativePage(int direction) {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable RemoteAddonCatalogPage previousPage = displayedPage;
        if (previousPage == null) {
            return;
        }
        submitCompletedQueryPage(previousPage.pageOffset() + direction);
    }

    /// Validates and starts an exact page request against the last completed query criteria.
    ///
    /// @param pageOffset zero-based provider page to request
    private void submitCompletedQueryPage(int pageOffset) {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable RemoteAddonCatalogQuery previousQuery = completedQuery;
        @Nullable RemoteAddonCatalogPage previousPage = displayedPage;
        if (previousQuery == null || previousPage == null || catalogLoading || activeExecutor != null
                || pageOffset < 0 || pageOffset >= previousPage.totalPages()) {
            return;
        }
        submitSearch(pageOffset);
    }

    /// Measures current viewport geometry and schedules one provider search only after a user command.
    ///
    /// @param pageOffset zero-based provider page requested by the user
    private void submitSearch(int pageOffset) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || catalogLoading || activeExecutor != null) {
            return;
        }
        RemoteAddonCatalogSource source = selectedSource();
        if (!source.isAvailable() || !source.supports(kind)) {
            setStatus(strings.sourceUnavailableStatus());
            return;
        }
        int pageSize = measuredPageSize();
        if (pageSize == 0) {
            setStatus(strings.viewportUnavailableStatus());
            return;
        }

        RemoteAddonCatalogQuery query = new RemoteAddonCatalogQuery(
                kind,
                source,
                searchField.getText(),
                gameVersionField.getText(),
                selectedCategory(),
                selectedSortType(),
                pageOffset,
                pageSize);
        long requestRevision = catalogRequestRevision.incrementAndGet();
        selectionRequestRevision.incrementAndGet();
        completedQuery = null;
        displayedPage = null;
        clearSelectedProject();
        dataSource.replaceItems(List.of());
        choiceList.reloadData();
        @Nullable RemoteAddonCatalogPage cachedPage = pageCache.get(query).orElse(null);
        if (cachedPage != null) {
            catalogLoading = false;
            applyCatalogPage(query, cachedPage, requestRevision);
            return;
        }
        catalogLoading = true;
        setStatus(strings.loadingStatus());
        updateControls();
        try {
            workerExecutor.execute(() -> loadCatalogPage(query, requestRevision));
        } catch (RuntimeException schedulingFailure) {
            LOG.warning("Failed to schedule a remote add-on catalog request", schedulingFailure);
            applyCatalogFailure(requestRevision);
        }
    }

    /// Runs one Core provider query away from the EDT and returns its result to the current revision.
    ///
    /// @param query explicit user-requested provider query
    /// @param requestRevision revision captured for this worker invocation
    private void loadCatalogPage(RemoteAddonCatalogQuery query, long requestRevision) {
        try {
            RemoteAddonCatalogPage page = backend.search(query);
            SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> applyCatalogPage(query, page, requestRevision));
        } catch (IOException | RuntimeException failure) {
            LOG.warning("Failed to search remote add-on catalog", failure);
            applyCatalogFailure(requestRevision);
        }
    }

    /// Publishes one current provider page and invalidates only its old retained sparse rows.
    ///
    /// @param query query that produced the page
    /// @param page immutable provider response
    /// @param requestRevision request identity captured before work started
    private void applyCatalogPage(
            RemoteAddonCatalogQuery query,
            RemoteAddonCatalogPage page,
            long requestRevision) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || catalogRequestRevision.get() != requestRevision) {
            return;
        }
        catalogLoading = false;
        completedQuery = Objects.requireNonNull(query, "query");
        displayedPage = Objects.requireNonNull(page, "page");
        pageCache.put(query, page);
        dataSource.replaceItems(page.items());
        choiceList.reloadData();
        setStatus(page.items().isEmpty() ? strings.noResultsStatus() : "");
        updateControls();
    }

    /// Restores controls after a current provider request fails.
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

    /// Resolves versions only for a selected materialized result row on the worker executor.
    private void selectedRowChanged() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || catalogLoading || activeExecutor != null) {
            return;
        }
        @Nullable RemoteAddonCatalogItem item = choiceList.getSelectedValue();
        if (item == null || item == selectedItem || item.kind() != kind) {
            return;
        }
        long requestRevision = selectionRequestRevision.incrementAndGet();
        selectedItem = item;
        versionLoading = true;
        versionBox.removeAllItems();
        setStatus(strings.loadingVersionsStatus());
        updateControls();
        try {
            workerExecutor.execute(() -> loadSelectedVersions(item, requestRevision));
        } catch (RuntimeException schedulingFailure) {
            LOG.warning("Failed to schedule remote add-on version loading", schedulingFailure);
            applyVersionFailure(item, requestRevision);
        }
    }

    /// Loads a selected project's available versions away from the EDT.
    ///
    /// @param item selected materialized project row
    /// @param requestRevision selection identity captured before worker scheduling
    private void loadSelectedVersions(RemoteAddonCatalogItem item, long requestRevision) {
        try {
            List<RemoteAddon.Version> versions = backend.loadVersions(item);
            SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> applyVersions(item, versions, requestRevision));
        } catch (IOException | RuntimeException failure) {
            LOG.warning("Failed to load remote add-on versions", failure);
            applyVersionFailure(item, requestRevision);
        }
    }

    /// Publishes selected-project versions only if the row remains selected at the same revision.
    ///
    /// @param item selected result represented by the loaded versions
    /// @param versions provider-order installable versions
    /// @param requestRevision selection identity captured before worker scheduling
    private void applyVersions(
            RemoteAddonCatalogItem item,
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

    /// Publishes selected-project version failure only while that selection remains current.
    ///
    /// @param item selected project whose version lookup failed
    /// @param requestRevision selection identity captured before worker scheduling
    private void applyVersionFailure(RemoteAddonCatalogItem item, long requestRevision) {
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

    /// Creates and starts one selected-version acquisition task against a freshly resolved target.
    private void beginInstall() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || !installButton.isEnabled()) {
            return;
        }
        @Nullable RemoteAddonCatalogItem item = selectedItem;
        @Nullable RemoteAddon.Version version = (RemoteAddon.Version) versionBox.getSelectedItem();
        if (item == null || version == null) {
            updateControls();
            return;
        }
        final @Nullable RemoteAddonInstallTarget target;
        try {
            target = resolveInstallTarget(item, version);
        } catch (RuntimeException targetFailure) {
            LOG.warning("Failed to resolve a remote acquisition target", targetFailure);
            setStatus(strings.installFailedStatus());
            updateControls();
            return;
        }
        if (target == null) {
            setStatus(strings.selectInstanceStatus());
            updateControls();
            return;
        }

        releaseCompletedPresentation();
        final Task<?> task;
        try {
            setStatus(strings.preparingInstallStatus());
            task = installLauncher.createInstallTask(new RemoteAddonInstallRequest(item, version, target));
        } catch (IOException | RuntimeException preparationFailure) {
            LOG.warning("Failed to prepare a selected remote add-on installation", preparationFailure);
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
            LOG.warning("Failed to start selected remote add-on installation", startFailure);
            cleanupFailedTaskStart(presentation, completionSubscription);
            setStatus(strings.installFailedStatus());
            updateControls();
        }
    }

    /// Publishes a terminal task outcome and reopens catalog controls.
    ///
    /// @param executor task executor that reached a terminal state
    /// @param succeeded whether the full task graph succeeded
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

    /// Clears a terminal task presentation before constructing a later acquisition task.
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

    /// Releases task resources when executor startup fails before a terminal callback can arrive.
    ///
    /// @param presentation presentation created for the failed executor
    /// @param completionSubscription terminal listener created for the failed executor
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

    /// Invalidates stale source and selection state after local criteria edits without starting a query.
    private void criteriaChanged() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || catalogLoading || activeExecutor != null) {
            return;
        }
        catalogRequestRevision.incrementAndGet();
        selectionRequestRevision.incrementAndGet();
        completedQuery = null;
        displayedPage = null;
        pageCache.clear();
        clearSelectedProject();
        dataSource.replaceItems(List.of());
        choiceList.reloadData();
        setStatus(strings.initialStatus());
        updateControls();
    }

    /// Clears selected sparse-row state and any provider versions belonging to the old selection.
    private void clearSelectedProject() {
        EdtDispatcher.requireEventDispatchThread();
        choiceList.getList().clearSelection();
        selectedItem = null;
        versionLoading = false;
        versionBox.removeAllItems();
    }

    /// Resolves the current selected target only after an explicit acquisition command.
    ///
    /// @param item selected remote project
    /// @param version exact selected version
    /// @return current selected target, or null when no usable instance is selected
    private @Nullable RemoteAddonInstallTarget resolveInstallTarget(
            RemoteAddonCatalogItem item,
            RemoteAddon.Version version) {
        Optional<RemoteAddonInstallTarget> target = Objects.requireNonNull(
                targetResolver.resolveSelection(kind, item, version, this),
                "targetResolver returned null selection optional");
        return target.orElse(null);
    }

    /// Returns the selected provider while retaining the combo-box non-null invariant.
    ///
    /// @return selected provider
    private RemoteAddonCatalogSource selectedSource() {
        return Objects.requireNonNull(
                (RemoteAddonCatalogSource) sourceBox.getSelectedItem(),
                "remote add-on source selection");
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
                "remote add-on sort selection");
    }

    /// Returns the current server page size from real visible result-list geometry.
    ///
    /// @return positive measured visible row count, or zero before layout establishes a viewport
    private int measuredPageSize() {
        Dimension extent = choiceList.getViewport().getExtentSize();
        int rowHeight = choiceList.getList().getFixedCellHeight();
        if (extent.height <= 0 || rowHeight <= 0) {
            return 0;
        }
        return Math.max(1, Math.floorDiv(extent.height + rowHeight - 1, rowHeight));
    }

    /// Reconciles all command availability from catalog, version, target, task, and lifecycle state.
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

        @Nullable RemoteAddonCatalogPage page = displayedPage;
        boolean pageButtonsEnabled = inputsEnabled && !catalogLoading && page != null;
        firstPageButton.setEnabled(pageButtonsEnabled && page.pageOffset() > 0);
        previousPageButton.setEnabled(pageButtonsEnabled && page.pageOffset() > 0);
        nextPageButton.setEnabled(pageButtonsEnabled && page.pageOffset() + 1 < page.totalPages());
        lastPageButton.setEnabled(pageButtonsEnabled && page.pageOffset() + 1 < page.totalPages());

        versionBox.setEnabled(inputsEnabled && selectedItem != null && !versionLoading && versionBox.getItemCount() > 0);
        installButton.setEnabled(inputsEnabled
                && !catalogLoading
                && !versionLoading
                && selectedItem != null
                && versionBox.getSelectedItem() != null
                && isTargetSelectionAvailable());
    }

    /// Checks whether the current destination policy can accept an explicit acquisition command.
    ///
    /// @return true when target selection can proceed without opening an interactive chooser now
    private boolean isTargetSelectionAvailable() {
        try {
            return targetResolver.isSelectionAvailable(kind);
        } catch (RuntimeException targetFailure) {
            LOG.warning("Failed to inspect remote acquisition target availability", targetFailure);
            return false;
        }
    }

    /// Applies current non-null feedback text with a matching accessibility tooltip.
    ///
    /// @param status non-null current feedback text, or empty to clear it
    private void setStatus(String status) {
        EdtDispatcher.requireEventDispatchThread();
        String text = Objects.requireNonNull(status, "status");
        statusLabel.setText(text);
        statusLabel.setToolTipText(text.isBlank() ? null : text);
    }

    /// Cancels live task state and releases all owned listeners and child presentation resources on the EDT.
    private void closeOnEventDispatchThread() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable TaskExecutor executor = activeExecutor;
        activeExecutor = null;
        if (executor != null) {
            try {
                executor.cancel();
            } catch (RuntimeException cancellationFailure) {
                LOG.warning("Failed to cancel remote add-on installation during panel close", cancellationFailure);
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
        choiceList.getChoiceModel().removeListDataListener(listDataListener);
        choiceList.close();
        pageCache.clear();
        progressHost.close();
        sourceBox.setEnabled(false);
        searchField.setEnabled(false);
        gameVersionField.setEnabled(false);
        categoryBox.setEnabled(false);
        sortBox.setEnabled(false);
        versionBox.setEnabled(false);
        searchButton.setEnabled(false);
        firstPageButton.setEnabled(false);
        previousPageButton.setEnabled(false);
        nextPageButton.setEnabled(false);
        lastPageButton.setEnabled(false);
        installButton.setEnabled(false);
    }

    /// Removes one optional task terminal-listener registration.
    ///
    /// @param subscription registration to remove, or null while no task owns one
    private static void unsubscribe(@Nullable Subscription subscription) {
        if (subscription != null) {
            subscription.unsubscribe();
        }
    }

    /// Invalidates stale retained results after any local criteria text mutation.
    @NotNullByDefault
    private final class CatalogCriteriaListener implements DocumentListener {
        /// Clears stale state after text insertion without triggering a provider request.
        ///
        /// @param event changed document event
        @Override
        public void insertUpdate(DocumentEvent event) {
            criteriaChanged();
        }

        /// Clears stale state after text removal without triggering a provider request.
        ///
        /// @param event changed document event
        @Override
        public void removeUpdate(DocumentEvent event) {
            criteriaChanged();
        }

        /// Clears stale state after attribute mutation without triggering a provider request.
        ///
        /// @param event changed document event
        @Override
        public void changedUpdate(DocumentEvent event) {
            criteriaChanged();
        }
    }

    /// Rechecks a sparse selected row when its visible placeholder changes into a loaded project value.
    @NotNullByDefault
    private final class CatalogListDataListener implements ListDataListener {
        /// Rechecks a selected row after logical row insertion.
        ///
        /// @param event changed list-data event
        @Override
        public void intervalAdded(ListDataEvent event) {
            selectedRowChanged();
        }

        /// Rechecks a selected row after logical row removal.
        ///
        /// @param event changed list-data event
        @Override
        public void intervalRemoved(ListDataEvent event) {
            selectedRowChanged();
        }

        /// Rechecks a selected row after a placeholder resolves into an actual project value.
        ///
        /// @param event changed list-data event
        @Override
        public void contentsChanged(ListDataEvent event) {
            selectedRowChanged();
        }
    }

    /// Routes one exact task executor's terminal state back to the Swing event dispatch thread.
    @NotNullByDefault
    private final class InstallCompletionListener extends TaskListener {
        /// Executor represented by this listener.
        private final TaskExecutor sourceExecutor;

        /// Creates a terminal listener for exactly one active task executor.
        ///
        /// @param sourceExecutor task executor whose lifecycle should update this panel
        private InstallCompletionListener(TaskExecutor sourceExecutor) {
            this.sourceExecutor = Objects.requireNonNull(sourceExecutor, "sourceExecutor");
        }

        /// Publishes terminal status only for the exact retained executor.
        ///
        /// @param succeeded whether the full task graph completed successfully
        /// @param executor executor reporting the terminal transition
        @Override
        public void onStop(boolean succeeded, TaskExecutor executor) {
            if (executor == sourceExecutor) {
                installCompleted(sourceExecutor, succeeded);
            }
        }
    }

    /// Renders selected Core versions as concise name and identifier text.
    @NotNullByDefault
    private static final class RemoteAddonVersionRenderer extends DefaultListCellRenderer {
        /// Renders one provider version while preserving an empty selector display before selection.
        ///
        /// @param list owning selector list
        /// @param value version record, or null before selection
        /// @param index row index
        /// @param isSelected whether the row is selected
        /// @param cellHasFocus whether the row owns focus
        /// @return configured renderer component
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

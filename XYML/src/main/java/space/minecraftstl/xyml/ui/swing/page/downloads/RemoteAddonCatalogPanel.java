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
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskExecutor;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.SwingTextFields;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;
import space.minecraftstl.xyml.ui.swing.choice.ChoiceListEntry;
import space.minecraftstl.xyml.ui.swing.choice.RichChoiceListCellRenderer;
import space.minecraftstl.xyml.ui.swing.choice.ViewportChoiceList;
import space.minecraftstl.xyml.ui.swing.page.instances.InstancesModel;
import space.minecraftstl.xyml.ui.swing.task.TaskLaunchController;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
    /// Bottom spacing after the final add-on row, matching the upstream download-list padding.
    private static final int RESULT_LIST_BOTTOM_PADDING = 9;

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

    /// Lazy provider-icon cache sharing the catalog worker boundary.
    private final RemoteAddonIconCache iconCache;

    /// Explicit visible text bundle for this catalog surface.
    private final RemoteAddonCatalogStrings strings;

    /// Retained provider-page data exposed through the sparse result list without network work.
    private final RemoteAddonViewportDataSource dataSource = new RemoteAddonViewportDataSource();

    /// Bounded cache of pages that this panel user has explicitly visited; it never triggers prefetching.
    private final RemoteAddonCatalogPageCache pageCache = new RemoteAddonCatalogPageCache();

    /// Viewport-driven result list that materializes only visible retained project rows.
    private final ViewportChoiceList<RemoteAddonCatalogItem> choiceList;

    /// Shared confirmed-task submission and navigation controller.
    private TaskLaunchController taskLaunchController = new TaskLaunchController(() -> { });

    /// Provider selector that refreshes category metadata without starting a project search.
    private final JComboBox<RemoteAddonCatalogSource> sourceBox = new JComboBox<>(
            RemoteAddonCatalogSource.values());

    /// Optional project keyword editor.
    private final JTextField searchField = new JTextField();

    /// Editable Minecraft-version source filter with common launcher versions as suggestions.
    private final JComboBox<String> gameVersionField = new JComboBox<>();

    /// Provider category selector populated asynchronously after the panel becomes displayable.
    private final JComboBox<RemoteCatalogCategoryOption> categoryBox = new JComboBox<>();

    /// Core-supported server result ordering selector.
    private final JComboBox<RemoteAddonRepository.SortType> sortBox = new JComboBox<>();

    /// Selected project-version selector populated only after a loaded row is selected.
    private final JComboBox<RemoteAddon.Version> versionBox = new JComboBox<>();

    /// Explicit ordering selector for the selected project's installable versions.
    private final JComboBox<RemoteAddonVersionSortMode> versionSortBox = new JComboBox<>(
            RemoteAddonVersionSortMode.values());

    /// Renderer that keeps the recommended version visible while the selector is open or closed.
    private final RemoteAddonVersionRenderer versionRenderer = new RemoteAddonVersionRenderer();

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

    /// On-demand changelog and exact provider-page command.
    private final JButton changelogButton = new JButton();

    /// Selected-project summary shown above the installation controls.
    private final JLabel projectSummaryLabel = new JLabel();

    /// Opens the selected project's public upstream page.
    private final JButton upstreamButton = new JButton();

    /// Label introducing downloadable prerequisite mods for the selected version.
    private final JLabel prerequisitesLabel = new JLabel();

    /// Wrapped prerequisite search commands for the selected version.
    private final JPanel prerequisiteButtons = new JPanel();

    /// Current catalog, version, selected-target, and task feedback.
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

    /// Viewport listener that retries a deferred search after Swing publishes a new extent.
    private final ChangeListener viewportListener = event -> schedulePendingSearchCheck();

    /// Last successfully completed query, or null before the first source response and after criteria change.
    private @Nullable RemoteAddonCatalogQuery completedQuery;

    /// Last successfully completed provider page, or null before a response and after criteria change.
    private @Nullable RemoteAddonCatalogPage displayedPage;

    /// Selected materialized result, or null before a selection and after criteria change.
    private @Nullable RemoteAddonCatalogItem selectedItem;

    /// Active acquisition executor, or null while the catalog accepts a future task.
    private @Nullable TaskExecutor activeExecutor;

    /// Terminal-listener subscription for the active executor, or null while no task is live.
    private @Nullable Subscription activeCompletionSubscription;

    /// Provider whose categories currently populate the selector, or null before a successful load.
    private @Nullable RemoteAddonCatalogSource loadedCategorySource;

    /// Whether category discovery for the selected provider most recently failed.
    private boolean categoryLoadFailed;

    /// Whether a background provider page request is currently outstanding.
    private boolean catalogLoading;

    /// Whether the selected project is waiting for background version resolution.
    private boolean versionLoading;

    /// Whether a changelog request is currently outstanding.
    private boolean changelogLoading;

    /// Whether the current provider category tree is loading in the background.
    private boolean categoryLoading;

    /// Whether selector mutations are internal category publication rather than user criteria edits.
    private boolean applyingCategoryOptions;

    /// Whether sort selector mutations are internal source publication rather than user criteria edits.
    private boolean applyingSortOptions;

    /// Provider versions retained for local reordering after the user changes version sort mode.
    private @Unmodifiable List<RemoteAddon.Version> loadedVersions = List.of();

    /// Stable recommendation retained independently from the selected browsing order.
    private @Nullable RemoteAddon.Version recommendedVersion;

    /// Suppresses version-combo callbacks while a new local order is being published.
    private boolean applyingVersionSort;

    /// Missing-dependency query waiting for the first layout to establish a result-list viewport, or null when none is
    /// pending.
    private @Nullable String pendingSearchText;

    /// Whether one deferred pending-search check is already queued on the EDT.
    private boolean pendingSearchCheckQueued;

    /// Whether this panel has permanently rejected user commands and worker callbacks.
    private volatile boolean closed;

    /// Optional local target-instance selector used by direct-install categories.
    private final @Nullable RemoteAddonTargetInstanceSelector targetInstanceSelector;

    /// Starts the local target selector when this catalog actually becomes visible.
    private final HierarchyListener showingListener;

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
        this(kind, strings, taskProgressStrings, animator, progressAnimationDuration, null);
    }

    /// Creates a production direct-install catalog backed by an explicit installed-instance source.
    public RemoteAddonCatalogPanel(
            RemoteAddonCatalogKind kind,
            RemoteAddonCatalogStrings strings,
            TaskProgressStrings taskProgressStrings,
            @Nullable SwingAnimator animator,
            Duration progressAnimationDuration,
            @Nullable InstancesModel instancesModel) {
        this(
                kind,
                new CoreRemoteAddonCatalogBackend(),
                new DefaultRemoteAddonInstallLauncher(),
                defaultTargetResolver(kind),
                Schedulers.io(),
                strings,
                taskProgressStrings,
                animator,
                progressAnimationDuration,
                instancesModel);
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

    /// Installs the shared task navigation controller used by production container wiring.
    ///
    /// @param controller shared confirmed-task submission controller
    void setTaskLaunchController(TaskLaunchController controller) {
        EdtDispatcher.requireEventDispatchThread();
        taskLaunchController = Objects.requireNonNull(controller, "controller");
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

    /// Creates a catalog with explicit source, target, task, and executor boundaries for focused tests.
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
        this(
                kind,
                backend,
                installLauncher,
                targetResolver,
                workerExecutor,
                strings,
                taskProgressStrings,
                animator,
                progressAnimationDuration,
                null);
    }

    /// Creates a catalog with explicit source boundaries and an optional local instance target selector.
    RemoteAddonCatalogPanel(
            RemoteAddonCatalogKind kind,
            RemoteAddonCatalogBackend backend,
            RemoteAddonInstallLauncher installLauncher,
            RemoteAddonInstallTargetResolver targetResolver,
            Executor workerExecutor,
            RemoteAddonCatalogStrings strings,
            TaskProgressStrings taskProgressStrings,
            @Nullable SwingAnimator animator,
            Duration progressAnimationDuration,
            @Nullable InstancesModel instancesModel) {
        this(
                kind,
                backend,
                installLauncher,
                targetResolver,
                workerExecutor,
                strings,
                taskProgressStrings,
                animator,
                progressAnimationDuration,
                instancesModel,
                new TaskLaunchController(() -> { }));
    }

    /// Creates a catalog with explicit task navigation ownership.
    RemoteAddonCatalogPanel(
            RemoteAddonCatalogKind kind,
            RemoteAddonCatalogBackend backend,
            RemoteAddonInstallLauncher installLauncher,
            RemoteAddonInstallTargetResolver targetResolver,
            Executor workerExecutor,
            RemoteAddonCatalogStrings strings,
            TaskProgressStrings taskProgressStrings,
            @Nullable SwingAnimator animator,
            Duration progressAnimationDuration,
            @Nullable InstancesModel instancesModel,
            TaskLaunchController taskLaunchController) {
        super(new MigLayout(
                "insets 0, fill, wrap 1",
                "[grow,fill]",
                "[]8[pref!,shrink 0]8[grow,fill,shrink 100]8[pref!,shrink 0]8[pref!,shrink 0]8[pref!,shrink 0]8[pref!,shrink 0]"));
        EdtDispatcher.requireEventDispatchThread();
        this.kind = Objects.requireNonNull(kind, "kind");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.installLauncher = Objects.requireNonNull(installLauncher, "installLauncher");
        this.targetResolver = Objects.requireNonNull(targetResolver, "targetResolver");
        this.workerExecutor = Objects.requireNonNull(workerExecutor, "workerExecutor");
        this.taskLaunchController = Objects.requireNonNull(taskLaunchController, "taskLaunchController");
        targetInstanceSelector = this.kind == RemoteAddonCatalogKind.WORLD || instancesModel == null
                ? null
                : new RemoteAddonTargetInstanceSelector(instancesModel);
        showingListener = event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0
                    && isShowing()
                    && targetInstanceSelector != null) {
                targetInstanceSelector.start();
                updateControls();
            }
        };
        iconCache = new RemoteAddonIconCache(this.workerExecutor);
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
        choiceList = new ViewportChoiceList<>(
                dataSource,
                new RichChoiceListCellRenderer<>(
                        item -> item.addon().title().isBlank() ? item.addon().slug() : item.addon().title(),
                        RemoteAddonCatalogItem::rowDetail,
                        item -> item.source().displayName(),
                        item -> iconCache.icon(item.addon().iconUrl(), this::repaint),
                        item -> item.addon().pageUrl()));
        choiceList.getViewport().addChangeListener(viewportListener);
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

    /// Prefills the project query and starts its first provider page after a usable result viewport exists.
    ///
    /// The panel is often created and selected before Swing has completed the first parent layout. In that
    /// state the request is retained and retried from the next layout/visibility callback instead of being
    /// rejected as an unavailable viewport. This method must be called on the Swing event dispatch thread.
    ///
    /// @param searchText non-blank project or dependency identifier
    public void openSearch(String searchText) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            return;
        }
        String query = Objects.requireNonNull(searchText, "searchText").trim();
        if (query.isEmpty()) {
            throw new IllegalArgumentException("searchText must not be blank");
        }
        searchField.setText(query);
        pendingSearchText = query;
        if (measuredPageSize() > 0 && !catalogLoading && activeExecutor == null) {
            pendingSearchText = null;
            submitFirstPageSearch();
        } else {
            schedulePendingSearchCheck();
        }
    }

    /// Opens a read-only missing-dependency query using the same fixed scope as candidate discovery.
    ///
    /// Programmatic diagnosis navigation always uses Modrinth, all categories, popularity ordering, and the
    /// analyzer's captured Minecraft version. An older read-only request is made stale before these criteria are
    /// applied, while an active installation remains owned by its existing task and merely delays the new search.
    ///
    /// @param searchText non-blank dependency identifier
    /// @param gameVersion analyzed Minecraft version, or null when unavailable
    public void openMissingDependencySearch(String searchText, @Nullable String gameVersion) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            return;
        }
        if (catalogLoading) {
            catalogRequestRevision.incrementAndGet();
            catalogLoading = false;
        }
        sourceBox.setSelectedItem(RemoteAddonCatalogSource.MODRINTH);
        resetCategoryOptions();
        resetSortOptions();
        SwingTextFields.textEditor(gameVersionField).setText(Objects.requireNonNullElse(gameVersion, "").trim());
        openSearch(searchText);
    }

    /// Queues one pending-search check after the current EDT event completes.
    private void schedulePendingSearchCheck() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || pendingSearchText == null || pendingSearchCheckQueued) {
            return;
        }
        pendingSearchCheckQueued = true;
        EdtDispatcher.executeLater(this::submitPendingSearchIfReady);
    }

    /// Submits a retained programmatic query only after layout has produced a positive page size.
    private void submitPendingSearchIfReady() {
        EdtDispatcher.requireEventDispatchThread();
        pendingSearchCheckQueued = false;
        @Nullable String query = pendingSearchText;
        if (query == null || closed) {
            return;
        }
        if (!query.equals(searchField.getText().trim())) {
            pendingSearchText = null;
            return;
        }
        if (catalogLoading || activeExecutor != null) {
            return;
        }
        if (measuredPageSize() <= 0) {
            return;
        }
        pendingSearchText = null;
        submitFirstPageSearch();
    }

    /// Starts category discovery only when this panel receives a peer while visible.
    @Override
    public void addNotify() {
        super.addNotify();
        EdtDispatcher.requireEventDispatchThread();
        if (isVisible()) {
            requestCategoriesForSelectedSource();
            schedulePendingSearchCheck();
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
            schedulePendingSearchCheck();
        }
    }

    /// Completes the first parent layout before checking a retained programmatic search request.
    @Override
    public void doLayout() {
        super.doLayout();
        if (pendingSearchText != null) {
            schedulePendingSearchCheck();
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
        setMinimumSize(new Dimension(0, 0));

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
        headingBand.setMinimumSize(new Dimension(0, 0));
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
        filterBand.setMinimumSize(new Dimension(0, 0));

        JPanel criteriaBand = new JPanel(new MigLayout(
                "insets 0, fillx, wrap 4",
                "[grow,fill][grow,fill][grow,fill][grow,fill]",
                "[40!]"));
        criteriaBand.setName("remoteAddonCriteriaBand");
        criteriaBand.setOpaque(false);
        criteriaBand.setMinimumSize(new Dimension(0, 0));

        JLabel sourceLabel = new JLabel(strings.sourceLabel());
        sourceLabel.setLabelFor(sourceBox);
        sourceBox.setName("remoteAddonSource");
        sourceBox.addActionListener(event -> sourceChanged());
        criteriaBand.add(RemoteCatalogFilterField.create(sourceLabel, sourceBox), "growx, wmin 0");

        JLabel gameVersionLabel = new JLabel(strings.gameVersionLabel());
        gameVersionLabel.setLabelFor(gameVersionField);
        gameVersionField.setName("remoteAddonGameVersion");
        configureGameVersionSelector();
        criteriaBand.add(RemoteCatalogFilterField.create(gameVersionLabel, gameVersionField), "growx, wmin 0");

        RemoteCatalogFilterStrings filterStrings = strings.filterStrings();
        JLabel categoryLabel = new JLabel(filterStrings.categoryLabel());
        categoryLabel.setLabelFor(categoryBox);
        categoryBox.setName("remoteAddonCategory");
        categoryBox.setRenderer(new RemoteCatalogCategoryRenderer(
                () -> selectedSource() == RemoteAddonCatalogSource.MODRINTH,
                filterStrings));
        resetCategoryOptions();
        categoryBox.addActionListener(event -> categoryChanged());
        criteriaBand.add(RemoteCatalogFilterField.create(categoryLabel, categoryBox), "growx, wmin 0");

        JLabel sortLabel = new JLabel(filterStrings.sortLabel());
        sortLabel.setLabelFor(sortBox);
        sortBox.setName("remoteAddonSort");
        sortBox.setRenderer(new RemoteCatalogSortRenderer(filterStrings));
        resetSortOptions();
        sortBox.addActionListener(event -> sortChanged());
        criteriaBand.add(RemoteCatalogFilterField.create(sortLabel, sortBox), "growx, wmin 0");
        filterBand.add(criteriaBand, "growx, wmin 0");

        JPanel searchBand = new JPanel(new MigLayout(
                "insets 0, fillx, wrap 4",
                "[grow,fill][grow,fill][grow,fill][grow,fill]",
                "[40!]"));
        searchBand.setName("remoteAddonSearchBand");
        searchBand.setOpaque(false);
        searchBand.setMinimumSize(new Dimension(0, 0));

        JLabel searchLabel = new JLabel(strings.searchLabel());
        searchLabel.setLabelFor(searchField);
        searchField.setName("remoteAddonSearch");
        SwingTextFields.showClearButton(searchField);
        searchField.getDocument().addDocumentListener(criteriaListener);
        searchBand.add(RemoteCatalogFilterField.create(searchLabel, searchField), "span 3, growx, wmin 0");

        searchButton.setName("remoteAddonSearchAction");
        searchButton.setText(strings.searchAction());
        searchButton.addActionListener(event -> submitFirstPageSearch());
        searchButton.setMinimumSize(new Dimension(0, 0));
        searchBand.add(searchButton, "grow, wmin 0, h 40!");
        filterBand.add(searchBand, "growx, wmin 0");

        JPanel pageBand = new JPanel(new MigLayout(
                "insets 0, fillx",
                "[grow,fill][grow,fill][grow,fill][grow,fill]",
                "[40!]"));
        pageBand.setName("remoteAddonPageBand");
        pageBand.setOpaque(false);
        pageBand.setMinimumSize(new Dimension(0, 0));

        firstPageButton.setName("remoteAddonFirstPage");
        firstPageButton.setText(i18n("search.first_page"));
        firstPageButton.addActionListener(event -> submitBoundaryPage(false));
        firstPageButton.setMinimumSize(new Dimension(0, 0));
        pageBand.add(firstPageButton, "grow, wmin 0, h 40!");
        previousPageButton.setName("remoteAddonPreviousPage");
        previousPageButton.setText(strings.previousPageAction());
        previousPageButton.addActionListener(event -> submitRelativePage(-1));
        previousPageButton.setMinimumSize(new Dimension(0, 0));
        pageBand.add(previousPageButton, "grow, wmin 0, h 40!");
        nextPageButton.setName("remoteAddonNextPage");
        nextPageButton.setText(strings.nextPageAction());
        nextPageButton.addActionListener(event -> submitRelativePage(1));
        nextPageButton.setMinimumSize(new Dimension(0, 0));
        pageBand.add(nextPageButton, "grow, wmin 0, h 40!");
        lastPageButton.setName("remoteAddonLastPage");
        lastPageButton.setText(i18n("search.last_page"));
        lastPageButton.addActionListener(event -> submitBoundaryPage(true));
        lastPageButton.setMinimumSize(new Dimension(0, 0));
        pageBand.add(lastPageButton, "grow, wmin 0, h 40!");
        filterBand.add(pageBand, "growx, wmin 0");
        add(filterBand, "growx");

        choiceList.setName("remoteAddonResults");
        choiceList.setOpaque(false);
        choiceList.getViewport().setOpaque(false);
        JList<ChoiceListEntry<RemoteAddonCatalogItem>> resultList = choiceList.getList();
        resultList.setName("remoteAddonResultsView");
        resultList.setOpaque(false);
        resultList.setBorder(BorderFactory.createEmptyBorder(0, 0, RESULT_LIST_BOTTOM_PADDING, 0));
        resultList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                selectedRowChanged();
            }
        });
        choiceList.getChoiceModel().addListDataListener(listDataListener);
        add(choiceList, "grow");

        add(RemoteAddonProjectDetailsLayout.create(
                projectSummaryLabel,
                upstreamButton,
                i18n("swing.download.upstream"),
                this::openUpstreamPage,
                prerequisitesLabel,
                i18n("swing.download.prerequisites"),
                prerequisiteButtons), "growx, wmin 0");

        int selectorColumnCount = targetInstanceSelector == null ? 4 : 6;
        JPanel installBand = new JPanel(new MigLayout(
                "insets 0, fillx, wrap " + selectorColumnCount,
                "[pref!][grow,fill]".repeat(selectorColumnCount / 2),
                "[40!]8[40!]"));
        installBand.setName("remoteAddonInstallBand");
        installBand.setOpaque(false);
        installBand.setMinimumSize(new Dimension(0, 0));
        if (targetInstanceSelector != null) {
            JLabel targetLabel = new JLabel(i18n("game.instance"));
            targetLabel.setLabelFor(targetInstanceSelector.component());
            installBand.add(targetLabel);
            targetInstanceSelector.component().setName("remoteAddonTargetInstance");
            installBand.add(targetInstanceSelector.component(), "growx, wmin 0, h 40!");
        }
        JLabel versionLabel = new JLabel(strings.versionLabel());
        versionLabel.setLabelFor(versionBox);
        installBand.add(versionLabel);
        versionBox.setName("remoteAddonVersion");
        versionBox.setRenderer(versionRenderer);
        versionBox.addActionListener(event -> versionChanged());
        versionBox.setMinimumSize(new Dimension(0, 0));
        installBand.add(versionBox, "growx, wmin 0, h 40!");
        JLabel versionSortLabel = new JLabel(filterStrings.versionSortLabel());
        versionSortLabel.setLabelFor(versionSortBox);
        installBand.add(versionSortLabel);
        versionSortBox.setName("remoteAddonVersionSort");
        versionSortBox.setRenderer(new RemoteAddonVersionSortRenderer(filterStrings));
        versionSortBox.addActionListener(event -> versionSortChanged());
        versionSortBox.setMinimumSize(new Dimension(0, 0));
        installBand.add(versionSortBox, "growx, wmin 0, h 40!");
        changelogButton.setName("remoteAddonChangelog");
        changelogButton.setText(i18n("update.changelog"));
        changelogButton.addActionListener(event -> showSelectedChangelog());
        changelogButton.setMinimumSize(new Dimension(0, 0));
        installBand.add(changelogButton,
                "span " + selectorColumnCount / 2 + ", grow, wmin 0, h 40!");
        installButton.setName("remoteAddonInstall");
        installButton.setText(strings.installAction());
        installButton.addActionListener(event -> beginInstall());
        installButton.setMinimumSize(new Dimension(0, 0));
        installBand.add(installButton,
                "span " + selectorColumnCount / 2 + ", grow, wmin 0, h 40!");
        add(installBand, "growx");

        statusLabel.setName("remoteAddonStatus");
        statusLabel.addMouseListener(statusMouseListener);
        add(statusLabel, "growx, h 24!");
        addHierarchyListener(showingListener);
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

    /// Updates project metadata, upstream availability, and version-specific prerequisite buttons.
    ///
    /// @param item selected remote project, or null when no row is selected
    /// @param version selected installable version, or null while versions are loading
    private void updateProjectDetails(
            @Nullable RemoteAddonCatalogItem item,
            @Nullable RemoteAddon.Version version) {
        if (item == null) {
            projectSummaryLabel.setText("");
            projectSummaryLabel.setToolTipText(null);
            upstreamButton.putClientProperty("remoteAddonUpstreamUri", null);
            upstreamButton.setToolTipText(null);
            upstreamButton.setVisible(false);
            prerequisitesLabel.setVisible(false);
            prerequisiteButtons.setVisible(false);
            prerequisiteButtons.removeAll();
            return;
        }

        RemoteAddon addon = item.addon();
        List<String> summary = new ArrayList<>();
        String title = addon.title().isBlank() ? addon.slug() : addon.title();
        if (!title.isBlank()) {
            summary.add(title);
        }
        if (!addon.author().isBlank()) {
            summary.add(addon.author());
        }
        String description = firstNonBlankLine(addon.description());
        if (!description.isBlank()) {
            summary.add(description);
        }
        if (version != null) {
            summary.add(RemoteAddonVersionOrdering.gameVersionText(
                    version,
                    SwingTextFields.comboText(gameVersionField))
                    + " | " + version.version());
        }
        projectSummaryLabel.setText(String.join(" | ", summary));
        projectSummaryLabel.setToolTipText(addon.description().isBlank() ? null : addon.description());

        @Nullable URI upstream = httpUri(addon.pageUrl());
        upstreamButton.putClientProperty("remoteAddonUpstreamUri", upstream);
        upstreamButton.setToolTipText(upstream == null ? null : upstream.toString());
        upstreamButton.setVisible(upstream != null);
        upstreamButton.setEnabled(upstream != null && !closed && activeExecutor == null);

        prerequisiteButtons.removeAll();
        if (kind == RemoteAddonCatalogKind.MOD && version != null) {
            addDependencyButtons(version);
        }
        boolean hasDependencies = prerequisiteButtons.getComponentCount() > 0;
        prerequisitesLabel.setVisible(hasDependencies);
        prerequisiteButtons.setVisible(hasDependencies);
        prerequisiteButtons.revalidate();
        prerequisiteButtons.repaint();
    }

    /// Adds one button for each downloadable prerequisite in the selected version.
    ///
    /// Embedded, incompatible, and broken entries are intentionally omitted because they cannot
    /// be acquired through the mod search route.
    ///
    /// @param version selected provider version
    private void addDependencyButtons(RemoteAddon.Version version) {
        Set<RemoteAddon.DependencyType> downloadable = EnumSet.of(
                RemoteAddon.DependencyType.REQUIRED,
                RemoteAddon.DependencyType.OPTIONAL,
                RemoteAddon.DependencyType.TOOL);
        Set<String> identifiers = new LinkedHashSet<>();
        for (RemoteAddon.Dependency dependency : version.dependencies()) {
            RemoteAddon.DependencyType type = dependency.getType();
            @Nullable String rawId = dependency.getId();
            if (!downloadable.contains(type) || rawId == null || rawId.isBlank()) {
                continue;
            }
            String id = rawId.trim();
            if (!identifiers.add(id)) {
                continue;
            }
            JButton dependencyButton = new JButton(id);
            dependencyButton.setName("remoteAddonDependency_" + dependencyComponentName(id));
            dependencyButton.setToolTipText(i18n(
                    "addon.dependency." + type.name().toLowerCase(Locale.ROOT)));
            dependencyButton.setMinimumSize(new Dimension(0, 32));
            dependencyButton.addActionListener(event -> openDependencySearch(dependency, id));
            prerequisiteButtons.add(dependencyButton, "growx, wmin 0, h 32!");
        }
    }

    /// Selects a dependency's provider when available, then opens its Mod search route.
    ///
    /// @param dependency provider dependency represented by the command
    /// @param identifier non-blank provider project identifier
    private void openDependencySearch(RemoteAddon.Dependency dependency, String identifier) {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable RemoteAddon.Source dependencySource = dependency.getSource();
        if (dependencySource != null) {
            for (RemoteAddonCatalogSource candidate : RemoteAddonCatalogSource.values()) {
                if (candidate.coreSource() == dependencySource
                        && candidate.supports(RemoteAddonCatalogKind.MOD)) {
                    sourceBox.setSelectedItem(candidate);
                    break;
                }
            }
        }
        openSearch(identifier);
    }

    /// Opens a selected project's upstream page in the platform browser.
    private void openUpstreamPage() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable Object property = upstreamButton.getClientProperty("remoteAddonUpstreamUri");
        if (!(property instanceof URI uri) || closed) {
            return;
        }
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            return;
        }
        try {
            Desktop.getDesktop().browse(uri);
        } catch (IOException | RuntimeException browseFailure) {
            LOG.warning("Failed to open remote add-on upstream page", browseFailure);
        }
    }

    /// Updates project details after the user changes the selected installable version.
    private void versionChanged() {
        EdtDispatcher.requireEventDispatchThread();
        if (applyingVersionSort) {
            return;
        }
        @Nullable RemoteAddon.Version version = (RemoteAddon.Version) versionBox.getSelectedItem();
        updateProjectDetails(selectedItem, version);
        updateControls();
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
        updateProjectDetails(selectedItem, (RemoteAddon.Version) versionBox.getSelectedItem());
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
                        "remote add-on version sort mode"));
    }

    /// Returns a validated HTTP(S) URI for an upstream page.
    ///
    /// @param rawUrl provider page value
    /// @return HTTP(S) URI, or null when the provider value is malformed or unsafe
    private static @Nullable URI httpUri(String rawUrl) {
        try {
            URI uri = URI.create(Objects.requireNonNull(rawUrl, "rawUrl").trim());
            String scheme = uri.getScheme();
            return scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    ? uri
                    : null;
        } catch (IllegalArgumentException failure) {
            return null;
        }
    }

    /// Converts a provider dependency id into a stable Swing component name fragment.
    ///
    /// @param id dependency identifier
    /// @return non-blank name-safe fragment
    private static String dependencyComponentName(String id) {
        String normalized = Objects.requireNonNull(id, "id").replaceAll("[^A-Za-z0-9_.-]", "_");
        return normalized.isBlank() ? "dependency" : normalized;
    }

    /// Returns the first meaningful line from optional multiline metadata.
    ///
    /// @param text complete description
    /// @return trimmed first line, or an empty string
    private static String firstNonBlankLine(String text) {
        return Objects.requireNonNull(text, "text").lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .findFirst()
                .orElse("");
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
        RemoteAddonCatalogSource source = selectedSource();
        if (!source.isAvailable() || !source.supports(kind)) {
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
    private void applyCategoryFailure(RemoteAddonCatalogSource source, long requestRevision) {
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
                SwingTextFields.comboText(gameVersionField),
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
        setCatalogIdleStatus(page.items().isEmpty() ? strings.noResultsStatus() : "");
        updateControls();
        schedulePendingSearchCheck();
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
            setStatus(strings.searchFailedStatus(), this::retryCatalogSearch);
            updateControls();
            schedulePendingSearchCheck();
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
        updateProjectDetails(item, null);
        versionLoading = true;
        loadedVersions = List.of();
        recommendedVersion = null;
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
        @Nullable RemoteAddonCatalogItem item = selectedItem;
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
            LOG.warning("Failed to schedule remote add-on version loading retry", schedulingFailure);
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
            updateProjectDetails(item, (RemoteAddon.Version) versionBox.getSelectedItem());
            setStatus("");
        } else {
            updateProjectDetails(item, null);
            setStatus(strings.noVersionsStatus(), this::returnFromEmptyVersions);
        }
        updateControls();
    }

    /// Loads the selected version changelog away from the EDT and opens its Swing dialog.
    private void showSelectedChangelog() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed || changelogLoading) {
            return;
        }
        @Nullable RemoteAddonCatalogItem item = selectedItem;
        @Nullable RemoteAddon.Version version = (RemoteAddon.Version) versionBox.getSelectedItem();
        if (item == null || version == null) {
            return;
        }
        changelogLoading = true;
        updateControls();
        long requestRevision = selectionRequestRevision.get();
        try {
            workerExecutor.execute(() -> {
                try {
                    @Nullable String markdown = backend.loadChangelog(item, version);
                    java.net.URI page = backend.versionPage(item, version);
                    SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
                        if (closed || selectionRequestRevision.get() != requestRevision
                                || selectedItem != item) {
                            return;
                        }
                        changelogLoading = false;
                        updateControls();
                        RemoteAddonChangelogDialog.show(
                                this,
                                item.addon().title() + " " + version.version(),
                                markdown,
                                page);
                    });
                } catch (IOException | RuntimeException failure) {
                    LOG.warning("Failed to load remote add-on changelog", failure);
                    SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
                        if (closed || selectionRequestRevision.get() != requestRevision
                                || selectedItem != item) {
                            return;
                        }
                        changelogLoading = false;
                        updateControls();
                        JOptionPane.showMessageDialog(this, i18n("addon.changelog.failed"),
                                i18n("message.error"), JOptionPane.ERROR_MESSAGE);
                    });
                }
            });
        } catch (RuntimeException schedulingFailure) {
            changelogLoading = false;
            updateControls();
            LOG.warning("Failed to schedule remote add-on changelog request", schedulingFailure);
        }
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
            loadedVersions = List.of();
            recommendedVersion = null;
            versionBox.removeAllItems();
            updateProjectDetails(item, null);
            setStatus(strings.versionLoadFailedStatus(), this::retrySelectedVersions);
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
        Subscription completionSubscription = executor.subscribeTaskListener(
                new RemoteAddonInstallCompletionListener(executor, this::installCompleted));
        activeExecutor = executor;
        activeCompletionSubscription = completionSubscription;
        setStatus(strings.installingStatus());
        updateControls();
        try {
            taskLaunchController.launch(executor, strings.installingStatus(), () -> { });
        } catch (RuntimeException | Error startFailure) {
            LOG.warning("Failed to start selected remote add-on installation", startFailure);
            cleanupFailedTaskStart(completionSubscription);
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
            schedulePendingSearchCheck();
        });
    }

    /// Releases task resources when executor startup fails before a terminal callback can arrive.
    ///
    /// @param completionSubscription terminal listener created for the failed executor
    private void cleanupFailedTaskStart(Subscription completionSubscription) {
        unsubscribe(completionSubscription);
        activeCompletionSubscription = null;
        activeExecutor = null;
        schedulePendingSearchCheck();
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

    /// Clears selected sparse-row state and any provider versions belonging to the old selection.
    private void clearSelectedProject() {
        EdtDispatcher.requireEventDispatchThread();
        choiceList.getList().clearSelection();
        selectedItem = null;
        updateProjectDetails(null, null);
        versionLoading = false;
        changelogLoading = false;
        loadedVersions = List.of();
        recommendedVersion = null;
        versionBox.removeAllItems();
        versionRenderer.setSelectionContext(null, "");
    }

    /// Resolves the current selected target only after an explicit acquisition command.
    ///
    /// @param item selected remote project
    /// @param version exact selected version
    /// @return current selected target, or null when no usable instance is selected
    private @Nullable RemoteAddonInstallTarget resolveInstallTarget(
            RemoteAddonCatalogItem item,
            RemoteAddon.Version version) {
        if (targetInstanceSelector != null) {
            targetInstanceSelector.synchronizeFromModel();
            @Nullable GameInstanceID targetInstanceId = targetInstanceSelector.selectedInstanceId();
            Optional<RemoteAddonInstallTarget> target = Objects.requireNonNull(
                    targetResolver.resolveSelection(kind, targetInstanceId, item, version, this),
                    "targetResolver returned null selection optional");
            return target.orElse(null);
        }
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
        if (targetInstanceSelector != null) {
            targetInstanceSelector.synchronizeFromModel();
            targetInstanceSelector.component().setEnabled(criteriaEnabled);
        }
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

        versionBox.setEnabled(inputsEnabled
                && selectedItem != null
                && !versionLoading
                && versionBox.getItemCount() > 0);
        versionSortBox.setEnabled(inputsEnabled
                && selectedItem != null
                && !versionLoading
                && !loadedVersions.isEmpty());
        upstreamButton.setEnabled(inputsEnabled && upstreamButton.isVisible()
                && upstreamButton.getClientProperty("remoteAddonUpstreamUri") instanceof URI);
        for (Component component : prerequisiteButtons.getComponents()) {
            component.setEnabled(inputsEnabled);
        }
        changelogButton.setEnabled(inputsEnabled
                && selectedItem != null
                && !versionLoading
                && !changelogLoading
                && versionBox.getSelectedItem() != null);
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
            if (targetInstanceSelector != null) {
                targetInstanceSelector.synchronizeFromModel();
                return targetResolver.isSelectionAvailable(kind, targetInstanceSelector.selectedInstanceId());
            }
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

    /// Cancels live task state and releases all owned listeners and child presentation resources on the EDT.
    private void closeOnEventDispatchThread() {
        EdtDispatcher.requireEventDispatchThread();
        clearStatusAction();
        if (targetInstanceSelector != null) {
            targetInstanceSelector.close();
            targetInstanceSelector.component().setEnabled(false);
        }
        iconCache.close();
        pendingSearchText = null;
        pendingSearchCheckQueued = false;
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
        searchField.getDocument().removeDocumentListener(criteriaListener);
        SwingTextFields.textEditor(gameVersionField).getDocument().removeDocumentListener(criteriaListener);
        choiceList.getViewport().removeChangeListener(viewportListener);
        choiceList.getChoiceModel().removeListDataListener(listDataListener);
        removeHierarchyListener(showingListener);
        choiceList.close();
        pageCache.clear();
        sourceBox.setEnabled(false);
        searchField.setEnabled(false);
        gameVersionField.setEnabled(false);
        categoryBox.setEnabled(false);
        sortBox.setEnabled(false);
        versionBox.setEnabled(false);
        versionSortBox.setEnabled(false);
        changelogButton.setEnabled(false);
        searchButton.setEnabled(false);
        firstPageButton.setEnabled(false);
        previousPageButton.setEnabled(false);
        nextPageButton.setEnabled(false);
        lastPageButton.setEnabled(false);
        installButton.setEnabled(false);
        upstreamButton.setEnabled(false);
        upstreamButton.setVisible(false);
        prerequisitesLabel.setVisible(false);
        prerequisiteButtons.setVisible(false);
        prerequisiteButtons.removeAll();
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
        /// {@inheritDoc}
        @Override
        public void insertUpdate(DocumentEvent event) {
            criteriaChanged();
        }

        /// {@inheritDoc}
        @Override
        public void removeUpdate(DocumentEvent event) {
            criteriaChanged();
        }

        /// {@inheritDoc}
        @Override
        public void changedUpdate(DocumentEvent event) {
            criteriaChanged();
        }
    }

    /// Rechecks a sparse selected row when its visible placeholder changes into a loaded project value.
    @NotNullByDefault
    private final class CatalogListDataListener implements ListDataListener {
        /// {@inheritDoc}
        @Override
        public void intervalAdded(ListDataEvent event) {
            selectedRowChanged();
        }

        /// {@inheritDoc}
        @Override
        public void intervalRemoved(ListDataEvent event) {
            selectedRowChanged();
        }

        /// {@inheritDoc}
        @Override
        public void contentsChanged(ListDataEvent event) {
            selectedRowChanged();
        }
    }

}

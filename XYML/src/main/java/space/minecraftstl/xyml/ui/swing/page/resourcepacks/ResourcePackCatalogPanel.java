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
package space.minecraftstl.xyml.ui.swing.page.resourcepacks;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingTransparency;
import space.minecraftstl.xyml.ui.swing.choice.ChoiceListEntry;
import space.minecraftstl.xyml.ui.swing.choice.ViewportChoiceList;

import javax.swing.BorderFactory;
import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.KeyStroke;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/// Presents an installed-resource-pack catalog without performing source I/O in Swing code.
///
/// The panel requests its first shallow index only when it is actually showing. Loaded rows come
/// exclusively from [ViewportChoiceList], so list demand follows measured viewport geometry.
/// Details use only immutable presentation values already supplied by the model: this component
/// never opens resource-pack paths, decodes pack icons, or accesses a network service.
@NotNullByDefault
public final class ResourcePackCatalogPanel extends JPanel implements AutoCloseable {
    /// Card shown before the first lazy index request.
    private static final String IDLE_CARD = "idle";

    /// Card shown while the shallow index is loading.
    private static final String LOADING_CARD = "loading";

    /// Card shown when the latest local index failed.
    private static final String FAILED_CARD = "failed";

    /// Card shown when the managed game version predates resource-pack support.
    private static final String UNSUPPORTED_CARD = "unsupported";

    /// Card shown when a successful index contains no resource packs.
    private static final String EMPTY_CARD = "empty";

    /// Card containing the viewport list and read-only details.
    private static final String LIST_CARD = "list";

    /// Sentinel distinguishing no pending selection command from a pending clear command.
    private static final int NO_PENDING_SELECTION = -2;

    /// Minimum allocated width that presents list and details side by side.
    private static final int WIDE_LAYOUT_MINIMUM_WIDTH = 720;

    /// Lock guarding close state and coalesced model notification revisions.
    private final Object stateLock = new Object();

    /// Serializes EDT snapshot application with synchronous resource cleanup.
    private final Object publicationLock = new Object();

    /// Owned toolkit-neutral catalog model and viewport source.
    private final ResourcePackCatalogModel model;

    /// Localized visible and accessible text.
    private final ResourcePackCatalogStrings strings;

    /// Localized command, confirmation, and failure text.
    private final ResourcePackCatalogActionStrings actionStrings;

    /// Application-owned dialog, desktop, and directory interaction boundary.
    private final ResourcePackCatalogInteractions interactions;

    /// Stable normalized directory managed by this catalog.
    private final Path resourcePackDirectory;

    /// Viewport-measured single-choice list.
    private final ViewportChoiceList<ResourcePackCatalogItem> choiceList;

    /// Cards representing every catalog lifecycle and content state.
    private final JPanel contentCards;

    /// Theme-aware source refresh command.
    private final JButton refreshButton;

    /// Retry command available only after a failed local scan.
    private final JButton retryButton;

    /// Multi-file resource-pack import command.
    private final JButton importButton;

    /// Command that creates and opens the managed resource-pack directory.
    private final JButton openDirectoryButton;

    /// Binary enabled-state command for the loaded selected pack.
    private final JCheckBox enabledToggle;

    /// Command that reveals the loaded selected pack in the platform file manager.
    private final JButton revealButton;

    /// Permanent deletion command for the loaded selected pack.
    private final JButton deleteButton;

    /// Current model status and retained failure detail.
    private final JTextArea statusText;

    /// Text displayed on the untouched initial card.
    private final JTextArea idleText;

    /// Text displayed while a local index generation is active.
    private final JTextArea loadingText;

    /// Failure heading and model-provided detail.
    private final JTextArea failedText;

    /// Unsupported-version state text.
    private final JTextArea unsupportedText;

    /// Successful empty-index state text.
    private final JTextArea emptyText;

    /// Exact file or directory name for the loaded selection.
    private final JLabel fileNameValue;

    /// Normalized absolute path for the loaded selection.
    private final JTextArea pathArea;

    /// Complete potentially multiline description for the loaded selection.
    private final JTextArea descriptionArea;

    /// Compatibility text for the managed game version.
    private final JLabel compatibilityValue;

    /// Whether Minecraft options currently enable the loaded selection.
    private final JLabel enabledValue;

    /// Responsive split that changes from side-by-side to stacked at narrow widths.
    private final ResponsiveCatalogSplitPane catalogSplit;

    /// Rechecks a pending placeholder selection and details after sparse rows change.
    private final ListDataListener listDataListener;

    /// Delegates a user selection only after its sparse row has loaded.
    private final ListSelectionListener selectionListener;

    /// Starts the catalog only when this page becomes the actually showing card.
    private final HierarchyListener showingListener;

    /// Captures one settled user selection and waits for its sparse row when necessary.
    ///
    /// @param event list selection transition
    private void selectionChanged(ListSelectionEvent event) {
        if (!event.getValueIsAdjusting() && !applyingSnapshot && isOpen()) {
            pendingUserSelectionIndex = choiceList.getList().getSelectedIndex();
            submitPendingUserSelection();
            updateSelectionDetails();
        }
    }

    /// Owned model-listener registration.
    private final Subscription modelSubscription;

    /// Snapshot currently represented by controls, or null before constructor initialization.
    private @Nullable ResourcePackCatalogSnapshot displayedSnapshot;

    /// Logical row awaiting its loaded value, -1 for clear, or the no-pending sentinel.
    private int pendingUserSelectionIndex = NO_PENDING_SELECTION;

    /// Whether programmatic selection restoration suppresses model commands.
    private boolean applyingSnapshot;

    /// Revision invalidating older worker-to-EDT snapshot applications.
    private long updateRevision;

    /// Whether this panel already delegated its one initial lazy-load request.
    private boolean initialLoadRequested;

    /// Whether callers have crossed the synchronous close gate.
    private boolean closed;

    /// Whether EDT-owned component and model resources have been released.
    private boolean resourcesClosed;

    /// Whether programmatic checkbox reconciliation suppresses user commands.
    private boolean applyingEnabledToggle;

    /// Whether a model write command remains locally outstanding.
    private boolean writePending;

    /// Model-notification revision captured immediately before the current write invocation.
    private long writeStartUpdateRevision;

    /// Whether one platform reveal command remains outstanding.
    private boolean revealPending;

    /// Whether one create-and-open-directory command remains outstanding.
    private boolean openDirectoryPending;

    /// Creates a read-only resource-pack catalog panel on the Swing event dispatch thread.
    ///
    /// @param model owned toolkit-neutral lazy catalog model
    /// @param strings localized catalog controls, states, and detail labels
    /// @param actionStrings localized command, confirmation, and failure text
    /// @param interactions application-owned dialog, desktop, and directory interaction boundary
    /// @param resourcePackDirectory stable managed resource-pack directory
    public ResourcePackCatalogPanel(
            ResourcePackCatalogModel model,
            ResourcePackCatalogStrings strings,
            ResourcePackCatalogActionStrings actionStrings,
            ResourcePackCatalogInteractions interactions,
            Path resourcePackDirectory) {
        super();
        @Nullable ViewportChoiceList<ResourcePackCatalogItem> acquiredChoiceList = null;
        @Nullable Subscription acquiredSubscription = null;
        try {
            EdtDispatcher.requireEventDispatchThread();
            this.model = Objects.requireNonNull(model, "model");
            this.strings = Objects.requireNonNull(strings, "strings");
            this.actionStrings = Objects.requireNonNull(actionStrings, "actionStrings");
            this.interactions = Objects.requireNonNull(interactions, "interactions");
            this.resourcePackDirectory = Objects.requireNonNull(
                    resourcePackDirectory,
                    "resourcePackDirectory").toAbsolutePath().normalize();
            setLayout(new MigLayout(
                    "insets 0, fill, wrap 1",
                    "[grow,fill]",
                    "[]12[grow,fill]8[]"));
            contentCards = new JPanel(new CardLayout());
            refreshButton = new JButton();
            retryButton = new JButton();
            importButton = new JButton();
            openDirectoryButton = new JButton();
            enabledToggle = new JCheckBox();
            revealButton = new JButton();
            deleteButton = new JButton();
            statusText = stateText("resourcePacksStatus");
            idleText = stateText("resourcePacksIdle");
            loadingText = stateText("resourcePacksLoading");
            failedText = stateText("resourcePacksFailed");
            unsupportedText = stateText("resourcePacksUnsupported");
            emptyText = stateText("resourcePacksEmpty");
            fileNameValue = new JLabel();
            pathArea = new JTextArea();
            descriptionArea = new JTextArea();
            compatibilityValue = new JLabel();
            enabledValue = new JLabel();
            listDataListener = createListDataListener();
            selectionListener = this::selectionChanged;
            showingListener = this::showingChanged;
            acquiredChoiceList = new ViewportChoiceList<>(model, ResourcePackCatalogItem::displayText);
            choiceList = acquiredChoiceList;
            catalogSplit = new ResponsiveCatalogSplitPane(choiceList, createDetailsPanel());
            configureComponents();
            acquiredSubscription = Objects.requireNonNull(
                    model.subscribe(this::modelChanged),
                    "resource-pack model returned null subscription");
            applySnapshot(model.snapshot());
        } catch (RuntimeException | Error failure) {
            @Nullable Throwable cleanupFailure = null;
            if (acquiredSubscription != null) {
                Subscription subscription = acquiredSubscription;
                cleanupFailure = attemptCleanup(cleanupFailure, subscription::unsubscribe);
            }
            if (acquiredChoiceList != null) {
                ViewportChoiceList<ResourcePackCatalogItem> list = acquiredChoiceList;
                cleanupFailure = attemptCleanup(cleanupFailure, list::close);
            }
            if (model != null) {
                ResourcePackCatalogModel ownedModel = model;
                cleanupFailure = attemptCleanup(cleanupFailure, ownedModel::close);
            }
            if (cleanupFailure != null && cleanupFailure != failure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        modelSubscription = Objects.requireNonNull(acquiredSubscription);
    }

    /// Returns the immutable snapshot currently represented by the page.
    ///
    /// @return displayed catalog state
    public ResourcePackCatalogSnapshot displayedSnapshot() {
        EdtDispatcher.requireEventDispatchThread();
        return Objects.requireNonNull(displayedSnapshot, "initial resource-pack snapshot was not applied");
    }

    /// Returns the viewport list for shell integration and focused verification.
    ///
    /// @return viewport-driven resource-pack list
    public ViewportChoiceList<ResourcePackCatalogItem> choiceList() {
        return choiceList;
    }

    /// Selects the responsive list/details orientation from this page's allocated width.
    @Override
    public void doLayout() {
        catalogSplit.updateForAvailableWidth(getWidth());
        super.doLayout();
    }

    /// Rechecks first-load eligibility after this page becomes displayable.
    @Override
    public void addNotify() {
        super.addNotify();
        requestInitialLoadIfShowing();
    }

    /// Starts the lazy source index on the first transition to actually showing.
    ///
    /// @param event component hierarchy transition
    private void showingChanged(HierarchyEvent event) {
        if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
            requestInitialLoadIfShowing();
        }
    }

    /// Delegates the initial index request once this exact page is visible to the user.
    private void requestInitialLoadIfShowing() {
        EdtDispatcher.requireEventDispatchThread();
        if (!isShowing()) {
            return;
        }
        synchronized (stateLock) {
            if (closed || initialLoadRequested) {
                return;
            }
            initialLoadRequested = true;
        }
        model.loadIfNeeded();
    }

    /// Synchronously gates future updates and releases the subscription, list, and owned model.
    @Override
    public void close() {
        synchronized (stateLock) {
            if (!closed) {
                closed = true;
                updateRevision++;
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

    /// Builds the title command, all state cards, list workspace, and status band.
    private void configureComponents() {
        setOpaque(false);
        JPanel headingBand = new JPanel(new MigLayout(
                "insets 0, fillx",
                "[grow,fill][][][]",
                "[40!]"));
        headingBand.setOpaque(false);

        JLabel heading = new JLabel(strings.pageTitle());
        heading.setName("resourcePacksPageTitle");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 28.0F));
        headingBand.add(heading, "growx");

        configureIconButton(
                importButton,
                "resourcePacksImport",
                "assets/swing/icons/file-import.svg",
                actionStrings.importAction(),
                actionStrings.importTooltip(),
                this::chooseAndImportResourcePacks);
        headingBand.add(importButton, "w 40!, h 40!");

        configureIconButton(
                openDirectoryButton,
                "resourcePacksOpenDirectory",
                "assets/swing/icons/folder-open.svg",
                actionStrings.openDirectoryAction(),
                actionStrings.openDirectoryTooltip(),
                this::openResourcePackDirectory);
        headingBand.add(openDirectoryButton, "w 40!, h 40!");

        refreshButton.setName("resourcePacksRefresh");
        refreshButton.setText(null);
        refreshButton.setIcon(new FlatSVGIcon("assets/swing/icons/refresh.svg", 18, 18));
        refreshButton.setToolTipText(strings.refreshTooltip());
        refreshButton.getAccessibleContext().setAccessibleDescription(strings.refreshTooltip());
        refreshButton.addActionListener(event -> requestRefresh());
        headingBand.add(refreshButton, "w 40!, h 40!");
        add(headingBand, "growx");

        configureChoiceList();

        idleText.setText(strings.idleText());
        loadingText.setText(strings.loadingText());
        unsupportedText.setText(strings.unsupportedText());
        emptyText.setText(strings.emptyText());

        JPanel failedPanel = new JPanel(new MigLayout(
                "insets 24, fill, wrap 1",
                "[grow,center]",
                "[grow,center]12[]"));
        failedPanel.setOpaque(false);
        failedPanel.setName("resourcePacksFailedPanel");
        JScrollPane failedScroll = createTransparentScrollPane(failedText, "resourcePacksFailedScroll");
        failedPanel.add(failedScroll, "grow");
        retryButton.setName("resourcePacksRetry");
        retryButton.setText(strings.retryAction());
        retryButton.setToolTipText(strings.retryTooltip());
        retryButton.getAccessibleContext().setAccessibleName(strings.retryAction());
        retryButton.getAccessibleContext().setAccessibleDescription(strings.retryTooltip());
        retryButton.addActionListener(event -> requestRefresh());
        failedPanel.add(retryButton, "h 40!");

        contentCards.setName("resourcePacksContent");
        contentCards.setOpaque(false);
        contentCards.add(createStateCard(idleText), IDLE_CARD);
        contentCards.add(createStateCard(loadingText), LOADING_CARD);
        contentCards.add(failedPanel, FAILED_CARD);
        contentCards.add(createStateCard(unsupportedText), UNSUPPORTED_CARD);
        contentCards.add(createStateCard(emptyText), EMPTY_CARD);
        contentCards.add(catalogSplit, LIST_CARD);
        add(contentCards, "grow, wmin 0");

        statusText.setRows(1);
        statusText.getAccessibleContext().setAccessibleName("");
        statusText.getAccessibleContext().setAccessibleDescription("");
        add(statusText, "growx, hmin 28, hmax 72");
        addHierarchyListener(showingListener);
    }

    /// Configures single-choice selection and sparse-row observation.
    private void configureChoiceList() {
        choiceList.setName("resourcePacksList");
        SwingTransparency.revealBackgroundThroughScrollPane(choiceList);
        JList<ChoiceListEntry<ResourcePackCatalogItem>> list = choiceList.getList();
        list.setName("resourcePacksListView");
        list.setOpaque(false);
        list.getAccessibleContext().setAccessibleName(strings.pageTitle());
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addListSelectionListener(selectionListener);
        list.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                "clearResourcePackSelection");
        list.getActionMap().put("clearResourcePackSelection", new AbstractAction() {
            /// Clears the stable selected row when the list can accept user commands.
            ///
            /// @param event ignored keyboard action event
            @Override
            public void actionPerformed(ActionEvent event) {
                if (list.isEnabled()) {
                    list.clearSelection();
                }
            }
        });
        choiceList.getChoiceModel().addListDataListener(listDataListener);
    }

    /// Creates the unframed read-only details surface used by both responsive orientations.
    ///
    /// @return configured details panel
    private JComponent createDetailsPanel() {
        JPanel details = new JPanel(new MigLayout(
                "insets 16, fill, wrap 2",
                "[][grow,fill]",
                "[]12[]8[]8[]8[]12[]8[grow,fill]12[]"));
        details.setName("resourcePacksDetails");
        details.setOpaque(false);

        JLabel heading = new JLabel(strings.detailsTitle());
        heading.setName("resourcePacksDetailsTitle");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD));
        details.add(heading, "span 2, growx");

        JLabel fileNameLabel = new JLabel(strings.fileNameLabel());
        fileNameLabel.setLabelFor(fileNameValue);
        details.add(fileNameLabel);
        fileNameValue.setName("resourcePacksFileName");
        details.add(fileNameValue, "growx");

        JLabel compatibilityLabel = new JLabel(strings.compatibilityLabel());
        compatibilityLabel.setLabelFor(compatibilityValue);
        details.add(compatibilityLabel);
        compatibilityValue.setName("resourcePacksCompatibility");
        details.add(compatibilityValue, "growx");

        JLabel enabledLabel = new JLabel(strings.enabledLabel());
        enabledLabel.setLabelFor(enabledValue);
        details.add(enabledLabel);
        enabledValue.setName("resourcePacksEnabled");
        details.add(enabledValue, "growx");

        JLabel pathLabel = new JLabel(strings.pathLabel());
        pathLabel.setLabelFor(pathArea);
        details.add(pathLabel, "span 2, growx");
        configureReadOnlyArea(pathArea, "resourcePacksPath");
        pathArea.setRows(2);
        JScrollPane pathScroll = new JScrollPane(pathArea);
        pathScroll.setName("resourcePacksPathScroll");
        SwingTransparency.revealBackgroundThroughScrollPane(pathScroll);
        details.add(pathScroll, "span 2, growx, hmin 52");

        JLabel descriptionLabel = new JLabel(strings.descriptionLabel());
        descriptionLabel.setLabelFor(descriptionArea);
        details.add(descriptionLabel, "span 2, growx");
        configureReadOnlyArea(descriptionArea, "resourcePacksDescription");
        JScrollPane descriptionScroll = new JScrollPane(descriptionArea);
        descriptionScroll.setName("resourcePacksDescriptionScroll");
        SwingTransparency.revealBackgroundThroughScrollPane(descriptionScroll);
        details.add(descriptionScroll, "span 2, grow");

        JPanel actions = new JPanel(new MigLayout(
                "insets 0, fillx",
                "[grow,fill][][]",
                "[40!]"));
        actions.setName("resourcePacksSelectionActions");
        actions.setOpaque(false);

        enabledToggle.setName("resourcePacksEnabledToggle");
        enabledToggle.setText(strings.enabledLabel());
        enabledToggle.setToolTipText(actionStrings.enableTooltip());
        enabledToggle.getAccessibleContext().setAccessibleDescription(actionStrings.enableTooltip());
        enabledToggle.addActionListener(event -> toggleSelectedResourcePackEnabled());
        actions.add(enabledToggle, "growx, h 40!");

        configureIconButton(
                revealButton,
                "resourcePacksReveal",
                "assets/swing/icons/folder-open.svg",
                actionStrings.revealAction(),
                actionStrings.revealTooltip(),
                this::revealSelectedResourcePack);
        actions.add(revealButton, "w 40!, h 40!");

        configureIconButton(
                deleteButton,
                "resourcePacksDelete",
                "assets/swing/icons/delete.svg",
                actionStrings.deleteAction(),
                actionStrings.deleteTooltip(),
                this::confirmAndDeleteSelectedResourcePack);
        actions.add(deleteButton, "w 40!, h 40!");
        details.add(actions, "span 2, growx");

        JScrollPane detailsScroll = createTransparentScrollPane(details, "resourcePacksDetailsScroll");
        detailsScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        detailsScroll.getVerticalScrollBar().setUnitIncrement(16);
        detailsScroll.setMinimumSize(new Dimension(0, 0));
        return detailsScroll;
    }

    /// Creates sparse-list data callbacks after all listener-dependent fields are initialized.
    ///
    /// @return listener reconciling loaded placeholders and selection details
    private ListDataListener createListDataListener() {
        return new ListDataListener() {
            /// Reconciles an added loaded range.
            @Override
            public void intervalAdded(ListDataEvent event) {
                loadedRowsChanged();
            }

            /// Reconciles a removed or invalidated loaded range.
            @Override
            public void intervalRemoved(ListDataEvent event) {
                loadedRowsChanged();
            }

            /// Reconciles a replaced loaded range.
            @Override
            public void contentsChanged(ListDataEvent event) {
                loadedRowsChanged();
            }
        };
    }

    /// Coalesces a model transition to the latest snapshot on the EDT.
    ///
    /// @param change transition that invalidated displayed state
    private void modelChanged(ValueChange<ResourcePackCatalogSnapshot> change) {
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

    /// Applies the newest model state only when no later update or close superseded it.
    ///
    /// @param requestedRevision queued notification revision
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

    /// Applies one immutable state and invalidates sparse rows only when indexed content changed.
    ///
    /// @param snapshot latest catalog state
    private void applySnapshot(ResourcePackCatalogSnapshot snapshot) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(snapshot, "snapshot");
        @Nullable ResourcePackCatalogSnapshot previous = displayedSnapshot;
        boolean contentChanged = previous == null
                || previous.contentRevision() != snapshot.contentRevision()
                || !previous.itemCount().equals(snapshot.itemCount());
        displayedSnapshot = snapshot;

        applyingSnapshot = true;
        try {
            if (contentChanged) {
                pendingUserSelectionIndex = NO_PENDING_SELECTION;
                choiceList.getList().clearSelection();
                choiceList.reloadData();
                showNoSelection();
            }
            restoreSelection(snapshot.selectedIndex());
        } finally {
            applyingSnapshot = false;
        }

        failedText.setText(failureText(snapshot.statusText()));
        failedText.setCaretPosition(0);
        failedText.setToolTipText(snapshot.statusText());
        ((CardLayout) contentCards.getLayout()).show(contentCards, selectContentCard(snapshot));

        boolean listEnabled = snapshot.status() == ResourcePackCatalogStatus.READY
                && snapshot.listEnabled();
        choiceList.setEnabled(listEnabled);
        choiceList.getList().setEnabled(listEnabled);
        String refreshName = snapshot.status() == ResourcePackCatalogStatus.LOADING
                ? strings.refreshingAction()
                : strings.refreshAction();
        refreshButton.getAccessibleContext().setAccessibleName(refreshName);
        refreshButton.setEnabled(snapshot.refreshEnabled());
        retryButton.setEnabled(snapshot.status() == ResourcePackCatalogStatus.FAILED
                && snapshot.refreshEnabled());
        String visibleStatus = snapshot.writeStatus() == ResourcePackCatalogWriteStatus.IDLE
                ? snapshot.statusText()
                : snapshot.writeStatusText();
        statusText.setText(visibleStatus);
        statusText.setCaretPosition(0);
        statusText.setToolTipText(visibleStatus);
        statusText.getAccessibleContext().setAccessibleName(visibleStatus);
        statusText.getAccessibleContext().setAccessibleDescription(visibleStatus);
        updateSelectionDetails();
    }

    /// Restores the model-selected row without delegating it back as a user command.
    ///
    /// @param selectedIndex selected indexed path, or empty for no selection
    private void restoreSelection(OptionalInt selectedIndex) {
        int targetIndex = selectedIndex.orElse(-1);
        if (targetIndex >= choiceList.getChoiceModel().getSize()) {
            targetIndex = -1;
        }
        if (choiceList.getList().getSelectedIndex() == targetIndex) {
            return;
        }

        pendingUserSelectionIndex = NO_PENDING_SELECTION;
        choiceList.getList().setSelectedIndex(targetIndex);
        if (targetIndex >= 0) {
            choiceList.getList().ensureIndexIsVisible(targetIndex);
        }
        choiceList.refreshLoadPlan();
    }

    /// Reconciles placeholder completion with pending selection and details.
    private void loadedRowsChanged() {
        submitPendingUserSelection();
        updateSelectionDetails();
    }

    /// Delegates one loaded user selection or explicit clear exactly once.
    private void submitPendingUserSelection() {
        EdtDispatcher.requireEventDispatchThread();
        if (applyingSnapshot || pendingUserSelectionIndex == NO_PENDING_SELECTION || !isOpen()) {
            return;
        }
        int selectedIndex = choiceList.getList().getSelectedIndex();
        if (selectedIndex != pendingUserSelectionIndex) {
            return;
        }

        @Nullable ResourcePackCatalogSnapshot displayed = displayedSnapshot;
        ResourcePackCatalogSnapshot current = model.snapshot();
        if (displayed == null
                || current.status() != ResourcePackCatalogStatus.READY
                || !current.listEnabled()
                || current.contentRevision() != displayed.contentRevision()) {
            return;
        }

        if (selectedIndex < 0) {
            pendingUserSelectionIndex = NO_PENDING_SELECTION;
            if (current.selectedIndex().isPresent()) {
                model.clearSelection();
            }
            return;
        }

        @Nullable ResourcePackCatalogItem selected = choiceList.getSelectedValue();
        if (selected == null) {
            return;
        }
        pendingUserSelectionIndex = NO_PENDING_SELECTION;
        if (current.selectedIndex().orElse(-1) != selectedIndex) {
            model.selectResourcePack(selected.path());
        }
    }

    /// Displays every presentation-safe field for the loaded selected row.
    private void updateSelectionDetails() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable ResourcePackCatalogSnapshot snapshot = displayedSnapshot;
        @Nullable ResourcePackCatalogItem selected = snapshot != null
                && snapshot.status() == ResourcePackCatalogStatus.READY
                && snapshot.selectedIndex().isPresent()
                ? choiceList.getSelectedValue()
                : null;
        if (selected == null) {
            showNoSelection();
            updateActionAvailability(null);
            return;
        }

        fileNameValue.setText(selected.fileName());
        fileNameValue.setToolTipText(selected.fileName());
        Path path = selected.path();
        pathArea.setText(path.toString());
        pathArea.setCaretPosition(0);
        descriptionArea.setText(selected.description());
        descriptionArea.setCaretPosition(0);
        compatibilityValue.setText(strings.compatibilityText(selected.compatibility()));
        enabledValue.setText(strings.enabledText(selected.enabled()));
        applyingEnabledToggle = true;
        try {
            enabledToggle.setSelected(selected.enabled());
        } finally {
            applyingEnabledToggle = false;
        }
        updateActionAvailability(selected);
    }

    /// Restores stable read-only detail placeholders for no loaded selection.
    private void showNoSelection() {
        fileNameValue.setText("");
        fileNameValue.setToolTipText(null);
        pathArea.setText("");
        pathArea.setCaretPosition(0);
        descriptionArea.setText(strings.noSelectionText());
        descriptionArea.setCaretPosition(0);
        compatibilityValue.setText("");
        enabledValue.setText("");
        applyingEnabledToggle = true;
        try {
            enabledToggle.setSelected(false);
        } finally {
            applyingEnabledToggle = false;
        }
        updateActionAvailability(null);
    }

    /// Requests a fresh index only while the displayed and authoritative states permit it.
    private void requestRefresh() {
        EdtDispatcher.requireEventDispatchThread();
        if (!isOpen()) {
            return;
        }
        @Nullable ResourcePackCatalogSnapshot displayed = displayedSnapshot;
        ResourcePackCatalogSnapshot current = model.snapshot();
        if (displayed != null
                && displayed.refreshEnabled()
                && current.refreshEnabled()
                && displayed.contentRevision() == current.contentRevision()
                && displayed.status() == current.status()) {
            model.refresh();
        }
    }

    /// Chooses multiple local archives and imports them only if the captured catalog remains current.
    private void chooseAndImportResourcePacks() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable ResourcePackCatalogSnapshot beforeDialog = currentWritableSnapshot();
        if (beforeDialog == null) {
            return;
        }
        long expectedRevision = beforeDialog.contentRevision();
        final @Unmodifiable List<Path> selectedSources;
        try {
            selectedSources = List.copyOf(
                    interactions.chooseImportFiles(this, resourcePackDirectory));
        } catch (RuntimeException failure) {
            showOperationFailure(failure);
            return;
        }
        if (selectedSources.isEmpty() || !isWritableSnapshotCurrent(expectedRevision)) {
            return;
        }
        startWrite(() -> model.importResourcePacks(selectedSources));
    }

    /// Toggles the selected pack through the model after compatibility confirmation when required.
    private void toggleSelectedResourcePackEnabled() {
        EdtDispatcher.requireEventDispatchThread();
        if (applyingEnabledToggle) {
            return;
        }
        @Nullable ResourcePackCatalogSnapshot beforeDialog = currentWritableSnapshot();
        @Nullable ResourcePackCatalogItem selected = selectedActionTarget(beforeDialog);
        if (beforeDialog == null || selected == null) {
            updateSelectionDetails();
            return;
        }

        long expectedRevision = beforeDialog.contentRevision();
        Path expectedPath = selected.path();
        boolean enabling = !selected.enabled();
        applyingEnabledToggle = true;
        try {
            enabledToggle.setSelected(selected.enabled());
        } finally {
            applyingEnabledToggle = false;
        }

        if (enabling && selected.compatibility() != ResourcePackCompatibility.COMPATIBLE) {
            final boolean confirmed;
            try {
                confirmed = interactions.confirmEnableIncompatible(this, selected);
            } catch (RuntimeException failure) {
                showOperationFailure(failure);
                return;
            }
            if (!confirmed) {
                return;
            }
        }
        if (!isSelectedActionCurrent(expectedRevision, expectedPath)) {
            return;
        }
        startWrite(() -> enabling
                ? model.enableResourcePack(expectedPath)
                : model.disableResourcePack(expectedPath));
    }

    /// Permanently deletes the selected pack only after confirmation and stale-state rejection.
    private void confirmAndDeleteSelectedResourcePack() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable ResourcePackCatalogSnapshot beforeDialog = currentWritableSnapshot();
        @Nullable ResourcePackCatalogItem selected = selectedActionTarget(beforeDialog);
        if (beforeDialog == null || selected == null) {
            return;
        }
        long expectedRevision = beforeDialog.contentRevision();
        Path expectedPath = selected.path();
        final boolean confirmed;
        try {
            confirmed = interactions.confirmDelete(this, selected);
        } catch (RuntimeException failure) {
            showOperationFailure(failure);
            return;
        }
        if (confirmed && isSelectedActionCurrent(expectedRevision, expectedPath)) {
            startWrite(() -> model.deleteResourcePack(expectedPath));
        }
    }

    /// Starts one platform reveal without blocking the EDT or permitting duplicate reveals.
    private void revealSelectedResourcePack() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable ResourcePackCatalogSnapshot snapshot = currentWritableSnapshot();
        @Nullable ResourcePackCatalogItem selected = selectedActionTarget(snapshot);
        if (revealPending || snapshot == null || selected == null) {
            return;
        }
        revealPending = true;
        updateActionAvailability(selected);
        try {
            CompletionStage<@Nullable Void> completion = Objects.requireNonNull(
                    interactions.reveal(selected),
                    "interactions.reveal returned null");
            completion.whenComplete((@Nullable Void ignored, @Nullable Throwable failure) ->
                    EdtDispatcher.execute(() -> revealCompleted(failure)));
        } catch (RuntimeException failure) {
            revealCompleted(failure);
        } catch (Error failure) {
            revealPending = false;
            updateSelectionDetails();
            throw failure;
        }
    }

    /// Creates and opens the managed directory without blocking the EDT or accepting double clicks.
    private void openResourcePackDirectory() {
        EdtDispatcher.requireEventDispatchThread();
        if (!canOpenResourcePackDirectory()) {
            return;
        }
        openDirectoryPending = true;
        updateActionAvailability(choiceList.getSelectedValue());
        try {
            CompletionStage<@Nullable Void> completion = Objects.requireNonNull(
                    interactions.openResourcePackDirectory(resourcePackDirectory),
                    "interactions.openResourcePackDirectory returned null");
            completion.whenComplete((@Nullable Void ignored, @Nullable Throwable failure) ->
                    EdtDispatcher.execute(() -> openDirectoryCompleted(failure)));
        } catch (RuntimeException failure) {
            openDirectoryCompleted(failure);
        } catch (Error failure) {
            openDirectoryPending = false;
            updateActionAvailability(choiceList.getSelectedValue());
            throw failure;
        }
    }

    /// Starts and observes one serialized model write, including synchronous validation failures.
    ///
    /// @param operation deferred model write
    private void startWrite(Supplier<CompletionStage<ResourcePackCatalogSnapshot>> operation) {
        EdtDispatcher.requireEventDispatchThread();
        if (writePending || currentWritableSnapshot() == null) {
            return;
        }
        writePending = true;
        synchronized (stateLock) {
            writeStartUpdateRevision = updateRevision;
        }
        updateActionAvailability(choiceList.getSelectedValue());
        try {
            CompletionStage<ResourcePackCatalogSnapshot> completion = Objects.requireNonNull(
                    operation.get(),
                    "resource-pack write returned null");
            completion.whenComplete((
                    @Nullable ResourcePackCatalogSnapshot ignored,
                    @Nullable Throwable failure) -> EdtDispatcher.execute(() -> writeCompleted(failure)));
        } catch (RuntimeException failure) {
            writeCompleted(failure);
        } catch (Error failure) {
            writePending = false;
            updateSelectionDetails();
            throw failure;
        }
    }

    /// Clears the local write gate and reports only failures not already published by the model.
    ///
    /// @param failure asynchronous wrapper or original failure, or null after success
    private void writeCompleted(@Nullable Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        if (!isOpen()) {
            return;
        }
        writePending = false;
        updateSelectionDetails();
        if (failure == null) {
            return;
        }
        Throwable resolved = unwrapCompletionFailure(failure);
        if (!(resolved instanceof CancellationException)
                && !currentWriteFailureWasPublished()) {
            interactions.showFailure(
                    this,
                    actionStrings.operationFailedTitle(),
                    failureText(resolved));
        }
    }

    /// Detects a model-published terminal error belonging to the current panel-started write.
    ///
    /// A retained error from an earlier write must not suppress a new synchronous validation or
    /// custom-stage failure that produced no model notification.
    ///
    /// @return whether this write published a terminal error snapshot
    private boolean currentWriteFailureWasPublished() {
        long currentUpdateRevision;
        synchronized (stateLock) {
            currentUpdateRevision = updateRevision;
        }
        return currentUpdateRevision > writeStartUpdateRevision
                && model.snapshot().writeStatus() == ResourcePackCatalogWriteStatus.ERROR;
    }

    /// Clears the reveal gate and reports a non-cancellation failure while the panel remains open.
    ///
    /// @param failure asynchronous wrapper or original failure, or null after success
    private void revealCompleted(@Nullable Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        if (!isOpen()) {
            return;
        }
        revealPending = false;
        updateSelectionDetails();
        if (failure != null) {
            Throwable resolved = unwrapCompletionFailure(failure);
            if (!(resolved instanceof CancellationException)) {
                interactions.showFailure(
                        this,
                        actionStrings.revealFailedTitle(),
                        failureText(resolved));
            }
        }
    }

    /// Clears the directory gate and reports a non-cancellation failure while still open.
    ///
    /// @param failure asynchronous wrapper or original failure, or null after success
    private void openDirectoryCompleted(@Nullable Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        if (!isOpen()) {
            return;
        }
        openDirectoryPending = false;
        updateActionAvailability(choiceList.getSelectedValue());
        if (failure != null) {
            Throwable resolved = unwrapCompletionFailure(failure);
            if (!(resolved instanceof CancellationException)) {
                interactions.showFailure(
                        this,
                        actionStrings.openDirectoryFailedTitle(),
                        failureText(resolved));
            }
        }
    }

    /// Reports one synchronous dialog or command failure unless it represents cancellation.
    ///
    /// @param failure original interaction failure
    private void showOperationFailure(Throwable failure) {
        if (!isOpen()) {
            return;
        }
        Throwable resolved = unwrapCompletionFailure(failure);
        if (!(resolved instanceof CancellationException)) {
            interactions.showFailure(
                    this,
                    actionStrings.operationFailedTitle(),
                    failureText(resolved));
        }
    }

    /// Updates every action from the displayed state, authoritative state, and local pending gates.
    ///
    /// @param selected loaded selected row, or null while no row is ready
    private void updateActionAvailability(@Nullable ResourcePackCatalogItem selected) {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable ResourcePackCatalogSnapshot writable = currentWritableSnapshot();
        boolean selectedCurrent = writable != null
                && selected != null
                && isSelectedActionCurrent(writable.contentRevision(), selected.path());
        importButton.setEnabled(writable != null);
        enabledToggle.setEnabled(selectedCurrent);
        revealButton.setEnabled(selectedCurrent && !revealPending);
        deleteButton.setEnabled(selectedCurrent);
        openDirectoryButton.setEnabled(canOpenResourcePackDirectory());

        boolean enabling = selected == null || !selected.enabled();
        String toggleName = enabling
                ? actionStrings.enableAction()
                : actionStrings.disableAction();
        String toggleTooltip = enabling
                ? actionStrings.enableTooltip()
                : actionStrings.disableTooltip();
        enabledToggle.getAccessibleContext().setAccessibleName(toggleName);
        enabledToggle.getAccessibleContext().setAccessibleDescription(toggleTooltip);
        enabledToggle.setToolTipText(toggleTooltip);
    }

    /// Returns the displayed snapshot only when it still denotes the exact writable catalog.
    ///
    /// @return current writable snapshot, or null
    private @Nullable ResourcePackCatalogSnapshot currentWritableSnapshot() {
        if (!isOpen() || writePending) {
            return null;
        }
        @Nullable ResourcePackCatalogSnapshot displayed = displayedSnapshot;
        if (displayed == null
                || displayed.status() != ResourcePackCatalogStatus.READY
                || displayed.itemCount().isEmpty()
                || displayed.writeStatus() == ResourcePackCatalogWriteStatus.BUSY) {
            return null;
        }
        ResourcePackCatalogSnapshot current = model.snapshot();
        return current.status() == ResourcePackCatalogStatus.READY
                && current.itemCount().isPresent()
                && current.writeStatus() != ResourcePackCatalogWriteStatus.BUSY
                && current.contentRevision() == displayed.contentRevision()
                && current.itemCount().equals(displayed.itemCount())
                ? displayed
                : null;
    }

    /// Checks whether one modal result still targets the exact writable catalog revision.
    ///
    /// @param expectedRevision captured content revision
    /// @return whether the captured catalog remains writable and current
    private boolean isWritableSnapshotCurrent(long expectedRevision) {
        @Nullable ResourcePackCatalogSnapshot current = currentWritableSnapshot();
        return current != null && current.contentRevision() == expectedRevision;
    }

    /// Returns the loaded selection only when both displayed and model selection agree.
    ///
    /// @param snapshot current writable snapshot, or null
    /// @return exact loaded target, or null
    private @Nullable ResourcePackCatalogItem selectedActionTarget(
            @Nullable ResourcePackCatalogSnapshot snapshot) {
        if (snapshot == null || snapshot.selectedIndex().isEmpty()) {
            return null;
        }
        int selectedIndex = choiceList.getList().getSelectedIndex();
        if (selectedIndex != snapshot.selectedIndex().getAsInt()) {
            return null;
        }
        @Nullable ResourcePackCatalogItem selected = choiceList.getSelectedValue();
        ResourcePackCatalogSnapshot current = model.snapshot();
        return selected != null
                && current.contentRevision() == snapshot.contentRevision()
                && current.selectedIndex().orElse(-1) == selectedIndex
                ? selected
                : null;
    }

    /// Checks whether one captured selected path still owns the exact current selection.
    ///
    /// @param expectedRevision captured content revision
    /// @param expectedPath captured selected path
    /// @return whether the exact selected action remains legal
    private boolean isSelectedActionCurrent(long expectedRevision, Path expectedPath) {
        @Nullable ResourcePackCatalogSnapshot snapshot = currentWritableSnapshot();
        @Nullable ResourcePackCatalogItem selected = selectedActionTarget(snapshot);
        return snapshot != null
                && snapshot.contentRevision() == expectedRevision
                && selected != null
                && selected.path().equals(expectedPath);
    }

    /// Returns whether opening the managed directory is legal for the latest displayed state.
    ///
    /// @return whether the directory command may start
    private boolean canOpenResourcePackDirectory() {
        @Nullable ResourcePackCatalogSnapshot snapshot = displayedSnapshot;
        return isOpen()
                && !openDirectoryPending
                && !writePending
                && snapshot != null
                && snapshot.writeStatus() != ResourcePackCatalogWriteStatus.BUSY;
    }

    /// Releases every owned resource on the EDT while attempting all cleanup steps.
    private void closeOnEventDispatchThread() {
        EdtDispatcher.requireEventDispatchThread();
        synchronized (publicationLock) {
            if (resourcesClosed) {
                return;
            }
            resourcesClosed = true;
            @Nullable Throwable failure = null;
            failure = attemptCleanup(failure, modelSubscription::unsubscribe);
            failure = attemptCleanup(
                    failure,
                    () -> choiceList.getChoiceModel().removeListDataListener(listDataListener));
            failure = attemptCleanup(
                    failure,
                    () -> choiceList.getList().removeListSelectionListener(selectionListener));
            failure = attemptCleanup(failure, () -> removeHierarchyListener(showingListener));
            failure = attemptCleanup(failure, () -> refreshButton.setEnabled(false));
            failure = attemptCleanup(failure, () -> retryButton.setEnabled(false));
            failure = attemptCleanup(failure, () -> importButton.setEnabled(false));
            failure = attemptCleanup(failure, () -> openDirectoryButton.setEnabled(false));
            failure = attemptCleanup(failure, () -> enabledToggle.setEnabled(false));
            failure = attemptCleanup(failure, () -> revealButton.setEnabled(false));
            failure = attemptCleanup(failure, () -> deleteButton.setEnabled(false));
            failure = attemptCleanup(failure, choiceList::close);
            failure = attemptCleanup(failure, model::close);
            throwUncheckedFailure(failure);
        }
    }

    /// Returns whether commands and queued updates may still reach the owned model.
    ///
    /// @return whether the panel remains open
    private boolean isOpen() {
        synchronized (stateLock) {
            return !closed;
        }
    }

    /// Selects the state card for one exact catalog snapshot.
    ///
    /// @param snapshot current catalog state
    /// @return stable card identifier
    private static String selectContentCard(ResourcePackCatalogSnapshot snapshot) {
        return switch (snapshot.status()) {
            case IDLE -> IDLE_CARD;
            case LOADING -> LOADING_CARD;
            case FAILED -> FAILED_CARD;
            case UNSUPPORTED -> UNSUPPORTED_CARD;
            case READY -> snapshot.itemCount().orElse(0) == 0 ? EMPTY_CARD : LIST_CARD;
        };
    }

    /// Formats a failed-card heading while preserving the model's complete diagnostic text.
    ///
    /// @param statusText model-provided localized status and optional detail
    /// @return visible failure text
    private String failureText(String statusText) {
        return statusText.isBlank() ? strings.failureTitle() : statusText;
    }

    /// Removes asynchronous completion wrappers while preserving the original failure identity.
    ///
    /// @param failure asynchronous wrapper or original failure
    /// @return deepest wrapped failure
    private static Throwable unwrapCompletionFailure(Throwable failure) {
        Throwable current = Objects.requireNonNull(failure, "failure");
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = Objects.requireNonNull(current.getCause());
        }
        return current;
    }

    /// Produces non-blank user-facing failure detail without inventing operation context.
    ///
    /// @param failure resolved failure
    /// @return message or failure type when no message exists
    private static String failureText(Throwable failure) {
        @Nullable String message = Objects.requireNonNull(failure, "failure").getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message;
    }

    /// Configures one fixed-size icon command with visible tooltip and accessible text.
    ///
    /// @param button command button
    /// @param name stable component name
    /// @param iconPath bundled SVG icon path
    /// @param accessibleName localized command name
    /// @param tooltip localized command description
    /// @param action command callback
    private static void configureIconButton(
            JButton button,
            String name,
            String iconPath,
            String accessibleName,
            String tooltip,
            Runnable action) {
        button.setName(name);
        button.setText(null);
        button.setIcon(new FlatSVGIcon(iconPath, 18, 18));
        button.setToolTipText(tooltip);
        button.getAccessibleContext().setAccessibleName(accessibleName);
        button.getAccessibleContext().setAccessibleDescription(tooltip);
        button.addActionListener(event -> action.run());
    }

    /// Configures one selectable read-only multiline value.
    ///
    /// @param area text component to configure
    /// @param name stable component name
    private static void configureReadOnlyArea(JTextArea area, String name) {
        area.setName(name);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setBorder(BorderFactory.createEmptyBorder());
    }

    /// Creates one wrapping lifecycle text component with a stable name.
    ///
    /// @param name stable component name
    /// @return transparent state text
    private static JTextArea stateText(String name) {
        JTextArea area = new JTextArea();
        area.setName(name);
        area.setEditable(false);
        area.setFocusable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setBorder(BorderFactory.createEmptyBorder());
        area.setRows(1);
        area.setColumns(1);
        return area;
    }

    /// Centers one wrapping state text inside an unframed lifecycle card.
    ///
    /// @param text state text component
    /// @return state card
    private static JPanel createStateCard(JTextArea text) {
        JPanel card = new JPanel(new MigLayout("insets 24, fill", "[grow,fill]", "[grow,center]"));
        card.setOpaque(false);
        card.add(text, "growx");
        return card;
    }

    /// Wraps one component in a transparent borderless scroll surface.
    ///
    /// @param component scrollable content
    /// @param name stable scroll-pane name
    /// @return configured scroll pane
    private static JScrollPane createTransparentScrollPane(JComponent component, String name) {
        JScrollPane scrollPane = new JScrollPane(component);
        scrollPane.setName(name);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
        return scrollPane;
    }

    /// Executes one cleanup step and accumulates unchecked failures without skipping later steps.
    ///
    /// @param prior prior cleanup failure, or null
    /// @param cleanup cleanup step
    /// @return accumulated failure, or null when every attempted step succeeded
    private static @Nullable Throwable attemptCleanup(
            @Nullable Throwable prior,
            Runnable cleanup) {
        try {
            cleanup.run();
            return prior;
        } catch (RuntimeException | Error failure) {
            return combineUncheckedFailures(prior, failure);
        }
    }

    /// Adds one unchecked cleanup failure to an existing failure chain.
    ///
    /// @param prior prior failure, or null
    /// @param failure new unchecked failure
    /// @return root accumulated failure
    private static Throwable combineUncheckedFailures(
            @Nullable Throwable prior,
            Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        if (prior == null) {
            return failure;
        }
        if (prior != failure) {
            prior.addSuppressed(failure);
        }
        return prior;
    }

    /// Rethrows one accumulated runtime failure or error without changing its type.
    ///
    /// @param failure accumulated failure, or null
    private static void throwUncheckedFailure(@Nullable Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw new IllegalStateException("Unexpected checked cleanup failure", failure);
        }
    }

    /// Switches the catalog between side-by-side and stacked layouts from actual allocated width.
    @NotNullByDefault
    private static final class ResponsiveCatalogSplitPane extends JSplitPane {
        /// Whether the divider ratio has been initialized for the current orientation.
        private boolean orientationInitialized;

        /// Creates a borderless responsive split using stable list and details components.
        ///
        /// @param list viewport-driven list
        /// @param details read-only details surface
        private ResponsiveCatalogSplitPane(
                ViewportChoiceList<ResourcePackCatalogItem> list,
                JComponent details) {
            super(JSplitPane.HORIZONTAL_SPLIT, list, details);
            setName("resourcePacksCatalogSplit");
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder());
            setContinuousLayout(true);
            setResizeWeight(0.42D);
        }

        /// Selects an orientation from the page width that the shell actually allocated.
        ///
        /// @param availableWidth allocated page width
        private void updateForAvailableWidth(int availableWidth) {
            boolean horizontal = availableWidth >= WIDE_LAYOUT_MINIMUM_WIDTH;
            int desiredOrientation = horizontal ? HORIZONTAL_SPLIT : VERTICAL_SPLIT;
            boolean orientationChanged = getOrientation() != desiredOrientation;
            if (orientationChanged) {
                setOrientation(desiredOrientation);
                orientationInitialized = false;
            }
            setResizeWeight(horizontal ? 0.42D : 0.48D);
        }

        /// Lays out children and initializes the divider ratio for the selected orientation.
        @Override
        public void doLayout() {
            boolean horizontal = getOrientation() == HORIZONTAL_SPLIT;
            if (!orientationInitialized) {
                int extent = horizontal ? getWidth() : getHeight();
                int usableExtent = extent - getDividerSize();
                if (usableExtent > 1) {
                    double ratio = horizontal ? 0.42D : 0.48D;
                    setDividerLocation((int) Math.round(usableExtent * ratio));
                    orientationInitialized = true;
                }
            }
            super.doLayout();
        }

        /// Allows the shell to allocate widths below the side-by-side breakpoint.
        ///
        /// @return zero-width minimum retaining the normal split height
        @Override
        public Dimension getMinimumSize() {
            return new Dimension(0, super.getMinimumSize().height);
        }
    }
}

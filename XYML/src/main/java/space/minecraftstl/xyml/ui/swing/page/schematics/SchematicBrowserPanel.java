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
package space.minecraftstl.xyml.ui.swing.page.schematics;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.schematic.LitematicFile;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingTransparency;
import space.minecraftstl.xyml.ui.swing.choice.ChoiceListEntry;
import space.minecraftstl.xyml.ui.swing.choice.ViewportChoiceList;
import space.minecraftstl.xyml.ui.swing.shell.ShellFileDropHandler;
import space.minecraftstl.xyml.util.i18n.I18n;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/// Swing schematic browser backed by a shallow, viewport-driven toolkit-neutral model.
///
/// Construction must occur on the EDT and performs no model loading. [#start()] or the first
/// display notification starts lazy loading. Ownership of the supplied model transfers to this
/// panel: [#close()] synchronously releases the subscription, viewport list, and model on the EDT.
/// All worker-published model changes are coalesced and applied through [EdtDispatcher].
@NotNullByDefault
public final class SchematicBrowserPanel extends JPanel implements AutoCloseable {
    /// Card shown before loading and while a directory scan is active.
    private static final String LOADING_CARD = "loading";

    /// Card shown when the latest directory scan failed.
    private static final String ERROR_CARD = "error";

    /// Card shown for an exact empty directory.
    private static final String EMPTY_CARD = "empty";

    /// Card containing the viewport list and selected-item details.
    private static final String BROWSER_CARD = "browser";

    /// Lock protecting close state, lazy start, and queued model update revisions.
    private final Object stateLock = new Object();

    /// Serializes an EDT snapshot application with close cleanup.
    private final Object publicationLock = new Object();

    /// Owned toolkit-neutral schematic browser model.
    private final SchematicBrowserModel model;

    /// Localized browser text.
    private final SchematicBrowserStrings strings;

    /// Dialog and platform interaction boundary supplied by the application.
    private final SchematicBrowserInteractions interactions;

    /// Viewport-measured single-choice directory listing.
    private final ViewportChoiceList<SchematicBrowserItem> choiceList;

    /// State cards for loading, failure, empty, and populated directories.
    private final JPanel contentCards = new JPanel(new CardLayout());

    /// Parent-directory toolbar command.
    private final JButton returnButton = new JButton();

    /// Current-directory refresh command.
    private final JButton refreshButton = new JButton();

    /// Multi-file import command.
    private final JButton importButton = new JButton();

    /// Direct child-directory creation command.
    private final JButton createDirectoryButton = new JButton();

    /// Selected child-directory navigation command.
    private final JButton openDirectoryButton = new JButton();

    /// Selected-row platform reveal command.
    private final JButton revealButton = new JButton();

    /// Selected-row deletion command.
    private final JButton deleteButton = new JButton();

    /// Responsive toolbar action owner, including the dynamic refresh label.
    private final ResponsiveActionStrip toolbarActions = new ResponsiveActionStrip();

    /// Failed directory scan retry command.
    private final JButton retryButton = new JButton();

    /// Loading or idle state text.
    private final JLabel loadingLabel = stateLabel("schematicsLoading");

    /// Exact empty-directory state text.
    private final JLabel emptyLabel = stateLabel("schematicsEmpty");

    /// Latest scan failure text.
    private final JLabel errorLabel = stateLabel("schematicsError");

    /// Current path and lifecycle status.
    private final JLabel statusLabel = new JLabel();

    /// Heading for the selected-row detail region.
    private final JLabel detailsHeading = new JLabel();

    /// Read-only responsive metadata and parse-error text.
    private final JTextArea detailsArea = new JTextArea();

    /// Owned model listener registration.
    private final Subscription modelSubscription;

    /// Page-scoped filtered route for local Litematica files.
    private final ShellFileDropHandler.RouteRegistration dropRegistration;

    /// Listener that updates details after a selected placeholder row finishes loading.
    private final ListDataListener listDataListener = new ListDataListener() {
        /// Reconciles a newly loaded selected row.
        @Override
        public void intervalAdded(ListDataEvent event) {
            updateSelectionPresentation();
        }

        /// Reconciles a removed selected row.
        @Override
        public void intervalRemoved(ListDataEvent event) {
            updateSelectionPresentation();
        }

        /// Reconciles a changed selected row.
        @Override
        public void contentsChanged(ListDataEvent event) {
            updateSelectionPresentation();
        }
    };

    /// Double-click navigation that opens only a loaded directory row under the pointer.
    private final MouseAdapter directoryOpenMouseListener = new MouseAdapter() {
        /// Opens a loaded directory on a primary-button double click.
        ///
        /// @param event list mouse event
        @Override
        public void mouseClicked(MouseEvent event) {
            if (event.getButton() != MouseEvent.BUTTON1 || event.getClickCount() != 2 || !isOpen()) {
                return;
            }
            JList<ChoiceListEntry<SchematicBrowserItem>> list = choiceList.getList();
            int index = list.locationToIndex(event.getPoint());
            @Nullable Rectangle bounds = index < 0 ? null : list.getCellBounds(index, index);
            if (bounds != null && bounds.contains(event.getPoint())) {
                list.setSelectedIndex(index);
                openSelectedDirectory();
            }
        }
    };

    /// Snapshot currently represented by Swing controls, or null before initialization.
    private @Nullable SchematicBrowserSnapshot displayedSnapshot;

    /// Monotonic notification revision used to discard queued stale EDT updates.
    private long updateRevision;

    /// Whether initial lazy loading has been requested.
    private boolean initialLoadRequested;

    /// Whether all future model notifications and commands are gated.
    private boolean closed;

    /// Whether owned Swing and model resources have been released on the EDT.
    private boolean resourcesClosed;

    /// Whether one platform reveal completion remains outstanding.
    private boolean revealPending;

    /// Creates a browser on the EDT and takes ownership of its model without starting I/O.
    ///
    /// @param model owned shallow schematic model
    /// @param strings localized browser and metadata text
    /// @param interactions application-owned dialog and desktop interaction boundary
    public SchematicBrowserPanel(
            SchematicBrowserModel model,
            SchematicBrowserStrings strings,
            SchematicBrowserInteractions interactions) {
        super(new MigLayout(
                "insets 0, fill, wrap 1",
                "[grow,fill]",
                "[]12[grow,fill]10[]"));
        EdtDispatcher.requireEventDispatchThread();
        this.model = Objects.requireNonNull(model, "model");
        this.strings = Objects.requireNonNull(strings, "strings");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        choiceList = new ViewportChoiceList<>(model, this::rowText);

        configureComponents();
        modelSubscription = model.subscribe(this::modelChanged);
        applySnapshot(model.snapshot());
        dropRegistration = ShellFileDropHandler.registerFiles(
                this,
                this::supportsDroppedSchematic,
                this::importDroppedFiles);
    }

    /// Returns the immutable snapshot currently represented by the panel.
    ///
    /// @return displayed browser snapshot
    public SchematicBrowserSnapshot displayedSnapshot() {
        EdtDispatcher.requireEventDispatchThread();
        return Objects.requireNonNull(displayedSnapshot, "initial schematic snapshot was not applied");
    }

    /// Returns the viewport-driven list for shell integration and focused verification.
    ///
    /// @return viewport choice list
    public ViewportChoiceList<SchematicBrowserItem> choiceList() {
        return choiceList;
    }

    /// Returns the exact details text currently visible to assistive technology and tests.
    ///
    /// @return current selected-row details
    public String displayedDetailsText() {
        EdtDispatcher.requireEventDispatchThread();
        return detailsArea.getText();
    }

    /// Starts the initial lazy directory scan exactly once on the EDT.
    public void start() {
        EdtDispatcher.requireEventDispatchThread();
        synchronized (stateLock) {
            if (closed || initialLoadRequested) {
                return;
            }
            initialLoadRequested = true;
        }
        model.loadIfNeeded();
    }

    /// Starts lazy loading after the panel first becomes displayable.
    @Override
    public void addNotify() {
        super.addNotify();
        start();
    }

    /// Gates late callbacks and synchronously releases all owned resources on the EDT.
    @Override
    public void close() {
        synchronized (stateLock) {
            if (!closed) {
                closed = true;
                updateRevision++;
            }
        }
        EdtDispatcher.executeAndWait(this::closeOnEventDispatchThread);
    }

    /// Builds the toolbar, lifecycle cards, responsive browser split, and status band.
    private void configureComponents() {
        setOpaque(false);
        JPanel toolbar = new JPanel(new MigLayout(
                "insets 0, fillx, wrap 1",
                "[grow,fill]",
                "[]8[40!]"));
        toolbar.setOpaque(false);

        JLabel heading = new JLabel(strings.pageTitle());
        heading.setName("schematicsPageTitle");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 28.0F));
        toolbar.add(heading, "growx");

        configureActionButton(
                returnButton,
                "schematicsReturn",
                strings.returnAction(),
                strings.returnTooltip(),
                "assets/swing/icons/arrow-back.svg");
        returnButton.addActionListener(event -> {
            if (canReturnToParent()) {
                model.returnToParent();
            }
        });
        toolbarActions.addAction(returnButton);

        configureActionButton(
                refreshButton,
                "schematicsRefresh",
                strings.refreshAction(),
                strings.refreshTooltip(),
                "assets/swing/icons/refresh.svg");
        refreshButton.addActionListener(event -> {
            if (canRefresh()) {
                model.refresh();
            }
        });
        toolbarActions.addAction(refreshButton);

        SchematicBrowserActionStrings actions = strings.actions();
        configureActionButton(
                importButton,
                "schematicsImport",
                actions.importAction(),
                actions.importTooltip(),
                "assets/swing/icons/file-import.svg");
        importButton.addActionListener(event -> chooseAndImportFiles());
        toolbarActions.addAction(importButton);

        configureActionButton(
                createDirectoryButton,
                "schematicsCreateDirectory",
                actions.createDirectoryAction(),
                actions.createDirectoryTooltip(),
                "assets/swing/icons/create-new-folder.svg");
        createDirectoryButton.addActionListener(event -> promptAndCreateDirectory());
        toolbarActions.addAction(createDirectoryButton);
        toolbar.add(toolbarActions, "growx, wmin 0, h 40!");
        add(toolbar, "growx");

        JPanel errorPanel = new JPanel(new MigLayout(
                "insets 24, fill, wrap 1",
                "[grow,center]",
                "[grow,center]12[]"));
        errorPanel.setOpaque(false);
        errorPanel.add(errorLabel, "growx");
        retryButton.setName("schematicsRetry");
        retryButton.setText(strings.retryAction());
        retryButton.getAccessibleContext().setAccessibleName(strings.retryAction());
        retryButton.getAccessibleContext().setAccessibleDescription(strings.errorTitle());
        retryButton.addActionListener(event -> {
            if (canRetry()) {
                model.refresh();
            }
        });
        errorPanel.add(retryButton, "h 40!");

        choiceList.setName("schematicsList");
        SwingTransparency.revealBackgroundThroughScrollPane(choiceList);
        JList<ChoiceListEntry<SchematicBrowserItem>> list = choiceList.getList();
        list.setName("schematicsListView");
        list.setOpaque(false);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                updateSelectionPresentation();
            }
        });
        list.addMouseListener(directoryOpenMouseListener);
        choiceList.getChoiceModel().addListDataListener(listDataListener);

        JPanel detailsPanel = new JPanel(new MigLayout(
                "insets 12, fill, wrap 1",
                "[grow,fill]",
                "[]8[grow,fill]8[]"));
        detailsPanel.setName("schematicsDetailsPanel");
        detailsPanel.setOpaque(false);
        detailsHeading.setName("schematicsDetailsTitle");
        detailsHeading.setText(strings.detailsTitle());
        detailsHeading.setFont(detailsHeading.getFont().deriveFont(Font.BOLD));
        detailsPanel.add(detailsHeading, "growx");

        detailsArea.setName("schematicsDetailsText");
        detailsArea.setEditable(false);
        detailsArea.setLineWrap(true);
        detailsArea.setWrapStyleWord(true);
        detailsArea.setOpaque(false);
        detailsArea.setBorder(BorderFactory.createEmptyBorder());
        detailsArea.setText(strings.noSelectionText());
        JScrollPane detailsScroll = new JScrollPane(detailsArea);
        detailsScroll.setName("schematicsDetailsScroll");
        SwingTransparency.revealBackgroundThroughScrollPane(detailsScroll);
        detailsPanel.add(detailsScroll, "grow");

        ResponsiveActionStrip selectedActions = new ResponsiveActionStrip();
        configureActionButton(
                openDirectoryButton,
                "schematicsOpenDirectory",
                strings.openDirectoryAction(),
                strings.openDirectoryTooltip(),
                "assets/swing/icons/arrow-forward.svg");
        openDirectoryButton.addActionListener(event -> openSelectedDirectory());
        selectedActions.addAction(openDirectoryButton);

        configureActionButton(
                revealButton,
                "schematicsReveal",
                actions.revealAction(),
                actions.revealTooltip(),
                "assets/swing/icons/folder-open.svg");
        revealButton.addActionListener(event -> revealSelectedItem());
        selectedActions.addAction(revealButton);

        configureActionButton(
                deleteButton,
                "schematicsDelete",
                actions.deleteAction(),
                actions.deleteTooltip(),
                "assets/swing/icons/delete.svg");
        deleteButton.addActionListener(event -> confirmAndDeleteSelectedItem());
        selectedActions.addAction(deleteButton);
        detailsPanel.add(selectedActions, "growx, wmin 0, h 40!");

        JSplitPane browserSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, choiceList, detailsPanel);
        browserSplit.setName("schematicsBrowserSplit");
        browserSplit.setOpaque(false);
        browserSplit.setBorder(BorderFactory.createEmptyBorder());
        browserSplit.setContinuousLayout(true);
        browserSplit.setResizeWeight(0.62D);

        loadingLabel.setText(strings.idleText());
        emptyLabel.setText(strings.emptyText());
        contentCards.add(loadingLabel, LOADING_CARD);
        contentCards.add(errorPanel, ERROR_CARD);
        contentCards.add(emptyLabel, EMPTY_CARD);
        contentCards.add(browserSplit, BROWSER_CARD);
        contentCards.setOpaque(false);
        add(contentCards, "grow");

        statusLabel.setName("schematicsStatus");
        statusLabel.getAccessibleContext().setAccessibleName("");
        statusLabel.getAccessibleContext().setAccessibleDescription("");
        add(statusLabel, "growx, h 28!");
    }

    /// Coalesces a model transition to the latest state on the EDT.
    ///
    /// @param change transition that invalidated displayed state
    private void modelChanged(ValueChange<SchematicBrowserSnapshot> change) {
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

    /// Applies one state and invalidates viewport content only when its indexed identity changed.
    ///
    /// @param snapshot latest browser state
    private void applySnapshot(SchematicBrowserSnapshot snapshot) {
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(snapshot, "snapshot");
        @Nullable SchematicBrowserSnapshot previous = displayedSnapshot;
        boolean contentChanged = previous == null
                || previous.contentRevision() != snapshot.contentRevision()
                || !previous.itemCount().equals(snapshot.itemCount())
                || !previous.currentDirectory().equals(snapshot.currentDirectory());
        displayedSnapshot = snapshot;

        if (contentChanged) {
            choiceList.getList().clearSelection();
            choiceList.reloadData();
            showNoSelection();
        }

        loadingLabel.setText(snapshot.status() == SchematicBrowserStatus.IDLE
                ? strings.idleText()
                : strings.loadingText());
        @Nullable String failureMessage = snapshot.failureMessage();
        errorLabel.setText(failureMessage == null || failureMessage.isBlank()
                ? strings.errorTitle()
                : strings.errorTitle() + ": " + failureMessage);
        errorLabel.setToolTipText(errorLabel.getText());
        emptyLabel.setText(strings.emptyText());
        ((CardLayout) contentCards.getLayout()).show(contentCards, selectContentCard(snapshot));

        boolean loading = snapshot.status() == SchematicBrowserStatus.LOADING
                || snapshot.status() == SchematicBrowserStatus.IDLE;
        boolean writeBusy = snapshot.writeStatus() == SchematicBrowserWriteStatus.BUSY;
        boolean exactReady = hasExactReadyListing(snapshot);
        returnButton.setEnabled(!loading && !writeBusy && snapshot.canReturnToParent());
        toolbarActions.setActionText(
                refreshButton,
                loading ? strings.refreshingAction() : strings.refreshAction());
        refreshButton.setEnabled(!loading && !writeBusy);
        importButton.setEnabled(exactReady && !writeBusy);
        createDirectoryButton.setEnabled(exactReady && !writeBusy);
        retryButton.setEnabled(snapshot.status() == SchematicBrowserStatus.ERROR && !writeBusy);
        choiceList.setEnabled(snapshot.status() == SchematicBrowserStatus.READY && !writeBusy);
        choiceList.getList().setEnabled(snapshot.status() == SchematicBrowserStatus.READY && !writeBusy);
        updateSelectionPresentation();

        String statusText = statusText(snapshot, failureMessage);
        String statusDescription = statusDescription(snapshot, statusText);
        statusLabel.setText(statusText);
        statusLabel.setToolTipText(statusDescription);
        statusLabel.getAccessibleContext().setAccessibleName(statusText);
        statusLabel.getAccessibleContext().setAccessibleDescription(statusDescription);
    }

    /// Opens the selected loaded directory while commands remain enabled.
    private void openSelectedDirectory() {
        EdtDispatcher.requireEventDispatchThread();
        if (!hasExactReadyListingForActions()) {
            return;
        }
        @Nullable SchematicBrowserItem selected = choiceList.getSelectedValue();
        if (selected instanceof SchematicDirectoryItem directory) {
            model.openDirectory(directory.path());
        }
    }

    /// Chooses source files and starts an import only if the modal result still targets the same directory.
    private void chooseAndImportFiles() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable SchematicBrowserSnapshot beforeDialog = currentActionSnapshot();
        if (beforeDialog == null) {
            return;
        }
        Path expectedDirectory = beforeDialog.currentDirectory();
        final @Unmodifiable List<Path> selectedFiles;
        try {
            selectedFiles = List.copyOf(interactions.chooseImportFiles(this, expectedDirectory));
        } catch (RuntimeException failure) {
            showOperationFailure(failure);
            return;
        }
        if (selectedFiles.isEmpty() || !isActionDirectoryCurrent(expectedDirectory)) {
            return;
        }
        startWrite(() -> model.importFiles(selectedFiles));
    }

    /// Returns whether the current writable directory accepts one dropped Litematica file.
    ///
    /// @param source normalized dropped path
    /// @return whether the source is a regular `.litematic` file and the directory is writable
    private boolean supportsDroppedSchematic(Path source) {
        @Nullable Path fileName = Objects.requireNonNull(source, "source").getFileName();
        return currentActionSnapshot() != null
                && Files.isRegularFile(source)
                && fileName != null
                && fileName.toString().toLowerCase(Locale.ROOT).endsWith(".litematic");
    }

    /// Imports accepted dropped files when the captured directory remains current.
    ///
    /// @param sources immutable accepted files in transfer order
    private void importDroppedFiles(@Unmodifiable List<Path> sources) {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable SchematicBrowserSnapshot snapshot = currentActionSnapshot();
        if (snapshot != null && !sources.isEmpty() && isActionDirectoryCurrent(snapshot.currentDirectory())) {
            startWrite(() -> model.importFiles(sources));
        }
    }

    /// Prompts for a child name and starts creation only if the modal result remains current.
    private void promptAndCreateDirectory() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable SchematicBrowserSnapshot beforeDialog = currentActionSnapshot();
        if (beforeDialog == null) {
            return;
        }
        Path expectedDirectory = beforeDialog.currentDirectory();
        final @Nullable String directoryName;
        try {
            directoryName = interactions.promptDirectoryName(this);
        } catch (RuntimeException failure) {
            showOperationFailure(failure);
            return;
        }
        if (directoryName == null || !isActionDirectoryCurrent(expectedDirectory)) {
            return;
        }
        startWrite(() -> model.createDirectory(directoryName));
    }

    /// Confirms deletion and submits only the path captured before opening the modal dialog.
    private void confirmAndDeleteSelectedItem() {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable SchematicBrowserSnapshot beforeDialog = currentActionSnapshot();
        @Nullable SchematicBrowserItem selected = choiceList.getSelectedValue();
        if (beforeDialog == null || selected == null) {
            return;
        }
        Path expectedDirectory = beforeDialog.currentDirectory();
        Path capturedPath = selected.path();
        final boolean confirmed;
        try {
            confirmed = interactions.confirmDelete(this, selected);
        } catch (RuntimeException failure) {
            showOperationFailure(failure);
            return;
        }
        if (!confirmed || !isSelectedActionCurrent(expectedDirectory, capturedPath)) {
            return;
        }
        startWrite(() -> model.delete(capturedPath));
    }

    /// Starts one asynchronous platform reveal while keeping unrelated browser commands available.
    private void revealSelectedItem() {
        EdtDispatcher.requireEventDispatchThread();
        if (revealPending || !hasExactReadyListingForActions()) {
            return;
        }
        @Nullable SchematicBrowserItem selected = choiceList.getSelectedValue();
        if (selected == null) {
            return;
        }
        revealPending = true;
        updateSelectionPresentation();
        final CompletionStage<@Nullable Void> completion;
        try {
            completion = Objects.requireNonNull(
                    interactions.reveal(selected),
                    "interactions.reveal returned null");
        } catch (RuntimeException failure) {
            revealCompleted(failure);
            return;
        }
        completion.whenComplete((@Nullable Void ignored, @Nullable Throwable failure) ->
                EdtDispatcher.execute(() -> revealCompleted(failure)));
    }

    /// Starts and observes one model mutation, including synchronous validation failures.
    ///
    /// @param operation deferred model command
    private void startWrite(Supplier<CompletionStage<SchematicBrowserSnapshot>> operation) {
        final CompletionStage<SchematicBrowserSnapshot> completion;
        try {
            completion = Objects.requireNonNull(operation.get(), "schematic write returned null");
        } catch (RuntimeException failure) {
            writeCompleted(failure);
            return;
        }
        completion.whenComplete((
                @Nullable SchematicBrowserSnapshot ignored,
                @Nullable Throwable failure) -> EdtDispatcher.execute(() -> writeCompleted(failure)));
    }

    /// Reports only write failures that were not already published in the browser status.
    ///
    /// @param failure asynchronous wrapper or original failure, or null after success
    private void writeCompleted(@Nullable Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        if (!isOpen() || failure == null) {
            return;
        }
        Throwable resolved = unwrapCompletionFailure(failure);
        if (resolved instanceof CancellationException || isPublishedWriteFailure(resolved)) {
            return;
        }
        interactions.showFailure(
                this,
                strings.actions().operationFailedTitle(),
                failureText(resolved));
    }

    /// Completes the local reveal gate and reports non-cancellation failures while still open.
    ///
    /// @param failure asynchronous wrapper or original failure, or null after success
    private void revealCompleted(@Nullable Throwable failure) {
        EdtDispatcher.requireEventDispatchThread();
        if (!isOpen()) {
            return;
        }
        revealPending = false;
        updateSelectionPresentation();
        if (failure == null) {
            return;
        }
        Throwable resolved = unwrapCompletionFailure(failure);
        if (!(resolved instanceof CancellationException)) {
            interactions.showFailure(
                    this,
                    strings.actions().revealFailedTitle(),
                    failureText(resolved));
        }
    }

    /// Reports a synchronous dialog or command failure unless it represents cancellation or closure.
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
                    strings.actions().operationFailedTitle(),
                    failureText(resolved));
        }
    }

    /// Returns the displayed state when both display and model still expose the same writable directory.
    ///
    /// @return current action state, or null when an action is no longer legal
    private @Nullable SchematicBrowserSnapshot currentActionSnapshot() {
        @Nullable SchematicBrowserSnapshot snapshot = displayedSnapshot;
        if (snapshot == null || !isActionDirectoryCurrent(snapshot.currentDirectory())) {
            return null;
        }
        return snapshot;
    }

    /// Revalidates the directory and both lifecycle states after a modal interaction returns.
    ///
    /// @param expectedDirectory directory captured before the modal interaction
    /// @return whether a write action still targets the current stable listing
    private boolean isActionDirectoryCurrent(Path expectedDirectory) {
        if (!isOpen()) {
            return false;
        }
        @Nullable SchematicBrowserSnapshot displayed = displayedSnapshot;
        if (displayed == null
                || !expectedDirectory.equals(displayed.currentDirectory())
                || !hasExactReadyListing(displayed)
                || displayed.writeStatus() == SchematicBrowserWriteStatus.BUSY) {
            return false;
        }
        SchematicBrowserSnapshot current = model.snapshot();
        return expectedDirectory.equals(current.currentDirectory())
                && hasExactReadyListing(current)
                && current.writeStatus() != SchematicBrowserWriteStatus.BUSY
                && current.contentRevision() == displayed.contentRevision()
                && current.itemCount().equals(displayed.itemCount());
    }

    /// Revalidates a captured selection after a modal confirmation returns.
    ///
    /// @param expectedDirectory directory captured before confirmation
    /// @param expectedPath selected row path captured before confirmation
    /// @return whether the same target remains selected in the same writable directory
    private boolean isSelectedActionCurrent(Path expectedDirectory, Path expectedPath) {
        if (!isActionDirectoryCurrent(expectedDirectory)) {
            return false;
        }
        @Nullable SchematicBrowserItem selected = choiceList.getSelectedValue();
        return selected != null && expectedPath.equals(selected.path());
    }

    /// Returns whether selection operations are legal against an exact stable listing.
    ///
    /// @return whether file operations may use the current selection
    private boolean hasExactReadyListingForActions() {
        @Nullable SchematicBrowserSnapshot snapshot = displayedSnapshot;
        return snapshot != null && isActionDirectoryCurrent(snapshot.currentDirectory());
    }

    /// Returns whether parent navigation remains legal in both displayed and current model state.
    ///
    /// @return whether the parent command may run
    private boolean canReturnToParent() {
        if (!isOpen()) {
            return false;
        }
        @Nullable SchematicBrowserSnapshot displayed = displayedSnapshot;
        if (displayed == null || !canUseReadCommands(displayed) || !displayed.canReturnToParent()) {
            return false;
        }
        SchematicBrowserSnapshot current = model.snapshot();
        return current.currentDirectory().equals(displayed.currentDirectory())
                && canUseReadCommands(current)
                && current.canReturnToParent();
    }

    /// Returns whether refresh remains legal in both displayed and current model state.
    ///
    /// @return whether the refresh command may run
    private boolean canRefresh() {
        if (!isOpen()) {
            return false;
        }
        @Nullable SchematicBrowserSnapshot displayed = displayedSnapshot;
        if (displayed == null || !canUseReadCommands(displayed)) {
            return false;
        }
        SchematicBrowserSnapshot current = model.snapshot();
        return current.currentDirectory().equals(displayed.currentDirectory())
                && canUseReadCommands(current);
    }

    /// Returns whether retry remains legal for the current scan failure.
    ///
    /// @return whether retry may run
    private boolean canRetry() {
        if (!isOpen()) {
            return false;
        }
        @Nullable SchematicBrowserSnapshot displayed = displayedSnapshot;
        if (displayed == null
                || displayed.status() != SchematicBrowserStatus.ERROR
                || displayed.writeStatus() == SchematicBrowserWriteStatus.BUSY) {
            return false;
        }
        SchematicBrowserSnapshot current = model.snapshot();
        return current.currentDirectory().equals(displayed.currentDirectory())
                && current.status() == SchematicBrowserStatus.ERROR
                && current.writeStatus() != SchematicBrowserWriteStatus.BUSY;
    }

    /// Returns whether a non-write scan command is legal for one snapshot.
    ///
    /// @param snapshot state to inspect
    /// @return whether refresh or parent navigation may run
    private static boolean canUseReadCommands(SchematicBrowserSnapshot snapshot) {
        return snapshot.status() != SchematicBrowserStatus.IDLE
                && snapshot.status() != SchematicBrowserStatus.LOADING
                && snapshot.writeStatus() != SchematicBrowserWriteStatus.BUSY;
    }

    /// Returns whether one snapshot represents a stable exact directory listing.
    ///
    /// @param snapshot state to inspect
    /// @return whether the listing is ready and exact
    private static boolean hasExactReadyListing(SchematicBrowserSnapshot snapshot) {
        return snapshot.status() == SchematicBrowserStatus.READY
                && snapshot.itemCount().isPresent();
    }

    /// Selects the visible status without exposing write diagnostics in ordinary page text.
    ///
    /// @param snapshot state being rendered
    /// @param scanFailure scan failure text, or null
    /// @return visible localized status
    private String statusText(
            SchematicBrowserSnapshot snapshot,
            @Nullable String scanFailure) {
        if (snapshot.writeStatus() == SchematicBrowserWriteStatus.BUSY) {
            return strings.actions().writingStatus();
        }
        if (snapshot.writeStatus() == SchematicBrowserWriteStatus.ERROR) {
            return strings.actions().writeFailedStatus();
        }
        String status = snapshot.currentDirectory().toString();
        return snapshot.status() == SchematicBrowserStatus.ERROR && scanFailure != null
                ? status + " - " + scanFailure
                : status;
    }

    /// Adds retained write diagnostics only to tooltip and assistive description text.
    ///
    /// @param snapshot state being rendered
    /// @param visibleStatus visible generic status
    /// @return accessible status description
    private static String statusDescription(
            SchematicBrowserSnapshot snapshot,
            String visibleStatus) {
        @Nullable String writeFailure = snapshot.writeFailureMessage();
        return snapshot.writeStatus() == SchematicBrowserWriteStatus.ERROR && writeFailure != null
                ? visibleStatus + ": " + writeFailure
                : visibleStatus;
    }

    /// Detects a model-published write error that already represents the same terminal failure.
    ///
    /// @param failure resolved write failure
    /// @return whether browser status already presents this failure
    private boolean isPublishedWriteFailure(Throwable failure) {
        SchematicBrowserSnapshot current = model.snapshot();
        return current.writeStatus() == SchematicBrowserWriteStatus.ERROR
                && Objects.equals(current.writeFailureMessage(), failureText(failure));
    }

    /// Removes completion wrappers while preserving the original failure identity.
    ///
    /// @param failure asynchronous wrapper or original failure
    /// @return deepest wrapped failure
    private static Throwable unwrapCompletionFailure(Throwable failure) {
        Throwable current = failure;
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
        @Nullable String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message;
    }

    /// Updates selected-row details and directory command eligibility.
    private void updateSelectionPresentation() {
        EdtDispatcher.requireEventDispatchThread();
        if (!isOpen()) {
            return;
        }
        @Nullable SchematicBrowserItem selected = choiceList.getSelectedValue();
        boolean actionsEnabled = hasExactReadyListingForActions();
        openDirectoryButton.setEnabled(actionsEnabled && selected instanceof SchematicDirectoryItem);
        revealButton.setEnabled(actionsEnabled && selected != null && !revealPending);
        deleteButton.setEnabled(actionsEnabled && selected != null);
        if (selected instanceof SchematicDirectoryItem directory) {
            detailsHeading.setText(directory.fileName());
            SchematicMetadataStrings metadataStrings = strings.metadata();
            detailsArea.setText(strings.directorySelectionText()
                    + System.lineSeparator()
                    + metadataStrings.pathLabel()
                    + ": "
                    + directory.path());
            detailsArea.setCaretPosition(0);
        } else if (selected instanceof SchematicFileItem file) {
            showFileDetails(file);
        } else {
            showNoSelection();
        }
    }

    /// Displays parsed metadata or the retained parse failure for one file row.
    ///
    /// @param file loaded file row
    private void showFileDetails(SchematicFileItem file) {
        detailsHeading.setText(file.fileName());
        @Nullable LitematicFile metadata = file.metadata();
        if (metadata == null) {
            String failure = Objects.requireNonNull(file.failureMessage(), "unreadable file requires failure text");
            detailsArea.setText(strings.unreadableText()
                    + System.lineSeparator()
                    + failure
                    + System.lineSeparator()
                    + strings.metadata().pathLabel()
                    + ": "
                    + file.path());
            detailsArea.setCaretPosition(0);
            return;
        }

        SchematicMetadataStrings labels = strings.metadata();
        StringBuilder details = new StringBuilder();
        appendDetail(details, labels.pathLabel(), file.path().toString());
        appendDetail(details, labels.nameLabel(), optionalText(metadata.getName(), labels));
        appendDetail(details, labels.authorLabel(), optionalText(metadata.getAuthor(), labels));
        appendDetail(details, labels.descriptionLabel(), optionalText(metadata.getDescription(), labels));
        appendDetail(details, labels.createdLabel(), formatInstant(metadata.getTimeCreated(), labels));
        appendDetail(details, labels.modifiedLabel(), formatInstant(metadata.getTimeModified(), labels));
        appendDetail(details, labels.regionCountLabel(), Integer.toString(metadata.getRegionCount()));
        appendDetail(details, labels.totalVolumeLabel(), Integer.toString(metadata.getTotalVolume()));
        appendDetail(details, labels.totalBlocksLabel(), Integer.toString(metadata.getTotalBlocks()));
        appendDetail(details, labels.enclosingSizeLabel(), formatEnclosingSize(metadata.getEnclosingSize(), labels));
        appendDetail(details, labels.formatVersionLabel(), formatVersion(metadata));
        appendDetail(
                details,
                labels.minecraftDataVersionLabel(),
                Integer.toString(metadata.getMinecraftDataVersion()));
        appendDetail(details, labels.previewLabel(), formatPreview(metadata.getPreviewImageData(), labels));
        detailsArea.setText(details.toString());
        detailsArea.setCaretPosition(0);
    }

    /// Restores the unselected details state.
    private void showNoSelection() {
        detailsHeading.setText(strings.detailsTitle());
        detailsArea.setText(strings.noSelectionText());
        detailsArea.setCaretPosition(0);
        openDirectoryButton.setEnabled(false);
        revealButton.setEnabled(false);
        deleteButton.setEnabled(false);
    }

    /// Releases all owned resources on the EDT while attempting every cleanup step.
    private void closeOnEventDispatchThread() {
        EdtDispatcher.requireEventDispatchThread();
        synchronized (publicationLock) {
            if (resourcesClosed) {
                return;
            }
            resourcesClosed = true;
            @Nullable Throwable failure = null;
            failure = attemptCleanup(failure, dropRegistration::close);
            failure = attemptCleanup(failure, modelSubscription::unsubscribe);
            failure = attemptCleanup(
                    failure,
                    () -> choiceList.getChoiceModel().removeListDataListener(listDataListener));
            failure = attemptCleanup(
                    failure,
                    () -> choiceList.getList().removeMouseListener(directoryOpenMouseListener));
            failure = attemptCleanup(failure, () -> returnButton.setEnabled(false));
            failure = attemptCleanup(failure, () -> refreshButton.setEnabled(false));
            failure = attemptCleanup(failure, () -> importButton.setEnabled(false));
            failure = attemptCleanup(failure, () -> createDirectoryButton.setEnabled(false));
            failure = attemptCleanup(failure, () -> openDirectoryButton.setEnabled(false));
            failure = attemptCleanup(failure, () -> revealButton.setEnabled(false));
            failure = attemptCleanup(failure, () -> deleteButton.setEnabled(false));
            failure = attemptCleanup(failure, () -> retryButton.setEnabled(false));
            failure = attemptCleanup(failure, choiceList::close);
            failure = attemptCleanup(failure, model::close);
            throwUncheckedFailure(failure);
        }
    }

    /// Returns localized row text without introducing a second renderer or fixed row geometry.
    ///
    /// @param item loaded browser row
    /// @return visible row text
    private String rowText(SchematicBrowserItem item) {
        if (item instanceof SchematicDirectoryItem directory) {
            return strings.directoryRowPrefix() + directory.fileName();
        }
        SchematicFileItem file = (SchematicFileItem) item;
        @Nullable LitematicFile metadata = file.metadata();
        @Nullable String metadataName = metadata == null ? null : metadata.getName();
        if (metadataName != null && !metadataName.isBlank() && !"Unnamed".equals(metadataName)) {
            return metadataName;
        }
        String fileName = file.fileName();
        int suffixLength = ".litematic".length();
        return fileName.length() > suffixLength
                ? fileName.substring(0, fileName.length() - suffixLength)
                : fileName;
    }

    /// Returns whether commands may still reach the owned model.
    ///
    /// @return whether the panel remains open
    private boolean isOpen() {
        synchronized (stateLock) {
            return !closed;
        }
    }

    /// Selects the state card for one exact browser snapshot.
    ///
    /// @param snapshot current browser state
    /// @return stable card identifier
    private static String selectContentCard(SchematicBrowserSnapshot snapshot) {
        if (snapshot.status() == SchematicBrowserStatus.IDLE
                || snapshot.status() == SchematicBrowserStatus.LOADING) {
            return LOADING_CARD;
        }
        if (snapshot.status() == SchematicBrowserStatus.ERROR) {
            return ERROR_CARD;
        }
        return snapshot.itemCount().orElse(0) == 0 ? EMPTY_CARD : BROWSER_CARD;
    }

    /// Keeps action labels when they fit and switches to a stable icon grid at narrow widths.
    @NotNullByDefault
    private static final class ResponsiveActionStrip extends JPanel {
        /// Horizontal space between full-width action buttons.
        private static final int ACTION_GAP = 8;

        /// Buttons participating in the shared responsive presentation.
        private final List<JButton> actions = new ArrayList<>();

        /// Last measured preferred widths for the full localized labels.
        private final List<Integer> fullWidths = new ArrayList<>();

        /// Full localized labels retained while compact buttons hide visible text.
        private final List<String> fullTexts = new ArrayList<>();

        /// Whether the strip currently presents icon-only actions.
        private boolean compact;

        /// Creates an empty trailing action strip.
        private ResponsiveActionStrip() {
            super(new FlowLayout(FlowLayout.TRAILING, ACTION_GAP, 0));
            setOpaque(false);
        }

        /// Adds one configured command and records its full-label width.
        ///
        /// @param action configured action button
        private void addAction(JButton action) {
            Objects.requireNonNull(action, "action");
            actions.add(action);
            fullTexts.add(Objects.requireNonNull(action.getText(), "action text"));
            fullWidths.add(action.getPreferredSize().width);
            add(action);
        }

        /// Updates one dynamic full label without leaking it into compact presentation.
        ///
        /// @param action action already owned by this strip
        /// @param fullText localized full label
        private void setActionText(JButton action, String fullText) {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(fullText, "fullText");
            int index = actions.indexOf(action);
            if (index < 0) {
                throw new IllegalArgumentException("Action does not belong to this strip");
            }
            fullTexts.set(index, fullText);
            action.getAccessibleContext().setAccessibleName(fullText);
            action.setText(fullText);
            fullWidths.set(index, action.getPreferredSize().width);
            if (compact) {
                action.setText(null);
            }
            revalidate();
        }

        /// Selects full labels or the icon grid from the actual allocated width before layout.
        @Override
        public void doLayout() {
            int availableWidth = Math.max(0, getWidth() - getInsets().left - getInsets().right);
            boolean shouldCompact = fullPresentationWidth() > availableWidth;
            if (compact && !shouldCompact) {
                applyCompactPresentation(false);
                shouldCompact = refreshFullWidths() > availableWidth;
            }
            applyCompactPresentation(shouldCompact);
            super.doLayout();
        }

        /// Allows the parent to constrain width below localized full-label preferences.
        ///
        /// @return zero-width minimum retaining the normal action-row height
        @Override
        public Dimension getMinimumSize() {
            return new Dimension(0, super.getMinimumSize().height);
        }

        /// Returns the cached or freshly measured width needed by all full labels.
        ///
        /// @return full presentation width including inter-button gaps
        private int fullPresentationWidth() {
            int buttonsWidth = compact ? sumFullWidths() : refreshFullWidths();
            return actions.isEmpty()
                    ? 0
                    : buttonsWidth + (actions.size() + 1) * ACTION_GAP;
        }

        /// Refreshes full-label button widths while labels are visible.
        ///
        /// @return sum of refreshed button widths
        private int refreshFullWidths() {
            int total = 0;
            for (int index = 0; index < actions.size(); index++) {
                int width = actions.get(index).getPreferredSize().width;
                fullWidths.set(index, width);
                total += width;
            }
            return total;
        }

        /// Returns the sum of the most recently measured full-label widths.
        ///
        /// @return cached width sum
        private int sumFullWidths() {
            int total = 0;
            for (Integer width : fullWidths) {
                total += width;
            }
            return total;
        }

        /// Applies one presentation mode without discarding hover or accessible labels.
        ///
        /// @param compactPresentation whether only icons should remain visible
        private void applyCompactPresentation(boolean compactPresentation) {
            if (compact == compactPresentation) {
                return;
            }
            compact = compactPresentation;
            setLayout(compact
                    ? new GridLayout(1, Math.max(1, actions.size()), ACTION_GAP, 0)
                    : new FlowLayout(FlowLayout.TRAILING, ACTION_GAP, 0));
            for (int index = 0; index < actions.size(); index++) {
                actions.get(index).setText(compact ? null : fullTexts.get(index));
            }
        }
    }

    /// Configures one theme-aware command with embedded icon and assistive text.
    ///
    /// @param button button to configure
    /// @param name stable component name
    /// @param text visible command text
    /// @param tooltip accessible hover text
    /// @param iconResource embedded classpath SVG resource
    private static void configureActionButton(
            JButton button,
            String name,
            String text,
            String tooltip,
            String iconResource) {
        button.setName(name);
        button.setText(text);
        button.setToolTipText(tooltip);
        button.setIcon(new FlatSVGIcon(iconResource, 18, 18));
        button.getAccessibleContext().setAccessibleName(text);
        button.getAccessibleContext().setAccessibleDescription(tooltip);
    }

    /// Creates one centered lifecycle label with a stable name.
    ///
    /// @param name stable component name
    /// @return centered label
    private static JLabel stateLabel(String name) {
        JLabel label = new JLabel("", SwingConstants.CENTER);
        label.setName(name);
        return label;
    }

    /// Appends one labeled metadata line.
    ///
    /// @param builder target details text
    /// @param label localized field label
    /// @param value formatted field value
    private static void appendDetail(StringBuilder builder, String label, String value) {
        if (builder.length() > 0) {
            builder.append(System.lineSeparator());
        }
        builder.append(label).append(": ").append(value);
    }

    /// Formats one optional non-blank metadata string.
    ///
    /// @param value optional source text
    /// @param strings metadata strings
    /// @return source text or localized unknown value
    private static String optionalText(
            @Nullable String value,
            SchematicMetadataStrings strings) {
        return value == null || value.isBlank() ? strings.unknownValue() : value;
    }

    /// Formats one optional metadata instant with the launcher's active locale.
    ///
    /// @param value optional instant
    /// @param strings metadata strings
    /// @return localized time or unknown value
    private static String formatInstant(
            @Nullable Instant value,
            SchematicMetadataStrings strings) {
        return value == null ? strings.unknownValue() : I18n.formatDateTime(value);
    }

    /// Formats optional enclosing dimensions.
    ///
    /// @param size optional dimensions
    /// @param strings metadata strings
    /// @return dimensions or unknown value
    private static String formatEnclosingSize(
            @Nullable LitematicFile.EnclosingSize size,
            SchematicMetadataStrings strings) {
        return size == null
                ? strings.unknownValue()
                : String.format(
                        Locale.getDefault(),
                        strings.enclosingSizeFormat(),
                        size.x(),
                        size.y(),
                        size.z());
    }

    /// Formats the main and optional sub-version without inventing absent values.
    ///
    /// @param metadata parsed metadata
    /// @return format version text
    private static String formatVersion(LitematicFile metadata) {
        return metadata.getSubVersion() == 0
                ? Integer.toString(metadata.getVersion())
                : metadata.getVersion() + "." + metadata.getSubVersion();
    }

    /// Reports preview metadata without constructing any desktop image object.
    ///
    /// @param pixels optional defensive preview pixel copy
    /// @param strings metadata strings
    /// @return deferred preview dimensions or pixel count
    private static String formatPreview(
            int @Nullable [] pixels,
            SchematicMetadataStrings strings) {
        if (pixels == null || pixels.length == 0) {
            return strings.previewUnavailableText();
        }
        int side = (int) Math.sqrt(pixels.length);
        if ((long) side * side == pixels.length) {
            return String.format(
                    Locale.getDefault(),
                    strings.previewDimensionsFormat(),
                    side,
                    side);
        }
        return String.format(
                Locale.getDefault(),
                strings.previewPixelCountFormat(),
                pixels.length);
    }

    /// Attempts one cleanup step and retains every unchecked failure.
    ///
    /// @param current earlier cleanup failure, or null
    /// @param cleanup cleanup action
    /// @return combined failure, or null
    private static @Nullable Throwable attemptCleanup(
            @Nullable Throwable current,
            Runnable cleanup) {
        try {
            cleanup.run();
            return current;
        } catch (RuntimeException | Error failure) {
            if (current == null) {
                return failure;
            }
            if (current != failure) {
                current.addSuppressed(failure);
            }
            return current;
        }
    }

    /// Rethrows an aggregated unchecked cleanup failure without changing its type.
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
}

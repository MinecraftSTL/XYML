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
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.schematic.LitematicFile;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.choice.ChoiceListEntry;
import space.minecraftstl.xyml.ui.swing.choice.ViewportChoiceList;
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
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

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

    /// Viewport-measured single-choice directory listing.
    private final ViewportChoiceList<SchematicBrowserItem> choiceList;

    /// State cards for loading, failure, empty, and populated directories.
    private final JPanel contentCards = new JPanel(new CardLayout());

    /// Parent-directory toolbar command.
    private final JButton returnButton = new JButton();

    /// Current-directory refresh command.
    private final JButton refreshButton = new JButton();

    /// Selected child-directory navigation command.
    private final JButton openDirectoryButton = new JButton();

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

    /// Creates a browser on the EDT and takes ownership of its model without starting I/O.
    ///
    /// @param model owned shallow schematic model
    /// @param strings localized browser and metadata text
    public SchematicBrowserPanel(
            SchematicBrowserModel model,
            SchematicBrowserStrings strings) {
        super(new MigLayout(
                "insets 0, fill, wrap 1",
                "[grow,fill]",
                "[]12[grow,fill]10[]"));
        EdtDispatcher.requireEventDispatchThread();
        this.model = Objects.requireNonNull(model, "model");
        this.strings = Objects.requireNonNull(strings, "strings");
        choiceList = new ViewportChoiceList<>(model, this::rowText);

        configureComponents();
        modelSubscription = model.subscribe(this::modelChanged);
        applySnapshot(model.snapshot());
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
        JPanel toolbar = new JPanel(new MigLayout(
                "insets 0, fillx",
                "[grow,fill][]8[]8[]",
                "[40!]"));
        toolbar.setOpaque(false);

        JLabel heading = new JLabel(strings.pageTitle());
        heading.setName("schematicsPageTitle");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 28.0F));
        toolbar.add(heading);

        configureToolbarButton(
                returnButton,
                "schematicsReturn",
                strings.returnAction(),
                strings.returnTooltip(),
                "assets/swing/icons/arrow-back.svg");
        returnButton.addActionListener(event -> {
            if (isOpen()) {
                model.returnToParent();
            }
        });
        toolbar.add(returnButton, "h 40!");

        configureToolbarButton(
                refreshButton,
                "schematicsRefresh",
                strings.refreshAction(),
                strings.refreshTooltip(),
                "assets/swing/icons/refresh.svg");
        refreshButton.addActionListener(event -> {
            if (isOpen()) {
                model.refresh();
            }
        });
        toolbar.add(refreshButton, "h 40!");

        configureToolbarButton(
                openDirectoryButton,
                "schematicsOpenDirectory",
                strings.openDirectoryAction(),
                strings.openDirectoryTooltip(),
                "assets/swing/icons/folder-open.svg");
        openDirectoryButton.addActionListener(event -> openSelectedDirectory());
        toolbar.add(openDirectoryButton, "h 40!");
        add(toolbar, "growx");

        JPanel errorPanel = new JPanel(new MigLayout(
                "insets 24, fill, wrap 1",
                "[grow,center]",
                "[grow,center]12[]"));
        errorPanel.setOpaque(false);
        errorPanel.add(errorLabel, "growx");
        retryButton.setName("schematicsRetry");
        retryButton.setText(strings.retryAction());
        retryButton.addActionListener(event -> {
            if (isOpen()) {
                model.refresh();
            }
        });
        errorPanel.add(retryButton, "h 40!");

        choiceList.setName("schematicsList");
        JList<ChoiceListEntry<SchematicBrowserItem>> list = choiceList.getList();
        list.setName("schematicsListView");
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
                "[]8[grow,fill]"));
        detailsPanel.setName("schematicsDetailsPanel");
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
        detailsScroll.getViewport().setOpaque(false);
        detailsPanel.add(detailsScroll, "grow");

        JSplitPane browserSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, choiceList, detailsPanel);
        browserSplit.setName("schematicsBrowserSplit");
        browserSplit.setBorder(BorderFactory.createEmptyBorder());
        browserSplit.setContinuousLayout(true);
        browserSplit.setResizeWeight(0.62D);

        loadingLabel.setText(strings.idleText());
        emptyLabel.setText(strings.emptyText());
        contentCards.add(loadingLabel, LOADING_CARD);
        contentCards.add(errorPanel, ERROR_CARD);
        contentCards.add(emptyLabel, EMPTY_CARD);
        contentCards.add(browserSplit, BROWSER_CARD);
        add(contentCards, "grow");

        statusLabel.setName("schematicsStatus");
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
        returnButton.setEnabled(!loading && snapshot.canReturnToParent());
        refreshButton.setText(loading ? strings.refreshingAction() : strings.refreshAction());
        refreshButton.setEnabled(!loading);
        retryButton.setEnabled(snapshot.status() == SchematicBrowserStatus.ERROR);
        choiceList.setEnabled(snapshot.status() == SchematicBrowserStatus.READY);
        choiceList.getList().setEnabled(snapshot.status() == SchematicBrowserStatus.READY);
        updateSelectionPresentation();

        String statusText = snapshot.currentDirectory().toString();
        if (snapshot.status() == SchematicBrowserStatus.ERROR && failureMessage != null) {
            statusText = statusText + " - " + failureMessage;
        }
        statusLabel.setText(statusText);
        statusLabel.setToolTipText(statusText);
    }

    /// Opens the selected loaded directory while commands remain enabled.
    private void openSelectedDirectory() {
        EdtDispatcher.requireEventDispatchThread();
        if (!isOpen()) {
            return;
        }
        @Nullable SchematicBrowserItem selected = choiceList.getSelectedValue();
        if (selected instanceof SchematicDirectoryItem directory) {
            model.openDirectory(directory.path());
        }
    }

    /// Updates selected-row details and directory command eligibility.
    private void updateSelectionPresentation() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            return;
        }
        @Nullable SchematicBrowserItem selected = choiceList.getSelectedValue();
        boolean ready = displayedSnapshot != null
                && displayedSnapshot.status() == SchematicBrowserStatus.READY;
        openDirectoryButton.setEnabled(ready && selected instanceof SchematicDirectoryItem);
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
            failure = attemptCleanup(failure, modelSubscription::unsubscribe);
            failure = attemptCleanup(
                    failure,
                    () -> choiceList.getChoiceModel().removeListDataListener(listDataListener));
            failure = attemptCleanup(
                    failure,
                    () -> choiceList.getList().removeMouseListener(directoryOpenMouseListener));
            failure = attemptCleanup(failure, () -> returnButton.setEnabled(false));
            failure = attemptCleanup(failure, () -> refreshButton.setEnabled(false));
            failure = attemptCleanup(failure, () -> openDirectoryButton.setEnabled(false));
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

    /// Configures one theme-aware toolbar command with an embedded SVG icon.
    ///
    /// @param button button to configure
    /// @param name stable component name
    /// @param text visible command text
    /// @param tooltip accessible hover text
    /// @param iconResource embedded classpath SVG resource
    private static void configureToolbarButton(
            JButton button,
            String name,
            String text,
            String tooltip,
            String iconResource) {
        button.setName(name);
        button.setText(text);
        button.setToolTipText(tooltip);
        button.setIcon(new FlatSVGIcon(iconResource, 18, 18));
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

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
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.choice.ChoiceListEntry;
import space.minecraftstl.xyml.ui.swing.choice.ViewportChoiceList;

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
import java.util.Objects;
import java.util.OptionalInt;

/// Presents a lazily loaded Minecraft game-version catalog with viewport-driven row demand.
///
/// Construction performs no catalog I/O. The first Swing display notification asks the model to
/// load if still idle. The panel owns its model subscription and viewport requests, but the caller
/// retains ownership of the model and its source.
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

    /// Lock guarding close state and queued model-change revisions.
    private final Object stateLock = new Object();

    /// Serializes EDT state application with synchronous close cleanup.
    private final Object publicationLock = new Object();

    /// Toolkit-neutral catalog model and viewport source.
    private final GameVersionCatalogModel model;

    /// Localized control text.
    private final GameVersionCatalogStrings strings;

    /// Viewport-measured single-choice list.
    private final ViewportChoiceList<GameVersionCatalogItem> choiceList;

    /// Cards representing lazy loading, failure, empty results, and visible rows.
    private final JPanel contentCards = new JPanel(new CardLayout());

    /// Version-ID query editor.
    private final JTextField searchField = new JTextField();

    /// Game-version kind selector.
    private final JComboBox<GameVersionFilter> filterBox = new JComboBox<>(GameVersionFilter.values());

    /// Source refresh command.
    private final JButton refreshButton = new JButton();

    /// Current model status, including retained-content refresh failures.
    private final JLabel statusLabel = new JLabel();

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

    /// Owned model listener registration.
    private final Subscription modelSubscription;

    /// Snapshot currently represented by controls, or null before initialization.
    private @Nullable GameVersionCatalogSnapshot displayedSnapshot;

    /// User-selected logical row waiting for its loaded value, or -1 when none is pending.
    private int pendingUserSelectionIndex = -1;

    /// Whether programmatic control restoration suppresses model commands.
    private boolean applyingSnapshot;

    /// Revision invalidating older worker-to-EDT state applications.
    private long updateRevision;

    /// Whether this panel has already delegated its one lazy initial-load request.
    private boolean initialLoadRequested;

    /// Whether this panel has released its subscription and viewport resources.
    private boolean closed;

    /// Creates a game-version catalog panel on the Swing event dispatch thread.
    ///
    /// @param model toolkit-neutral lazy catalog model
    /// @param strings localized page text
    public GameVersionCatalogPanel(
            GameVersionCatalogModel model,
            GameVersionCatalogStrings strings) {
        super(new MigLayout(
                "insets 0, fill, wrap 1",
                "[grow,fill]",
                "[]12[]12[grow,fill]8[]"));
        EdtDispatcher.requireEventDispatchThread();
        this.model = Objects.requireNonNull(model, "model");
        this.strings = Objects.requireNonNull(strings, "strings");
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

    /// Builds the stable title, query, filter, list-state, and status layout.
    private void configureComponents() {
        JPanel headingBand = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill][]", "[]"));
        headingBand.setOpaque(false);

        JLabel heading = new JLabel(strings.pageTitle());
        heading.setName("gameVersionsPageTitle");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 28.0F));
        headingBand.add(heading);

        refreshButton.setName("gameVersionsRefresh");
        refreshButton.addActionListener(event -> {
            if (isOpen()) {
                model.refresh();
            }
        });
        headingBand.add(refreshButton, "h 40!");
        add(headingBand, "growx");

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
        add(filterBand, "growx");

        choiceList.setName("gameVersionsList");
        JList<ChoiceListEntry<GameVersionCatalogItem>> list = choiceList.getList();
        list.setName("gameVersionsListView");
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !applyingSnapshot && isOpen()) {
                pendingUserSelectionIndex = list.getSelectedIndex();
                submitPendingUserSelection();
            }
        });
        choiceList.getChoiceModel().addListDataListener(listDataListener);

        contentCards.add(loadingLabel, LOADING_CARD);
        contentCards.add(failedLabel, FAILED_CARD);
        contentCards.add(emptyLabel, EMPTY_CARD);
        contentCards.add(choiceList, LIST_CARD);
        add(contentCards, "grow");

        statusLabel.setName("gameVersionsStatus");
        add(statusLabel, "growx, h 28!");
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
        refreshButton.setEnabled(snapshot.refreshEnabled());
        statusLabel.setText(snapshot.statusText());
        statusLabel.setToolTipText(snapshot.statusText());
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

    /// Releases list listeners and viewport requests on the EDT.
    private void closeOnEventDispatchThread() {
        EdtDispatcher.requireEventDispatchThread();
        synchronized (publicationLock) {
            @Nullable Throwable cleanupFailure = null;
            cleanupFailure = attemptCleanup(cleanupFailure, modelSubscription::unsubscribe);
            cleanupFailure = attemptCleanup(
                    cleanupFailure,
                    () -> searchField.getDocument().removeDocumentListener(searchListener));
            cleanupFailure = attemptCleanup(
                    cleanupFailure,
                    () -> choiceList.getChoiceModel().removeListDataListener(listDataListener));
            cleanupFailure = attemptCleanup(cleanupFailure, () -> refreshButton.setEnabled(false));
            cleanupFailure = attemptCleanup(cleanupFailure, () -> searchField.setEnabled(false));
            cleanupFailure = attemptCleanup(cleanupFailure, () -> filterBox.setEnabled(false));
            cleanupFailure = attemptCleanup(cleanupFailure, () -> choiceList.setEnabled(false));
            cleanupFailure = attemptCleanup(cleanupFailure, () -> choiceList.getList().setEnabled(false));
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

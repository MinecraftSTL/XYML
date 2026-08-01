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
package space.minecraftstl.xyml.ui.swing.page.instances;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.event.EventBus;
import space.minecraftstl.xyml.event.EventManager;
import space.minecraftstl.xyml.event.RefreshedGameInstancesEvent;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.image.InstanceIconData;
import space.minecraftstl.xyml.image.InstanceIconLoader;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.setting.GameSettings;
import space.minecraftstl.xyml.setting.GameInstanceIconType;
import space.minecraftstl.xyml.ui.swing.choice.ChoicePage;
import space.minecraftstl.xyml.ui.swing.choice.IndexRange;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;
import space.minecraftstl.xyml.ui.swing.runtime.LauncherStateDispatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Adapts one real game repository to the viewport-driven installed-instance model.
///
/// Repository scans and per-row game-version detection run on the supplied background executor.
/// Each content revision captures immutable instance descriptors, exact count, and selection in one
/// atomically published state. The wrapped repository is accessed only through the launcher state
/// dispatcher, so no UI component type crosses this model boundary.
@NotNullByDefault
public final class RepositoryInstancesModel implements InstancesModel, AutoCloseable {
    /// Lock protecting atomic model-state replacement, refresh ownership, and close state.
    private final Object stateLock = new Object();

    /// Repository operations isolated behind a testable non-UI boundary.
    private final RepositoryAccess repository;

    /// Executor required to run scans and detail resolution away from UI threads.
    private final Executor backgroundExecutor;

    /// Command that opens the new-instance workflow.
    private final Runnable addCommand;

    /// Command that opens management for a stable instance ID.
    private final Consumer<GameInstanceID> manageCommand;

    /// Localized repository state and fallback text.
    private final RepositoryInstancesStatusStrings statusStrings;

    /// Thread-safe page-state publisher.
    private final ValueChangeSupport<InstancesSnapshot> changes = new ValueChangeSupport<>(this);

    /// Atomically published indexed source and its matching page snapshot.
    private volatile ModelState state;

    /// Cancellable repository-refresh event registration.
    private final Subscription refreshEventSubscription;

    /// Cancellable selected-instance registration for this repository.
    private final Subscription selectionSubscription;

    /// Cancellable icon-change registration for this repository.
    private final Subscription iconEventSubscription;

    /// Active model-owned refresh, or null while idle or awaiting an external initial scan.
    private @Nullable RefreshOperation activeRefresh;

    /// Whether later commands, loads, and state transitions are rejected.
    private volatile boolean closed;

    /// Creates a production model for one repository and one caller-owned I/O executor.
    ///
    /// An unloaded repository remains in its loading state until an already-running initial scan emits
    /// [RefreshedGameInstancesEvent], or the composition root explicitly calls [#refreshInstances()].
    ///
    /// @param repository real installed-game repository
    /// @param backgroundExecutor caller-owned executor suitable for blocking disk and JAR access
    /// @param addCommand new-instance workflow command
    /// @param manageCommand selected-instance management command
    /// @param statusStrings localized repository text
    public RepositoryInstancesModel(
            XYMLGameRepository repository,
            Executor backgroundExecutor,
            Runnable addCommand,
            Consumer<GameInstanceID> manageCommand,
            RepositoryInstancesStatusStrings statusStrings) {
        this(
                new XYMLRepositoryAccess(repository),
                EventBus.EVENT_BUS.channel(RefreshedGameInstancesEvent.class),
                backgroundExecutor,
                addCommand,
                manageCommand,
                statusStrings);
    }

    /// Creates a model with explicit repository, event, and executor collaborators.
    ///
    /// This constructor is package-visible for deterministic repository adapter tests.
    ///
    /// @param repository repository operations
    /// @param refreshedInstancesEvents completed-refresh event manager
    /// @param backgroundExecutor non-UI I/O executor
    /// @param addCommand new-instance workflow command
    /// @param manageCommand selected-instance management command
    /// @param statusStrings localized repository text
    RepositoryInstancesModel(
            RepositoryAccess repository,
            EventManager<RefreshedGameInstancesEvent> refreshedInstancesEvents,
            Executor backgroundExecutor,
            Runnable addCommand,
            Consumer<GameInstanceID> manageCommand,
            RepositoryInstancesStatusStrings statusStrings) {
        this.repository = Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(refreshedInstancesEvents, "refreshedInstancesEvents");
        this.backgroundExecutor = Objects.requireNonNull(backgroundExecutor, "backgroundExecutor");
        this.addCommand = Objects.requireNonNull(addCommand, "addCommand");
        this.manageCommand = Objects.requireNonNull(manageCommand, "manageCommand");
        this.statusStrings = Objects.requireNonNull(statusStrings, "statusStrings");

        SourceSnapshot emptySource = new SourceSnapshot(0L, List.of());
        state = new ModelState(emptySource, new InstancesSnapshot(
                OptionalInt.empty(),
                0,
                0L,
                statusStrings.loadingStatus(),
                false,
                false,
                false,
                true,
                false));
        refreshEventSubscription = refreshedInstancesEvents.subscribe(this::repositoryRefreshed);
        selectionSubscription = repository.subscribeSelectedInstance(this::selectionChanged);
        iconEventSubscription = repository.subscribeIconChanges(this::iconsChanged);

        if (repository.isLoaded()) {
            replaceInitialRepositoryContent();
        }
    }

    /// Returns the latest immutable repository page state.
    @Override
    public InstancesSnapshot snapshot() {
        return state.snapshot();
    }

    /// Registers a listener for future repository page transitions.
    @Override
    public Subscription subscribe(ValueChangeListener<InstancesSnapshot> listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (stateLock) {
            requireOpen();
            return changes.subscribe(listener);
        }
    }

    /// Returns the exact count from the atomically published current source revision.
    @Override
    public OptionalInt exactItemCount() {
        return OptionalInt.of(state.source().entries().size());
    }

    /// Returns stable instance identifiers without resolving version details or icons.
    @Override
    public @Unmodifiable List<String> stableItemIds() {
        SourceSnapshot source = state.source();
        List<String> identifiers = new ArrayList<>(source.entries().size());
        for (RepositoryEntry entry : source.entries()) {
            identifiers.add(entry.id().id());
        }
        return List.copyOf(identifiers);
    }

    /// Returns cheap stable IDs and their repository display names without resolving row details.
    @Override
    public @Unmodifiable List<InstanceSearchEntry> searchEntries() {
        SourceSnapshot source = state.source();
        List<InstanceSearchEntry> entries = new ArrayList<>(source.entries().size());
        for (RepositoryEntry entry : source.entries()) {
            entries.add(new InstanceSearchEntry(entry.id(), entry.id().id()));
        }
        return List.copyOf(entries);
    }

    /// Resolves one requested instance row away from the event dispatch thread.
    @Override
    public CompletionStage<InstanceListItem> loadItem(
            String stableId,
            LoadCancellation cancellation) {
        Objects.requireNonNull(stableId, "stableId");
        Objects.requireNonNull(cancellation, "cancellation");
        SourceSnapshot requestSource;
        synchronized (stateLock) {
            requireOpen();
            requestSource = state.source();
        }
        int index = indexOf(requestSource.entries(), new GameInstanceID(stableId));
        if (index < 0) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Unknown instance: " + stableId));
        }
        RepositoryEntry entry = requestSource.entries().get(index);
        try {
            return CompletableFuture.supplyAsync(
                    () -> loadEntry(entry, cancellation),
                    backgroundExecutor);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /// Resolves only the rows demanded by the current measured viewport.
    @Override
    public CompletionStage<ChoicePage<InstanceListItem>> load(
            IndexRange desiredRange,
            LoadCancellation cancellation) {
        Objects.requireNonNull(desiredRange, "desiredRange");
        Objects.requireNonNull(cancellation, "cancellation");
        SourceSnapshot requestSource;
        synchronized (stateLock) {
            requireOpen();
            requestSource = state.source();
        }
        IndexRange actualRange = desiredRange.clampToItemCount(requestSource.entries().size());
        try {
            return CompletableFuture.supplyAsync(
                    () -> loadRange(requestSource, actualRange, cancellation),
                    backgroundExecutor);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /// Persists a stable loaded instance selection and publishes its indexed state.
    @Override
    public void selectInstance(GameInstanceID instanceId) {
        Objects.requireNonNull(instanceId, "instanceId");
        InstancesSnapshot previous;
        InstancesSnapshot replacement;
        synchronized (stateLock) {
            requireOpen();
            ModelState current = state;
            int selectedIndex = indexOf(current.source().entries(), instanceId);
            if (selectedIndex < 0) {
                throw new IllegalArgumentException("Unknown instance: " + instanceId);
            }

            repository.setSelectedInstanceId(instanceId);
            previous = current.snapshot();
            replacement = copySnapshot(
                    previous,
                    OptionalInt.of(selectedIndex),
                    previous.statusText(),
                    previous.refreshing(),
                    previous.listEnabled(),
                    previous.refreshEnabled(),
                    previous.addEnabled(),
                    true);
            state = new ModelState(current.source(), replacement);
        }
        publish(previous, replacement);
    }

    /// Starts one background repository refresh and disables duplicate refresh commands.
    @Override
    public void refreshInstances() {
        RefreshOperation operation;
        InstancesSnapshot previous;
        InstancesSnapshot replacement;
        synchronized (stateLock) {
            requireOpen();
            if (activeRefresh != null) {
                return;
            }
            operation = new RefreshOperation();
            ModelState current = state;
            previous = current.snapshot();
            replacement = copySnapshot(
                    previous,
                    previous.selectedIndex(),
                    statusStrings.refreshingStatus(),
                    true,
                    false,
                    false,
                    previous.addEnabled(),
                    false);
            state = new ModelState(current.source(), replacement);
            activeRefresh = operation;
        }
        operation.completion().whenComplete((ignored, failure) -> refreshCompleted(operation, failure));
        publish(previous, replacement);

        if (!isActive(operation)) {
            operation.completion().cancel(false);
            return;
        }
        try {
            backgroundExecutor.execute(() -> runRefresh(operation));
        } catch (RuntimeException failure) {
            operation.completion().completeExceptionally(failure);
        }
    }

    /// Delegates the new-instance workflow command while this model is open.
    @Override
    public void addInstance() {
        synchronized (stateLock) {
            requireOpen();
        }
        addCommand.run();
    }

    /// Delegates management for the currently selected stable instance ID.
    @Override
    public void manageSelectedInstance() {
        GameInstanceID selectedInstanceId;
        synchronized (stateLock) {
            requireOpen();
            ModelState current = state;
            OptionalInt selectedIndex = current.snapshot().selectedIndex();
            if (selectedIndex.isEmpty()) {
                return;
            }
            selectedInstanceId = current.source().entries().get(selectedIndex.getAsInt()).id();
        }
        manageCommand.accept(selectedInstanceId);
    }

    /// Invalidates active completion, rejects later work, and removes the global event registration.
    @Override
    public void close() {
        @Nullable RefreshOperation operation;
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            closed = true;
            operation = activeRefresh;
            activeRefresh = null;
        }
        if (operation != null) {
            operation.completion().cancel(false);
        }
        refreshEventSubscription.unsubscribe();
        selectionSubscription.unsubscribe();
        iconEventSubscription.unsubscribe();
    }

    /// Runs one model-owned blocking refresh only while its operation remains active.
    ///
    /// @param operation refresh ownership token
    private void runRefresh(RefreshOperation operation) {
        if (!isActive(operation)) {
            operation.completion().cancel(false);
            return;
        }
        try {
            repository.refresh();
            operation.completion().complete(null);
        } catch (Throwable failure) {
            operation.completion().completeExceptionally(failure);
        }
    }

    /// Loads one immutable range from captured revision descriptors.
    ///
    /// @param requestSource source revision captured when the request started
    /// @param actualRange range constrained to that source boundary
    /// @param cancellation cooperative viewport cancellation signal
    /// @return source-aligned exact choice page
    private ChoicePage<InstanceListItem> loadRange(
            SourceSnapshot requestSource,
            IndexRange actualRange,
            LoadCancellation cancellation) {
        checkLoadActive(cancellation);
        List<InstanceListItem> items = new ArrayList<>(actualRange.length());
        for (int index = actualRange.startInclusive(); index < actualRange.endExclusive(); index++) {
            checkLoadActive(cancellation);
            RepositoryEntry entry = requestSource.entries().get(index);
            String detail = entry.resolveGameVersion().orElse(statusStrings.unknownVersionDetail());
            checkLoadActive(cancellation);
            InstanceIconData icon = repository.resolveIcon(entry.id());
            items.add(new InstanceListItem(entry.id(), entry.id().id(), detail, icon));
        }
        checkLoadActive(cancellation);
        return new ChoicePage<>(
                actualRange,
                List.copyOf(items),
                OptionalInt.of(requestSource.entries().size()),
                actualRange.endExclusive() == requestSource.entries().size());
    }

    /// Resolves one immutable repository descriptor into its visible row.
    ///
    /// @param entry captured repository descriptor
    /// @param cancellation cooperative viewport cancellation signal
    /// @return resolved visible row
    private InstanceListItem loadEntry(
            RepositoryEntry entry,
            LoadCancellation cancellation) {
        checkLoadActive(cancellation);
        String detail = entry.resolveGameVersion().orElse(statusStrings.unknownVersionDetail());
        checkLoadActive(cancellation);
        InstanceIconData icon = repository.resolveIcon(entry.id());
        checkLoadActive(cancellation);
        return new InstanceListItem(entry.id(), entry.id().id(), detail, icon);
    }

    /// Applies a matching real-repository refresh event without releasing model-owned refresh ownership.
    ///
    /// @param event completed refresh event
    private void repositoryRefreshed(RefreshedGameInstancesEvent event) {
        if (event.getSource() != repository.eventSource()) {
            return;
        }
        try {
            @Unmodifiable List<RepositoryEntry> entries = repository.displayedInstances();
            @Nullable GameInstanceID selectedInstanceId = repository.selectedInstanceId();
            applyRepositoryEvent(entries, selectedInstanceId);
        } catch (RuntimeException failure) {
            LOG.warning("Failed to apply refreshed instances", failure);
        }
    }

    /// Applies one repository-confirmed selection without changing indexed content.
    ///
    /// @param change repository selection transition
    private void selectionChanged(ValueChange<GameInstanceID> change) {
        @Nullable GameInstanceID selectedInstanceId = change.currentValue();
        InstancesSnapshot previous;
        InstancesSnapshot replacement;
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            ModelState current = state;
            OptionalInt selectedIndex = selectedIndex(
                    current.source().entries(), selectedInstanceId);
            previous = current.snapshot();
            replacement = copySnapshot(
                    previous,
                    selectedIndex,
                    previous.statusText(),
                    previous.refreshing(),
                    previous.listEnabled(),
                    previous.refreshEnabled(),
                    previous.addEnabled(),
                    !previous.refreshing() && selectedIndex.isPresent());
            state = new ModelState(current.source(), replacement);
        }
        publish(previous, replacement);
    }

    /// Publishes a fresh content revision after the repository changes any instance icon.
    ///
    /// The ordered descriptor list and selection remain unchanged. Incrementing only the content revision lets
    /// [InstancesPanel] invalidate its sparse rows, after which the viewport model resolves icons for the measured
    /// visible range instead of eagerly decoding every installed instance.
    private void iconsChanged() {
        InstancesSnapshot previous;
        InstancesSnapshot replacement;
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            ModelState current = state;
            SourceSnapshot source = new SourceSnapshot(
                    current.source().contentRevision() + 1L,
                    current.source().entries());
            previous = current.snapshot();
            replacement = new InstancesSnapshot(
                    previous.selectedIndex(),
                    previous.itemCount(),
                    source.contentRevision(),
                    previous.statusText(),
                    previous.refreshing(),
                    previous.listEnabled(),
                    previous.refreshEnabled(),
                    previous.addEnabled(),
                    previous.manageEnabled());
            state = new ModelState(source, replacement);
        }
        publish(previous, replacement);
    }

    /// Replaces initial content without incrementing the initial revision.
    private void replaceInitialRepositoryContent() {
        @Unmodifiable List<RepositoryEntry> entries = repository.displayedInstances();
        @Nullable GameInstanceID selectedInstanceId = repository.selectedInstanceId();
        InstancesSnapshot previous;
        InstancesSnapshot replacement;
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            previous = state.snapshot();
            SourceSnapshot source = new SourceSnapshot(0L, entries);
            replacement = readySnapshot(source, selectedInstanceId);
            state = new ModelState(source, replacement);
        }
        publish(previous, replacement);
    }

    /// Publishes one completed repository event as a new immutable content revision.
    ///
    /// @param entries captured immutable repository descriptors
    /// @param selectedInstanceId repository-selected ID, or null for none
    private void applyRepositoryEvent(
            @Unmodifiable List<RepositoryEntry> entries,
            @Nullable GameInstanceID selectedInstanceId) {
        InstancesSnapshot previous;
        InstancesSnapshot replacement;
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            ModelState current = state;
            SourceSnapshot source = new SourceSnapshot(
                    current.source().contentRevision() + 1L,
                    entries);
            previous = current.snapshot();
            @Nullable RefreshOperation operation = activeRefresh;
            if (operation == null) {
                replacement = readySnapshot(source, selectedInstanceId);
            } else {
                OptionalInt selectedIndex = selectedIndex(entries, selectedInstanceId);
                replacement = new InstancesSnapshot(
                        selectedIndex,
                        entries.size(),
                        source.contentRevision(),
                        statusStrings.refreshingStatus(),
                        true,
                        false,
                        false,
                        true,
                        false);
            }
            state = new ModelState(source, replacement);
        }
        publish(previous, replacement);
    }

    /// Completes one model-owned refresh according to whether an event already supplied content.
    ///
    /// @param operation refresh ownership token
    /// @param failure completion failure, or null for success
    private void refreshCompleted(RefreshOperation operation, @Nullable Throwable failure) {
        synchronized (stateLock) {
            if (closed || activeRefresh != operation) {
                return;
            }
        }

        if (failure == null) {
            finishOperationFromRepository(operation);
        } else if (!(failure instanceof CancellationException)) {
            finishOperationFailed(operation);
        }
    }

    /// Uses repository state as a fallback when a successful refresh emitted no completion event.
    ///
    /// @param operation refresh ownership token
    private void finishOperationFromRepository(RefreshOperation operation) {
        @Unmodifiable List<RepositoryEntry> entries;
        @Nullable GameInstanceID selectedInstanceId;
        try {
            entries = repository.displayedInstances();
            selectedInstanceId = repository.selectedInstanceId();
        } catch (RuntimeException failure) {
            finishOperationFailed(operation);
            return;
        }

        InstancesSnapshot previous;
        InstancesSnapshot replacement;
        synchronized (stateLock) {
            if (closed || activeRefresh != operation) {
                return;
            }
            ModelState current = state;
            if (hasSameContent(current.source().entries(), entries)) {
                previous = current.snapshot();
                replacement = readySnapshot(current.source(), selectedInstanceId);
                state = new ModelState(current.source(), replacement);
            } else {
                SourceSnapshot source = new SourceSnapshot(
                        current.source().contentRevision() + 1L,
                        entries);
                previous = current.snapshot();
                replacement = readySnapshot(source, selectedInstanceId);
                state = new ModelState(source, replacement);
            }
            activeRefresh = null;
        }
        publish(previous, replacement);
    }

    /// Restores commands after a refresh failure without mutating indexed content.
    ///
    /// @param operation refresh ownership token
    private void finishOperationFailed(RefreshOperation operation) {
        InstancesSnapshot previous;
        InstancesSnapshot replacement;
        synchronized (stateLock) {
            if (closed || activeRefresh != operation) {
                return;
            }
            ModelState current = state;
            previous = current.snapshot();
            replacement = copySnapshot(
                    previous,
                    previous.selectedIndex(),
                    statusStrings.refreshFailedStatus(),
                    false,
                    true,
                    true,
                    previous.addEnabled(),
                    previous.selectedIndex().isPresent());
            state = new ModelState(current.source(), replacement);
            activeRefresh = null;
        }
        publish(previous, replacement);
    }

    /// Creates a command-enabled ready snapshot for one exact source revision.
    ///
    /// @param source exact source revision
    /// @param selectedInstanceId selected repository ID, or null for none
    /// @return ready page snapshot
    private InstancesSnapshot readySnapshot(
            SourceSnapshot source,
            @Nullable GameInstanceID selectedInstanceId) {
        OptionalInt selectedIndex = selectedIndex(source.entries(), selectedInstanceId);
        return new InstancesSnapshot(
                selectedIndex,
                source.entries().size(),
                source.contentRevision(),
                statusStrings.readyStatus(),
                false,
                true,
                true,
                true,
                selectedIndex.isPresent());
    }

    /// Copies a snapshot while retaining its item count and content revision.
    ///
    /// @param source original snapshot
    /// @param selectedIndex replacement selected index
    /// @param statusText replacement status text
    /// @param refreshing replacement refreshing state
    /// @param listEnabled replacement list availability
    /// @param refreshEnabled replacement refresh availability
    /// @param addEnabled replacement add availability
    /// @param manageEnabled replacement management availability
    /// @return replacement snapshot
    private static InstancesSnapshot copySnapshot(
            InstancesSnapshot source,
            OptionalInt selectedIndex,
            String statusText,
            boolean refreshing,
            boolean listEnabled,
            boolean refreshEnabled,
            boolean addEnabled,
            boolean manageEnabled) {
        return new InstancesSnapshot(
                selectedIndex,
                source.itemCount(),
                source.contentRevision(),
                statusText,
                refreshing,
                listEnabled,
                refreshEnabled,
                addEnabled,
                manageEnabled);
    }

    /// Returns whether two captured descriptor lists represent the same repository content.
    ///
    /// @param left first immutable descriptor list
    /// @param right second immutable descriptor list
    /// @return whether stable IDs and captured content tokens are equal in order
    private static boolean hasSameContent(
            @Unmodifiable List<RepositoryEntry> left,
            @Unmodifiable List<RepositoryEntry> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            RepositoryEntry leftEntry = left.get(index);
            RepositoryEntry rightEntry = right.get(index);
            if (!leftEntry.id().equals(rightEntry.id())
                    || !Objects.equals(leftEntry.contentToken(), rightEntry.contentToken())) {
                return false;
            }
        }
        return true;
    }

    /// Finds a persisted selected ID in immutable descriptor order.
    ///
    /// @param entries immutable displayed entries
    /// @param selectedInstanceId selected repository ID, or null for none
    /// @return selected source index, or empty when the persisted ID is absent
    private static OptionalInt selectedIndex(
            @Unmodifiable List<RepositoryEntry> entries,
            @Nullable GameInstanceID selectedInstanceId) {
        int selectedIndex = indexOf(entries, selectedInstanceId);
        return selectedIndex < 0 ? OptionalInt.empty() : OptionalInt.of(selectedIndex);
    }

    /// Finds a stable ID in immutable descriptor order.
    ///
    /// @param entries immutable displayed entries
    /// @param instanceId stable instance ID, or null for none
    /// @return zero-based index, or -1 when absent
    private static int indexOf(
            @Unmodifiable List<RepositoryEntry> entries,
            @Nullable GameInstanceID instanceId) {
        if (instanceId == null) {
            return -1;
        }
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index).id().equals(instanceId)) {
                return index;
            }
        }
        return -1;
    }

    /// Returns whether one refresh operation still owns the model refresh slot.
    ///
    /// @param operation refresh operation to inspect
    /// @return whether it may perform repository I/O
    private boolean isActive(RefreshOperation operation) {
        synchronized (stateLock) {
            return !closed && activeRefresh == operation;
        }
    }

    /// Rejects a range load after either list cancellation or model closure.
    ///
    /// @param cancellation viewport cancellation signal
    private void checkLoadActive(LoadCancellation cancellation) {
        if (closed || cancellation.isCancelled()) {
            throw new CancellationException("Viewport instance load is no longer active");
        }
    }

    /// Rejects commands after this model has closed.
    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Repository instances model is closed");
        }
    }

    /// Publishes one already-applied state transition without disrupting repository event delivery.
    ///
    /// @param previous previous page snapshot
    /// @param replacement replacement page snapshot
    private void publish(InstancesSnapshot previous, InstancesSnapshot replacement) {
        try {
            changes.fireChange(previous, replacement);
        } catch (RuntimeException failure) {
            LOG.warning("An installed-instance model listener failed", failure);
        }
    }

    /// Exact immutable instance descriptors associated with one content revision.
    ///
    /// @param contentRevision non-negative content revision
    /// @param entries immutable displayed instance descriptors in source order
    @NotNullByDefault
    private record SourceSnapshot(
            long contentRevision,
            @Unmodifiable List<RepositoryEntry> entries) {
        /// Validates and defensively copies one source snapshot.
        private SourceSnapshot {
            if (contentRevision < 0L) {
                throw new IllegalArgumentException("Content revision cannot be negative");
            }
            entries = List.copyOf(entries);
        }
    }

    /// Atomically published source revision and its exactly matching page state.
    ///
    /// @param source exact indexed source
    /// @param snapshot matching page snapshot
    @NotNullByDefault
    private record ModelState(SourceSnapshot source, InstancesSnapshot snapshot) {
        /// Validates count and revision agreement between both state halves.
        private ModelState {
            if (source.entries().size() != snapshot.itemCount()) {
                throw new IllegalArgumentException("Snapshot item count must match source entries");
            }
            if (source.contentRevision() != snapshot.contentRevision()) {
                throw new IllegalArgumentException("Snapshot revision must match source revision");
            }
        }
    }

    /// Ownership token for one model-started repository refresh.
    @NotNullByDefault
    private static final class RefreshOperation {
        /// Completion observed by the model state machine.
        private final CompletableFuture<Void> completion = new CompletableFuture<>();

        /// Returns this operation's completion.
        ///
        /// @return refresh completion
        private CompletableFuture<Void> completion() {
            return completion;
        }

    }

    /// Captured lazy detail resolver for one exact repository instance revision.
    @FunctionalInterface
    @NotNullByDefault
    interface DetailResolver {
        /// Resolves the captured instance's underlying game version.
        ///
        /// @return game version, or empty when unknown
        Optional<String> resolveGameVersion();
    }

    /// Immutable stable ID and revision-captured lazy detail resolver.
    ///
    /// @param id stable instance ID
    /// @param contentToken identity or value representing captured row content
    /// @param detailResolver resolver capturing the matching repository version descriptor
    @NotNullByDefault
    record RepositoryEntry(GameInstanceID id, Object contentToken, DetailResolver detailResolver) {
        /// Validates one repository entry.
        RepositoryEntry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(contentToken, "contentToken");
            Objects.requireNonNull(detailResolver, "detailResolver");
        }

        /// Resolves this captured instance's underlying game version.
        ///
        /// @return game version, or empty when unknown
        Optional<String> resolveGameVersion() {
            return Objects.requireNonNull(
                    detailResolver.resolveGameVersion(),
                    "detail resolver result");
        }
    }

    /// Non-UI repository operations required by this adapter.
    @NotNullByDefault
    interface RepositoryAccess {
        /// Returns the identity emitted as a refresh event source.
        Object eventSource();

        /// Returns whether the repository has completed an initial scan.
        boolean isLoaded();

        /// Captures immutable displayed entries and lazy detail resolvers in repository order.
        @Unmodifiable List<RepositoryEntry> displayedInstances();

        /// Returns the selected stable instance ID, or null for none.
        @Nullable GameInstanceID selectedInstanceId();

        /// Registers for repository-confirmed selected-instance transitions.
        ///
        /// @param listener selected-instance transition listener
        /// @return independently cancellable listener registration
        Subscription subscribeSelectedInstance(ValueChangeListener<GameInstanceID> listener);

        /// Registers for repository-confirmed instance-icon transitions.
        ///
        /// @param listener no-argument icon transition listener
        /// @return independently cancellable listener registration
        Subscription subscribeIconChanges(Runnable listener);

        /// Resolves one normalized icon for a viewport-demanded instance row.
        ///
        /// This method may perform filesystem and image-decoding work and therefore runs only on the model's
        /// caller-owned background executor.
        ///
        /// @param instanceId stable instance ID
        /// @return immutable normalized icon pixels
        InstanceIconData resolveIcon(GameInstanceID instanceId);

        /// Persists the selected stable instance ID.
        ///
        /// @param instanceId stable instance ID
        void setSelectedInstanceId(GameInstanceID instanceId);

        /// Performs a blocking repository refresh on a background thread.
        void refresh();
    }

    /// Real [XYMLGameRepository] access without leaking it through the public model contract.
    @NotNullByDefault
    private static final class XYMLRepositoryAccess implements RepositoryAccess {
        /// Wrapped repository.
        private final XYMLGameRepository repository;

        /// Creates a real repository access adapter.
        ///
        /// @param repository wrapped repository
        private XYMLRepositoryAccess(XYMLGameRepository repository) {
            this.repository = Objects.requireNonNull(repository, "repository");
        }

        /// Returns the wrapped repository event identity.
        @Override
        public Object eventSource() {
            return repository;
        }

        /// Returns whether the wrapped repository has completed its initial scan.
        @Override
        public boolean isLoaded() {
            return repository.isLoaded();
        }

        /// Captures each displayed instance manifest inside its lazy game-version resolver.
        @Override
        public @Unmodifiable List<RepositoryEntry> displayedInstances() {
            return repository.getDisplayInstanceManifests()
                    .map(manifest -> new RepositoryEntry(
                            manifest.id(),
                            manifest,
                            () -> repository.getGameVersion(manifest)))
                    .toList();
        }

        /// Returns the persisted selected repository instance ID.
        @Override
        public @Nullable GameInstanceID selectedInstanceId() {
            return repository.getSelectedInstance();
        }

        /// Registers a toolkit-neutral selected-instance listener.
        @Override
        public Subscription subscribeSelectedInstance(ValueChangeListener<GameInstanceID> listener) {
            return repository.subscribeSelectedInstance(listener);
        }

        /// Registers for this repository's instance-icon transitions.
        ///
        /// @param listener no-argument icon transition listener
        /// @return independently cancellable listener registration
        @Override
        public Subscription subscribeIconChanges(Runnable listener) {
            return repository.onInstanceIconChanged.subscribe(listener);
        }

        /// Resolves custom or configured bundled icon pixels for one demanded row.
        ///
        /// @param instanceId stable instance ID
        /// @return immutable normalized icon pixels
        @Override
        public InstanceIconData resolveIcon(GameInstanceID instanceId) {
            @Nullable GameSettings.Instance settings = repository.getInstanceGameSettings(instanceId);
            @Nullable GameInstanceIconType configuredType = settings == null
                    ? null
                    : settings.iconProperty().getValue();
            GameInstanceIconType builtInType = configuredType == null
                    ? GameInstanceIconType.DEFAULT
                    : configuredType;
            @Nullable java.nio.file.Path customIcon = repository.getInstanceIconFile(instanceId)
                    .orElse(null);
            return InstanceIconLoader.load(builtInType, customIcon);
        }

        /// Queues one selected repository instance ID on the Swing event thread.
        @Override
        public void setSelectedInstanceId(GameInstanceID instanceId) {
            LauncherStateDispatcher.execute(() -> repository.setSelectedInstance(instanceId));
        }

        /// Performs one blocking repository refresh.
        @Override
        public void refresh() {
            repository.refresh();
        }
    }
}

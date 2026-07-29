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
package space.minecraftstl.xyml.ui.swing.page.settings.theme;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.observable.ValueChangeSupport;
import space.minecraftstl.xyml.theme.ThemeReference;
import space.minecraftstl.xyml.ui.swing.choice.ChoicePage;
import space.minecraftstl.xyml.ui.swing.choice.IndexRange;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;
import space.minecraftstl.xyml.ui.swing.choice.ViewportChoiceDataSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Function;

/// Asynchronous local theme-pack inventory, mutation coordinator, and viewport data source.
///
/// Repository and resource work is delegated to the injected backend with the caller-owned executor. Snapshot
/// listeners run on the thread that commits a result; Swing owners must dispatch those callbacks to the EDT.
@NotNullByDefault
public final class ThemePackManagementModel
        implements ViewportChoiceDataSource<ThemePackItem>, AutoCloseable {
    /// Lock guarding lifecycle, generations, inventory, and snapshot replacement.
    private final Object stateLock = new Object();

    /// Background package boundary.
    private final ThemePackManagementBackend backend;

    /// Exact theme persistence and runtime application callback.
    private final ThemePackApplication application;

    /// Caller-owned worker used by every backend operation.
    private final Executor executor;

    /// Snapshot transition publisher.
    private final ValueChangeSupport<ThemePackManagementSnapshot> changes = new ValueChangeSupport<>(this);

    /// Complete loaded inventory before search filtering.
    private @Unmodifiable List<ThemePackItem> allItems = List.of();

    /// Latest immutable visible state.
    private volatile ThemePackManagementSnapshot snapshot;

    /// Monotonic inventory request generation rejecting stale refresh completions.
    private long refreshGeneration;

    /// Monotonic mutation generation rejecting late completion after close.
    private long operationGeneration;

    /// Terminal lifecycle flag.
    private boolean closed;

    /// Creates an idle theme-pack model.
    ///
    /// @param backend local and embedded package boundary
    /// @param application exact theme application callback
    /// @param executor caller-owned non-EDT worker executor
    /// @param appliedTheme currently applied reference, or `null`
    public ThemePackManagementModel(
            ThemePackManagementBackend backend,
            ThemePackApplication application,
            Executor executor,
            @Nullable ThemeReference appliedTheme) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.application = Objects.requireNonNull(application, "application");
        this.executor = Objects.requireNonNull(executor, "executor");
        snapshot = new ThemePackManagementSnapshot(
                List.of(),
                0,
                "",
                ThemePackManagementStatus.IDLE,
                ThemePackManagementOperation.NONE,
                appliedTheme,
                null,
                0L);
    }

    /// Returns the latest immutable state.
    ///
    /// @return current snapshot
    public ThemePackManagementSnapshot snapshot() {
        return snapshot;
    }

    /// Registers a listener for future snapshot transitions.
    ///
    /// @param listener snapshot listener
    /// @return independently cancellable subscription
    public Subscription subscribe(ValueChangeListener<ThemePackManagementSnapshot> listener) {
        synchronized (stateLock) {
            requireOpen();
            return changes.subscribe(Objects.requireNonNull(listener, "listener"));
        }
    }

    /// Loads or reloads the complete embedded and installed inventory.
    ///
    /// @return eventual current snapshot, including a visible failure state on error
    public CompletionStage<ThemePackManagementSnapshot> refresh() {
        long generation;
        ThemePackManagementSnapshot previous;
        ThemePackManagementSnapshot loading;
        synchronized (stateLock) {
            requireOpen();
            if (snapshot.operation() != ThemePackManagementOperation.NONE) {
                throw new IllegalStateException("Theme-pack mutation is already active");
            }
            generation = ++refreshGeneration;
            previous = snapshot;
            loading = replaceStatus(
                    previous,
                    ThemePackManagementStatus.LOADING,
                    ThemePackManagementOperation.NONE,
                    null);
            snapshot = loading;
        }
        changes.fireChange(previous, loading);

        CompletableFuture<ThemePackManagementSnapshot> completion = new CompletableFuture<>();
        CompletionStage<@Unmodifiable List<ThemePackItem>> stage;
        try {
            stage = Objects.requireNonNull(backend.loadAll(executor), "backend returned null load stage");
        } catch (RuntimeException failure) {
            completeRefresh(generation, null, failure, completion);
            return completion.minimalCompletionStage();
        }
        stage.whenComplete((items, failure) -> completeRefresh(generation, items, failure, completion));
        return completion.minimalCompletionStage();
    }

    /// Applies a local search query without reading package resources.
    ///
    /// @param query raw user query
    public void setQuery(String query) {
        String normalized = Objects.requireNonNull(query, "query").trim().toLowerCase(Locale.ROOT);
        ThemePackManagementSnapshot previous;
        ThemePackManagementSnapshot replacement;
        synchronized (stateLock) {
            requireOpen();
            previous = snapshot;
            if (previous.query().equals(normalized)) {
                return;
            }
            replacement = new ThemePackManagementSnapshot(
                    filter(allItems, normalized),
                    allItems.size(),
                    normalized,
                    previous.status(),
                    previous.operation(),
                    previous.appliedTheme(),
                    previous.failureMessage(),
                    previous.contentRevision() + 1L);
            snapshot = replacement;
        }
        changes.fireChange(previous, replacement);
    }

    /// Imports one `.xyml-theme` archive and appends its newly installed manifest index.
    ///
    /// @param archive selected local archive path
    /// @return eventual current snapshot with success or visible failure state
    public CompletionStage<ThemePackManagementSnapshot> importArchive(Path archive) {
        Path source = Objects.requireNonNull(archive, "archive").toAbsolutePath().normalize();
        @Nullable Path fileName = source.getFileName();
        if (fileName == null || !fileName.toString().toLowerCase(Locale.ROOT).endsWith(".xyml-theme")) {
            throw new IllegalArgumentException("Theme pack must use the .xyml-theme extension");
        }
        long generation = beginOperation(ThemePackManagementOperation.IMPORTING);
        CompletableFuture<ThemePackManagementSnapshot> completion = new CompletableFuture<>();
        CompletionStage<@Unmodifiable List<ThemePackItem>> stage;
        try {
            stage = Objects.requireNonNull(
                    backend.importArchive(source, executor),
                    "backend returned null import stage");
        } catch (RuntimeException failure) {
            completeImport(generation, null, failure, completion);
            return completion.minimalCompletionStage();
        }
        stage.whenComplete((items, failure) -> completeImport(generation, items, failure, completion));
        return completion.minimalCompletionStage();
    }

    /// Applies one exact item still present in the loaded inventory.
    ///
    /// @param item selected loaded item
    /// @return eventual current snapshot with applied reference or visible failure state
    public CompletionStage<ThemePackManagementSnapshot> apply(ThemePackItem item) {
        ThemePackItem current = requireCurrentItem(item);
        synchronized (stateLock) {
            if (Objects.equals(snapshot.appliedTheme(), current.reference())) {
                return CompletableFuture.completedFuture(snapshot).minimalCompletionStage();
            }
        }
        long generation = beginOperation(ThemePackManagementOperation.APPLYING);
        CompletableFuture<ThemePackManagementSnapshot> completion = new CompletableFuture<>();
        CompletionStage<@Nullable Void> stage;
        try {
            stage = Objects.requireNonNull(
                    application.apply(current.reference()),
                    "theme application returned null stage");
        } catch (RuntimeException failure) {
            completeApply(generation, current.reference(), failure, completion);
            return completion.minimalCompletionStage();
        }
        stage.whenComplete((ignored, failure) -> completeApply(generation, current.reference(), failure, completion));
        return completion.minimalCompletionStage();
    }

    /// Returns whether an item can delete its containing package in the current state.
    ///
    /// @param item candidate item
    /// @return whether it is current, installed, unapplied, and no work is active
    public boolean canDelete(ThemePackItem item) {
        synchronized (stateLock) {
            @Nullable ThemePackItem current = findCurrentItem(item);
            return current != null
                    && !snapshot.busy()
                    && !current.builtIn()
                    && current.installedDirectory() != null
                    && (snapshot.appliedTheme() == null
                    || !snapshot.appliedTheme().packId().equals(current.reference().packId()));
        }
    }

    /// Deletes the exact installed package represented by a current, unapplied item.
    ///
    /// @param item selected installed item
    /// @return eventual current snapshot with the package removed or a visible failure state
    public CompletionStage<ThemePackManagementSnapshot> delete(ThemePackItem item) {
        ThemePackItem current;
        synchronized (stateLock) {
            current = Objects.requireNonNull(findCurrentItem(item), "Theme item is no longer available");
            if (!canDelete(current)) {
                throw new IllegalStateException("The selected theme pack cannot be deleted in the current state");
            }
        }
        long generation = beginOperation(ThemePackManagementOperation.DELETING);
        CompletableFuture<ThemePackManagementSnapshot> completion = new CompletableFuture<>();
        CompletionStage<@Nullable Void> stage;
        try {
            stage = Objects.requireNonNull(
                    backend.deleteInstalled(current, executor),
                    "backend returned null delete stage");
        } catch (RuntimeException failure) {
            completeDelete(generation, current.reference().packId(), failure, completion);
            return completion.minimalCompletionStage();
        }
        stage.whenComplete((ignored, failure) ->
                completeDelete(generation, current.reference().packId(), failure, completion));
        return completion.minimalCompletionStage();
    }

    /// Revalidates and opens the exact installed directory through an injected desktop callback.
    ///
    /// @param item selected installed item
    /// @param revealer callback that opens the validated directory without blocking the EDT
    /// @return eventual current snapshot with success or visible failure state
    public CompletionStage<ThemePackManagementSnapshot> locate(
            ThemePackItem item,
            Function<Path, CompletionStage<@Nullable Void>> revealer) {
        ThemePackItem current = requireCurrentItem(item);
        if (current.builtIn() || current.installedDirectory() == null) {
            throw new IllegalArgumentException("Built-in theme packs do not have an installed directory");
        }
        Function<Path, CompletionStage<@Nullable Void>> checkedRevealer =
                Objects.requireNonNull(revealer, "revealer");
        long generation = beginOperation(ThemePackManagementOperation.LOCATING);
        CompletableFuture<ThemePackManagementSnapshot> completion = new CompletableFuture<>();
        CompletionStage<@Nullable Void> stage;
        try {
            stage = backend.locateInstalled(current, executor).thenCompose(path -> Objects.requireNonNull(
                    checkedRevealer.apply(path),
                    "directory revealer returned null stage"));
        } catch (RuntimeException failure) {
            completeSimpleOperation(generation, failure, completion);
            return completion.minimalCompletionStage();
        }
        stage.whenComplete((ignored, failure) -> completeSimpleOperation(generation, failure, completion));
        return completion.minimalCompletionStage();
    }

    /// Updates the externally observed applied reference without package work.
    ///
    /// @param appliedTheme exact applied reference, or `null`
    public void setAppliedTheme(@Nullable ThemeReference appliedTheme) {
        ThemePackManagementSnapshot previous;
        ThemePackManagementSnapshot replacement;
        synchronized (stateLock) {
            requireOpen();
            previous = snapshot;
            if (Objects.equals(previous.appliedTheme(), appliedTheme)) {
                return;
            }
            replacement = new ThemePackManagementSnapshot(
                    previous.items(),
                    previous.totalItemCount(),
                    previous.query(),
                    previous.status(),
                    previous.operation(),
                    appliedTheme,
                    previous.failureMessage(),
                    previous.contentRevision());
            snapshot = replacement;
        }
        changes.fireChange(previous, replacement);
    }

    /// Returns the current exact filtered item count for viewport sizing.
    ///
    /// @return exact current count
    @Override
    public OptionalInt exactItemCount() {
        return OptionalInt.of(snapshot.items().size());
    }

    /// Returns the revision used to reject a stale viewport slice after filtering or mutation.
    ///
    /// @return current source revision
    @Override
    public OptionalLong sourceRevision() {
        return OptionalLong.of(snapshot.contentRevision());
    }

    /// Returns only the measured viewport slice from the already loaded lightweight index.
    ///
    /// @param desiredRange exact adaptive viewport demand
    /// @param cancellation cooperative cancellation signal
    /// @return immediately completed slice without file or image work
    @Override
    public CompletionStage<ChoicePage<ThemePackItem>> load(
            IndexRange desiredRange,
            LoadCancellation cancellation) {
        Objects.requireNonNull(cancellation, "cancellation").throwIfCancelled();
        ThemePackManagementSnapshot current = snapshot;
        IndexRange range = Objects.requireNonNull(desiredRange, "desiredRange")
                .clampToItemCount(current.items().size());
        @Unmodifiable List<ThemePackItem> items = List.copyOf(
                current.items().subList(range.startInclusive(), range.endExclusive()));
        return CompletableFuture.completedFuture(new ChoicePage<>(
                range,
                items,
                OptionalInt.of(current.items().size()),
                range.endExclusive() == current.items().size()));
    }

    /// Invalidates pending work, clears the viewport source, and publishes terminal state.
    @Override
    public void close() {
        ThemePackManagementSnapshot previous;
        ThemePackManagementSnapshot replacement;
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            closed = true;
            refreshGeneration++;
            operationGeneration++;
            allItems = List.of();
            previous = snapshot;
            replacement = new ThemePackManagementSnapshot(
                    List.of(),
                    0,
                    previous.query(),
                    ThemePackManagementStatus.CLOSED,
                    ThemePackManagementOperation.NONE,
                    previous.appliedTheme(),
                    null,
                    previous.contentRevision() + 1L);
            snapshot = replacement;
        }
        changes.fireChange(previous, replacement);
    }

    /// Commits one applicable inventory result and resolves its caller stage.
    private void completeRefresh(
            long generation,
            @Nullable @Unmodifiable List<ThemePackItem> items,
            @Nullable Throwable failure,
            CompletableFuture<ThemePackManagementSnapshot> completion) {
        @Nullable Throwable resolvedFailure = unwrap(failure);
        @Unmodifiable List<ThemePackItem> validated = List.of();
        if (resolvedFailure == null) {
            try {
                validated = validateItems(Objects.requireNonNull(items, "backend returned null items"));
            } catch (RuntimeException validationFailure) {
                resolvedFailure = validationFailure;
            }
        }
        ThemePackManagementSnapshot previous;
        ThemePackManagementSnapshot replacement;
        synchronized (stateLock) {
            if (closed || generation != refreshGeneration) {
                completion.complete(snapshot);
                return;
            }
            previous = snapshot;
            if (resolvedFailure == null) {
                allItems = validated;
                replacement = new ThemePackManagementSnapshot(
                        filter(allItems, previous.query()),
                        allItems.size(),
                        previous.query(),
                        ThemePackManagementStatus.READY,
                        ThemePackManagementOperation.NONE,
                        previous.appliedTheme(),
                        null,
                        previous.contentRevision() + 1L);
            } else {
                replacement = replaceStatus(
                        previous,
                        ThemePackManagementStatus.FAILED,
                        ThemePackManagementOperation.NONE,
                        failureMessage(resolvedFailure));
            }
            snapshot = replacement;
        }
        completion.complete(replacement);
        changes.fireChange(previous, replacement);
    }

    /// Commits one applicable import result or failure.
    private void completeImport(
            long generation,
            @Nullable @Unmodifiable List<ThemePackItem> importedItems,
            @Nullable Throwable failure,
            CompletableFuture<ThemePackManagementSnapshot> completion) {
        @Nullable Throwable resolvedFailure = unwrap(failure);
        @Unmodifiable List<ThemePackItem> validated = List.of();
        if (resolvedFailure == null) {
            try {
                validated = validateItems(Objects.requireNonNull(importedItems, "backend returned null import items"));
                if (validated.isEmpty()) {
                    throw new IllegalStateException("Imported theme pack contains no themes");
                }
            } catch (RuntimeException validationFailure) {
                resolvedFailure = validationFailure;
            }
        }
        ThemePackManagementSnapshot previous;
        ThemePackManagementSnapshot replacement;
        synchronized (stateLock) {
            if (closed || generation != operationGeneration) {
                completion.complete(snapshot);
                return;
            }
            previous = snapshot;
            if (resolvedFailure == null) {
                try {
                    allItems = mergeImported(allItems, validated);
                    replacement = inventoryReadySnapshot(previous);
                } catch (RuntimeException mergeFailure) {
                    replacement = replaceStatus(
                            previous,
                            ThemePackManagementStatus.FAILED,
                            ThemePackManagementOperation.NONE,
                            failureMessage(mergeFailure));
                }
            } else {
                replacement = replaceStatus(
                        previous,
                        ThemePackManagementStatus.FAILED,
                        ThemePackManagementOperation.NONE,
                        failureMessage(resolvedFailure));
            }
            snapshot = replacement;
        }
        completion.complete(replacement);
        changes.fireChange(previous, replacement);
    }

    /// Commits one exact applied reference or visible failure.
    private void completeApply(
            long generation,
            ThemeReference reference,
            @Nullable Throwable failure,
            CompletableFuture<ThemePackManagementSnapshot> completion) {
        @Nullable Throwable resolvedFailure = unwrap(failure);
        ThemePackManagementSnapshot previous;
        ThemePackManagementSnapshot replacement;
        synchronized (stateLock) {
            if (closed || generation != operationGeneration) {
                completion.complete(snapshot);
                return;
            }
            previous = snapshot;
            replacement = new ThemePackManagementSnapshot(
                    previous.items(),
                    previous.totalItemCount(),
                    previous.query(),
                    resolvedFailure == null
                            ? ThemePackManagementStatus.READY
                            : ThemePackManagementStatus.FAILED,
                    ThemePackManagementOperation.NONE,
                    resolvedFailure == null ? reference : previous.appliedTheme(),
                    resolvedFailure == null ? null : failureMessage(resolvedFailure),
                    previous.contentRevision());
            snapshot = replacement;
        }
        completion.complete(replacement);
        changes.fireChange(previous, replacement);
    }

    /// Commits deletion by removing every theme belonging to the deleted package.
    private void completeDelete(
            long generation,
            String packageId,
            @Nullable Throwable failure,
            CompletableFuture<ThemePackManagementSnapshot> completion) {
        @Nullable Throwable resolvedFailure = unwrap(failure);
        ThemePackManagementSnapshot previous;
        ThemePackManagementSnapshot replacement;
        synchronized (stateLock) {
            if (closed || generation != operationGeneration) {
                completion.complete(snapshot);
                return;
            }
            previous = snapshot;
            if (resolvedFailure == null) {
                allItems = allItems.stream()
                        .filter(item -> !packageId.equals(item.reference().packId()))
                        .toList();
                replacement = inventoryReadySnapshot(previous);
            } else {
                replacement = replaceStatus(
                        previous,
                        ThemePackManagementStatus.FAILED,
                        ThemePackManagementOperation.NONE,
                        failureMessage(resolvedFailure));
            }
            snapshot = replacement;
        }
        completion.complete(replacement);
        changes.fireChange(previous, replacement);
    }

    /// Finishes locate and desktop integration without changing inventory content.
    private void completeSimpleOperation(
            long generation,
            @Nullable Throwable failure,
            CompletableFuture<ThemePackManagementSnapshot> completion) {
        @Nullable Throwable resolvedFailure = unwrap(failure);
        ThemePackManagementSnapshot previous;
        ThemePackManagementSnapshot replacement;
        synchronized (stateLock) {
            if (closed || generation != operationGeneration) {
                completion.complete(snapshot);
                return;
            }
            previous = snapshot;
            replacement = replaceStatus(
                    previous,
                    resolvedFailure == null
                            ? ThemePackManagementStatus.READY
                            : ThemePackManagementStatus.FAILED,
                    ThemePackManagementOperation.NONE,
                    resolvedFailure == null ? null : failureMessage(resolvedFailure));
            snapshot = replacement;
        }
        completion.complete(replacement);
        changes.fireChange(previous, replacement);
    }

    /// Begins one exclusive mutation and publishes its busy state.
    ///
    /// @param operation non-empty operation
    /// @return operation generation
    private long beginOperation(ThemePackManagementOperation operation) {
        if (operation == ThemePackManagementOperation.NONE) {
            throw new IllegalArgumentException("Operation must be active");
        }
        long generation;
        ThemePackManagementSnapshot previous;
        ThemePackManagementSnapshot replacement;
        synchronized (stateLock) {
            requireOpen();
            requireNoActiveWork();
            generation = ++operationGeneration;
            previous = snapshot;
            replacement = replaceStatus(previous, previous.status(), operation, null);
            snapshot = replacement;
        }
        changes.fireChange(previous, replacement);
        return generation;
    }

    /// Creates a ready snapshot after inventory content changes.
    private ThemePackManagementSnapshot inventoryReadySnapshot(ThemePackManagementSnapshot previous) {
        return new ThemePackManagementSnapshot(
                filter(allItems, previous.query()),
                allItems.size(),
                previous.query(),
                ThemePackManagementStatus.READY,
                ThemePackManagementOperation.NONE,
                previous.appliedTheme(),
                null,
                previous.contentRevision() + 1L);
    }

    /// Replaces lifecycle fields while preserving inventory and revision.
    private static ThemePackManagementSnapshot replaceStatus(
            ThemePackManagementSnapshot previous,
            ThemePackManagementStatus status,
            ThemePackManagementOperation operation,
            @Nullable String failureMessage) {
        return new ThemePackManagementSnapshot(
                previous.items(),
                previous.totalItemCount(),
                previous.query(),
                status,
                operation,
                previous.appliedTheme(),
                failureMessage,
                previous.contentRevision());
    }

    /// Filters already loaded metadata in deterministic source order.
    private static @Unmodifiable List<ThemePackItem> filter(
            @Unmodifiable List<ThemePackItem> items,
            String query) {
        if (query.isEmpty()) {
            return items;
        }
        return items.stream().filter(item -> item.matches(query)).toList();
    }

    /// Validates one complete item list and exact-reference uniqueness.
    private static @Unmodifiable List<ThemePackItem> validateItems(List<ThemePackItem> items) {
        @Unmodifiable List<ThemePackItem> copy = List.copyOf(items);
        Set<ThemeReference> references = new HashSet<>();
        for (ThemePackItem item : copy) {
            if (!references.add(item.reference())) {
                throw new IllegalStateException("Theme inventory contains a duplicate reference: " + item.reference());
            }
        }
        return copy;
    }

    /// Merges one new package only when its ID is absent from the complete inventory.
    private static @Unmodifiable List<ThemePackItem> mergeImported(
            @Unmodifiable List<ThemePackItem> existing,
            @Unmodifiable List<ThemePackItem> imported) {
        Set<String> existingPackageIds = new HashSet<>();
        existing.forEach(item -> existingPackageIds.add(item.reference().packId()));
        Set<String> importedPackageIds = new HashSet<>();
        imported.forEach(item -> importedPackageIds.add(item.reference().packId()));
        if (importedPackageIds.size() != 1 || existingPackageIds.contains(imported.get(0).reference().packId())) {
            throw new IllegalStateException("Imported theme package conflicts with the loaded inventory");
        }
        List<ThemePackItem> merged = new ArrayList<>(existing.size() + imported.size());
        merged.addAll(existing);
        merged.addAll(imported);
        return validateItems(merged);
    }

    /// Finds the exact current item represented by an arbitrary row value.
    private @Nullable ThemePackItem findCurrentItem(ThemePackItem item) {
        ThemePackItem checked = Objects.requireNonNull(item, "item");
        for (ThemePackItem current : allItems) {
            if (current.equals(checked)) {
                return current;
            }
        }
        return null;
    }

    /// Requires one exact item still present in the complete inventory.
    private ThemePackItem requireCurrentItem(ThemePackItem item) {
        synchronized (stateLock) {
            requireOpen();
            ThemePackItem current = Objects.requireNonNull(
                    findCurrentItem(item),
                    "Theme item is no longer available");
            requireNoActiveWork();
            return current;
        }
    }

    /// Rejects overlapping refreshes and mutations.
    private void requireNoActiveWork() {
        if (snapshot.busy()) {
            throw new IllegalStateException("Theme-pack management work is already active");
        }
    }

    /// Rejects commands after terminal close.
    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Theme-pack management model is closed");
        }
    }

    /// Unwraps one asynchronous wrapper for concise factual status text.
    private static @Nullable Throwable unwrap(@Nullable Throwable failure) {
        @Nullable Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /// Returns a stable failure message even when an exception has no text.
    private static String failureMessage(Throwable failure) {
        @Nullable String message = Objects.requireNonNull(failure, "failure").getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message.trim();
    }
}

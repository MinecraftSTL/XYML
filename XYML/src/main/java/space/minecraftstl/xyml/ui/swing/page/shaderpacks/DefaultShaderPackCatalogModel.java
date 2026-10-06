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
package space.minecraftstl.xyml.ui.swing.page.shaderpacks;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.util.io.DeletionMode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/// Default asynchronous shader-pack catalog model with serialized mutations.
@NotNullByDefault
public final class DefaultShaderPackCatalogModel implements ShaderPackCatalogModel {
    /// Blocking local access boundary.
    private final ShaderPackCatalogAccess access;

    /// Caller-owned executor for scans and mutations.
    private final Executor executor;

    /// Localized idle status.
    private final String idleText;

    /// Localized loading status.
    private final String loadingText;

    /// Localized ready status.
    private final String readyText;

    /// Localized empty status.
    private final String emptyText;

    /// Localized failure prefix.
    private final String failureText;

    /// Localized mutation status.
    private final String writingText;

    /// Localized mutation failure prefix.
    private final String writeFailedText;

    /// Guards snapshot, listener, lifecycle, and generation state.
    private final Object lock = new Object();

    /// Serializes every disk mutation while allowing scans to observe completed writes.
    private final Object mutationLock = new Object();

    /// Registered transition listeners.
    private final List<ValueChangeListener<ShaderPackCatalogSnapshot>> listeners = new ArrayList<>();

    /// Latest published snapshot.
    private ShaderPackCatalogSnapshot snapshot;

    /// Monotonic generation used to discard stale scans.
    private long generation;

    /// Whether an initial scan has been requested.
    private boolean loadStarted;

    /// Whether this model has been closed.
    private boolean closed;

    /// Creates one catalog model.
    ///
    /// @param access blocking local access boundary
    /// @param executor caller-owned executor for scans and mutations
    /// @param idleText localized idle status
    /// @param loadingText localized loading status
    /// @param readyText localized ready status
    /// @param emptyText localized empty status
    /// @param failureText localized failure prefix
    /// @param writingText localized mutation status
    /// @param writeFailedText localized mutation failure prefix
    public DefaultShaderPackCatalogModel(
            ShaderPackCatalogAccess access,
            Executor executor,
            String idleText,
            String loadingText,
            String readyText,
            String emptyText,
            String failureText,
            String writingText,
            String writeFailedText) {
        this.access = Objects.requireNonNull(access, "access");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.idleText = Objects.requireNonNull(idleText, "idleText");
        this.loadingText = Objects.requireNonNull(loadingText, "loadingText");
        this.readyText = Objects.requireNonNull(readyText, "readyText");
        this.emptyText = Objects.requireNonNull(emptyText, "emptyText");
        this.failureText = Objects.requireNonNull(failureText, "failureText");
        this.writingText = Objects.requireNonNull(writingText, "writingText");
        this.writeFailedText = Objects.requireNonNull(writeFailedText, "writeFailedText");
        snapshot = ShaderPackCatalogSnapshot.idle(idleText);
    }

    /// Returns the latest immutable snapshot.
    @Override
    public ShaderPackCatalogSnapshot snapshot() {
        synchronized (lock) {
            return snapshot;
        }
    }

    /// Registers one listener until the returned subscription is cancelled.
    @Override
    public Subscription subscribe(ValueChangeListener<ShaderPackCatalogSnapshot> listener) {
        ValueChangeListener<ShaderPackCatalogSnapshot> checked =
                Objects.requireNonNull(listener, "listener");
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("Shader-pack catalog is closed");
            }
            listeners.add(checked);
        }
        return Subscription.create(() -> {
            synchronized (lock) {
                listeners.remove(checked);
            }
        });
    }

    /// Starts the initial scan once.
    @Override
    public void loadIfNeeded() {
        synchronized (lock) {
            if (closed || loadStarted) {
                return;
            }
            loadStarted = true;
        }
        startScan();
    }

    /// Starts one fresh scan.
    @Override
    public void refresh() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            loadStarted = true;
        }
        startScan();
    }

    /// Selects one row in the current snapshot.
    @Override
    public void selectShaderPack(Path path) {
        Path selected = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        synchronized (lock) {
            if (closed) {
                return;
            }
            int index = indexOf(snapshot.items(), selected);
            publishLocked(withSelection(snapshot, index < 0 ? OptionalInt.empty() : OptionalInt.of(index)));
        }
    }

    /// Clears the current row selection.
    @Override
    public void clearSelection() {
        synchronized (lock) {
            if (!closed) {
                publishLocked(withSelection(snapshot, OptionalInt.empty()));
            }
        }
    }

    /// Imports one or more sources and rescans the catalog.
    @Override
    public CompletionStage<ShaderPackCatalogSnapshot> importShaderPacks(List<Path> sources) {
        List<Path> checkedSources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        if (checkedSources.isEmpty()) {
            return failedFuture(new IllegalArgumentException("At least one source is required"));
        }
        return submitMutation(() -> {
            access.importShaderPacks(checkedSources);
            return scan();
        });
    }

    /// Updates one pack's selected state in the requested backends.
    @Override
    public CompletionStage<ShaderPackCatalogSnapshot> setShaderPackEnabled(
            Path path,
            Set<ShaderPackBackend> backends,
            boolean enabled) {
        Path checkedPath = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        Set<ShaderPackBackend> checkedBackends = Set.copyOf(
                Objects.requireNonNull(backends, "backends"));
        if (checkedBackends.isEmpty()) {
            return failedFuture(new IllegalArgumentException("At least one backend is required"));
        }
        return submitMutation(() -> {
            access.setEnabled(checkedPath, checkedBackends, enabled);
            return scan();
        });
    }

    /// Deletes one pack and rescans the catalog.
    @Override
    public CompletionStage<ShaderPackCatalogSnapshot> deleteShaderPack(Path path, DeletionMode mode) {
        Path checkedPath = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        DeletionMode checkedMode = Objects.requireNonNull(mode, "mode");
        return submitMutation(() -> {
            access.delete(checkedPath, checkedMode);
            return scan();
        });
    }

    /// Deletes several packs serially while retaining every failure.
    @Override
    public CompletionStage<ShaderPackCatalogSnapshot> deleteShaderPacks(
            List<Path> paths,
            DeletionMode mode) {
        List<Path> checkedPaths = List.copyOf(Objects.requireNonNull(paths, "paths"));
        DeletionMode checkedMode = Objects.requireNonNull(mode, "mode");
        if (checkedPaths.isEmpty()) {
            return failedFuture(new IllegalArgumentException("At least one path is required"));
        }
        return submitMutation(() -> {
            @Nullable Throwable firstFailure = null;
            for (Path path : checkedPaths) {
                try {
                    access.delete(path.toAbsolutePath().normalize(), checkedMode);
                } catch (RuntimeException | Error | java.io.IOException failure) {
                    if (firstFailure == null) {
                        firstFailure = failure;
                    } else if (firstFailure != failure) {
                        firstFailure.addSuppressed(failure);
                    }
                }
            }
            ShaderPackCatalogSnapshot next = scan();
            publish(next);
            if (firstFailure != null) {
                throw unchecked(firstFailure);
            }
            return next;
        });
    }

    /// Releases listeners and prevents later scans or mutations.
    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            generation++;
            listeners.clear();
        }
    }

    /// Starts one background scan.
    private void startScan() {
        long requestedGeneration;
        synchronized (lock) {
            if (closed) {
                return;
            }
            requestedGeneration = ++generation;
            publishLocked(withStatus(snapshot, ShaderPackCatalogStatus.LOADING, loadingText));
        }
        try {
            executor.execute(() -> finishScan(requestedGeneration));
        } catch (RuntimeException failure) {
            synchronized (lock) {
                if (!closed && generation == requestedGeneration) {
                    publishLocked(withStatus(
                            snapshot,
                            ShaderPackCatalogStatus.FAILED,
                            failureText + ": " + detail(failure)));
                }
            }
        }
    }

    /// Completes one scan if it is still current.
    ///
    /// @param requestedGeneration generation being completed
    private void finishScan(long requestedGeneration) {
        try {
            ShaderPackCatalogSnapshot next = scan();
            synchronized (lock) {
                if (!closed && generation == requestedGeneration) {
                    publishLocked(next);
                }
            }
        } catch (RuntimeException | Error | java.io.IOException failure) {
            synchronized (lock) {
                if (!closed && generation == requestedGeneration) {
                    publishLocked(withStatus(
                            snapshot,
                            ShaderPackCatalogStatus.FAILED,
                            failureText + ": " + detail(failure)));
                }
            }
        }
    }

    /// Scans the local catalog synchronously on the caller's worker thread.
    ///
    /// @return completed ready snapshot
    /// @throws java.io.IOException when disk access fails
    private ShaderPackCatalogSnapshot scan() throws java.io.IOException {
        List<Path> paths = access.loadIndex();
        List<ShaderPackCatalogItem> items = access.loadItems(paths);
        Set<ShaderPackBackend> backends = access.detectAvailableBackends();
        synchronized (lock) {
            long revision = snapshot.contentRevision() + 1L;
            String text = items.isEmpty() ? emptyText : readyText;
            return new ShaderPackCatalogSnapshot(
                    OptionalInt.empty(),
                    items.size(),
                    revision,
                    ShaderPackCatalogStatus.READY,
                    text,
                    ShaderPackCatalogWriteStatus.IDLE,
                    "",
                    items,
                    backends);
        }
    }

    /// Submits one serialized mutation.
    ///
    /// @param mutation blocking mutation producing a new snapshot
    /// @return asynchronous terminal snapshot
    private CompletionStage<ShaderPackCatalogSnapshot> submitMutation(Mutation mutation) {
        CompletableFuture<ShaderPackCatalogSnapshot> result = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                synchronized (mutationLock) {
                    synchronized (lock) {
                        if (closed) {
                            result.completeExceptionally(
                                    new IllegalStateException("Shader-pack catalog is closed"));
                            return;
                        }
                        publishLocked(withWriteStatus(snapshot, ShaderPackCatalogWriteStatus.BUSY, writingText));
                    }
                    try {
                        ShaderPackCatalogSnapshot next = Objects.requireNonNull(
                                mutation.run(),
                                "mutation returned null");
                        synchronized (lock) {
                            if (!closed && !next.equals(snapshot)) {
                                publishLocked(next);
                            }
                        }
                        result.complete(next);
                    } catch (RuntimeException | Error | java.io.IOException failure) {
                        synchronized (lock) {
                            if (!closed) {
                                publishLocked(withWriteStatus(
                                        snapshot,
                                        ShaderPackCatalogWriteStatus.FAILED,
                                        writeFailedText + ": " + detail(failure)));
                            }
                        }
                        result.completeExceptionally(failure);
                    }
                }
            });
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    /// Publishes one snapshot while holding the state lock.
    ///
    /// @param next next snapshot
    private void publishLocked(ShaderPackCatalogSnapshot next) {
        ShaderPackCatalogSnapshot previous = snapshot;
        snapshot = Objects.requireNonNull(next, "next");
        for (ValueChangeListener<ShaderPackCatalogSnapshot> listener : List.copyOf(listeners)) {
            listener.onChange(new ValueChange<>(this, previous, snapshot));
        }
    }

    /// Publishes one snapshot without requiring the caller to hold the state lock.
    ///
    /// @param next next snapshot
    private void publish(ShaderPackCatalogSnapshot next) {
        synchronized (lock) {
            publishLocked(next);
        }
    }

    /// Creates a snapshot with one selected index.
    ///
    /// @param current current snapshot
    /// @param selectedIndex selected index
    /// @return replacement snapshot
    private static ShaderPackCatalogSnapshot withSelection(
            ShaderPackCatalogSnapshot current,
            OptionalInt selectedIndex) {
        return new ShaderPackCatalogSnapshot(
                selectedIndex,
                current.itemCount(),
                current.contentRevision(),
                current.status(),
                current.statusText(),
                current.writeStatus(),
                current.writeStatusText(),
                current.items(),
                current.availableBackends());
    }

    /// Creates a snapshot with one scan status.
    ///
    /// @param current current snapshot
    /// @param status replacement status
    /// @param text replacement status text
    /// @return replacement snapshot
    private static ShaderPackCatalogSnapshot withStatus(
            ShaderPackCatalogSnapshot current,
            ShaderPackCatalogStatus status,
            String text) {
        return new ShaderPackCatalogSnapshot(
                current.selectedIndex(),
                current.itemCount(),
                current.contentRevision(),
                status,
                text,
                current.writeStatus(),
                current.writeStatusText(),
                current.items(),
                current.availableBackends());
    }

    /// Creates a snapshot with one write status.
    ///
    /// @param current current snapshot
    /// @param status replacement write status
    /// @param text replacement write text
    /// @return replacement snapshot
    private static ShaderPackCatalogSnapshot withWriteStatus(
            ShaderPackCatalogSnapshot current,
            ShaderPackCatalogWriteStatus status,
            String text) {
        return new ShaderPackCatalogSnapshot(
                current.selectedIndex(),
                current.itemCount(),
                current.contentRevision(),
                current.status(),
                current.statusText(),
                status,
                text,
                current.items(),
                current.availableBackends());
    }

    /// Finds one path in a list of catalog items.
    ///
    /// @param items current catalog items
    /// @param path exact path
    /// @return matching index, or -1
    private static int indexOf(List<ShaderPackCatalogItem> items, Path path) {
        for (int index = 0; index < items.size(); index++) {
            if (items.get(index).path().equals(path)) {
                return index;
            }
        }
        return -1;
    }

    /// Returns a failed future without throwing synchronously.
    ///
    /// @param failure failure to deliver
    /// @param <T> future value type
    /// @return failed future
    private static <T> CompletionStage<T> failedFuture(Throwable failure) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(Objects.requireNonNull(failure, "failure"));
        return future;
    }

    /// Converts a checked failure into a runtime exception without losing existing runtime failures.
    ///
    /// @param failure failure to convert
    /// @return checked failure as runtime failure
    private static RuntimeException unchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new java.io.UncheckedIOException(
                new java.io.IOException("Shader-pack deletion failed", failure));
    }

    /// Returns a non-blank failure detail.
    ///
    /// @param failure failure to describe
    /// @return failure message or class name
    private static String detail(Throwable failure) {
        @Nullable String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    /// Blocking mutation runnable.
    @FunctionalInterface
    @NotNullByDefault
    private interface Mutation {
        /// Runs one mutation.
        ///
        /// @return resulting snapshot
        /// @throws java.io.IOException when disk access fails
        ShaderPackCatalogSnapshot run() throws java.io.IOException;
    }
}

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.theme.ThemeReference;
import space.minecraftstl.xyml.ui.swing.choice.IndexRange;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies asynchronous generations, permissions, failures, filtering, and terminal closure.
@NotNullByDefault
public final class ThemePackManagementModelTest {
    /// Temporary installed-directory identities used without touching their contents.
    @TempDir
    private Path temporaryDirectory;

    /// A newer refresh wins and a completion arriving after close cannot restore inventory state.
    @Test
    public void discardsStaleRefreshAndCompletionAfterClose() {
        FakeBackend backend = new FakeBackend();
        CompletableFuture<@Unmodifiable List<ThemePackItem>> first = backend.enqueueLoad();
        CompletableFuture<@Unmodifiable List<ThemePackItem>> second = backend.enqueueLoad();
        CompletableFuture<@Unmodifiable List<ThemePackItem>> third = backend.enqueueLoad();
        ThemePackManagementModel model = model(backend, new RecordingApplication());

        var firstStage = model.refresh();
        var secondStage = model.refresh();
        ThemePackItem newer = builtin("xyml.default", null, "Newer");
        second.complete(List.of(newer));
        assertEquals(List.of(newer), secondStage.toCompletableFuture().join().items());

        first.complete(List.of(builtin("xyml.classic", null, "Stale")));
        assertEquals(List.of(newer), firstStage.toCompletableFuture().join().items());
        assertEquals(List.of(newer), model.snapshot().items());

        var thirdStage = model.refresh();
        model.close();
        third.complete(List.of(builtin("xyml.classic", null, "Late")));
        assertEquals(ThemePackManagementStatus.CLOSED, thirdStage.toCompletableFuture().join().status());
        assertTrue(model.snapshot().items().isEmpty());
        assertThrows(IllegalStateException.class, model::refresh);
    }

    /// Applies exact references and permits deletion only for a current installed package that is not active.
    @Test
    public void enforcesApplyAndDeleteAuthorization() {
        FakeBackend backend = new FakeBackend();
        CompletableFuture<@Unmodifiable List<ThemePackItem>> load = backend.enqueueLoad();
        CompletableFuture<@Nullable Void> deletion = backend.enqueueDelete();
        RecordingApplication application = new RecordingApplication();
        ThemeReference builtinReference = new ThemeReference("xyml.default", null);
        ThemePackManagementModel model = new ThemePackManagementModel(
                backend,
                application,
                Runnable::run,
                builtinReference);
        ThemePackItem builtin = builtin("xyml.default", null, "Default");
        ThemePackItem installedFirst = installed("example.local", "soft", "Soft");
        ThemePackItem installedSecond = installed("example.local", "sharp", "Sharp");

        model.refresh();
        load.complete(List.of(builtin, installedFirst, installedSecond));
        assertFalse(model.canDelete(builtin));
        assertTrue(model.canDelete(installedFirst));
        assertThrows(IllegalStateException.class, () -> model.delete(builtin));

        model.apply(installedSecond).toCompletableFuture().join();
        assertEquals(List.of(installedSecond.reference()), application.references);
        assertEquals(installedSecond.reference(), model.snapshot().appliedTheme());
        assertFalse(model.canDelete(installedFirst));

        model.setAppliedTheme(builtinReference);
        var deleteStage = model.delete(installedFirst);
        assertEquals(installedFirst, backend.deletedItem);
        deletion.complete(null);
        assertEquals(List.of(builtin), deleteStage.toCompletableFuture().join().items());
        model.close();
    }

    /// Import failures preserve the loaded index and search operates only on that lightweight metadata.
    @Test
    public void retainsInventoryAfterImportFailureAndFiltersViewportSlices() {
        FakeBackend backend = new FakeBackend();
        CompletableFuture<@Unmodifiable List<ThemePackItem>> load = backend.enqueueLoad();
        CompletableFuture<@Unmodifiable List<ThemePackItem>> importFuture = backend.enqueueImport();
        ThemePackManagementModel model = model(backend, new RecordingApplication());
        ThemePackItem first = builtin("xyml.default", "bright", "Bright default");
        ThemePackItem second = builtin("xyml.classic", "orange", "Classic orange");

        model.refresh();
        load.complete(List.of(first, second));
        model.setQuery("orange");
        assertEquals(List.of(second), model.snapshot().items());
        assertEquals(2, model.snapshot().totalItemCount());
        var page = model.load(new IndexRange(0, 1), new LoadCancellation()).toCompletableFuture().join();
        assertEquals(List.of(second), page.items());

        var importStage = model.importArchive(temporaryDirectory.resolve("duplicate.xyml-theme"));
        importFuture.completeExceptionally(new FileAlreadyExistsException("example.local"));
        ThemePackManagementSnapshot failed = importStage.toCompletableFuture().join();
        assertEquals(ThemePackManagementStatus.FAILED, failed.status());
        assertEquals(List.of(second), failed.items());
        assertEquals("example.local", failed.failureMessage());
        assertThrows(
                IllegalArgumentException.class,
                () -> model.importArchive(temporaryDirectory.resolve("wrong.zip")));
        model.close();
    }

    /// Creates a model whose fake backend and application complete without additional scheduling.
    private static ThemePackManagementModel model(
            FakeBackend backend,
            RecordingApplication application) {
        return new ThemePackManagementModel(backend, application, Runnable::run, null);
    }

    /// Creates one embedded test item.
    private static ThemePackItem builtin(String packageId, @Nullable String themeId, String name) {
        return new ThemePackItem(
                new ThemeReference(packageId, themeId),
                name,
                "Built-in package",
                "1.0.0",
                "XYML",
                null,
                true,
                null);
    }

    /// Creates one installed test item under the temporary directory.
    private ThemePackItem installed(String packageId, String themeId, String name) {
        return new ThemePackItem(
                new ThemeReference(packageId, themeId),
                name,
                "Local package",
                "2.0.0",
                "Theme author",
                "Local theme",
                false,
                temporaryDirectory.resolve(packageId));
    }

    /// Fake backend exposing independently controllable asynchronous results.
    @NotNullByDefault
    private static final class FakeBackend implements ThemePackManagementBackend {
        /// Queued inventory completions.
        private final ArrayDeque<CompletableFuture<@Unmodifiable List<ThemePackItem>>> loads = new ArrayDeque<>();

        /// Queued import completions.
        private final ArrayDeque<CompletableFuture<@Unmodifiable List<ThemePackItem>>> imports = new ArrayDeque<>();

        /// Queued deletion completions.
        private final ArrayDeque<CompletableFuture<@Nullable Void>> deletions = new ArrayDeque<>();

        /// Exact item passed to the latest delete request, or `null`.
        private @Nullable ThemePackItem deletedItem;

        /// Enqueues one controllable load completion.
        private CompletableFuture<@Unmodifiable List<ThemePackItem>> enqueueLoad() {
            CompletableFuture<@Unmodifiable List<ThemePackItem>> future = new CompletableFuture<>();
            loads.add(future);
            return future;
        }

        /// Enqueues one controllable import completion.
        private CompletableFuture<@Unmodifiable List<ThemePackItem>> enqueueImport() {
            CompletableFuture<@Unmodifiable List<ThemePackItem>> future = new CompletableFuture<>();
            imports.add(future);
            return future;
        }

        /// Enqueues one controllable delete completion.
        private CompletableFuture<@Nullable Void> enqueueDelete() {
            CompletableFuture<@Nullable Void> future = new CompletableFuture<>();
            deletions.add(future);
            return future;
        }

        /// Returns the next controllable inventory completion.
        @Override
        public CompletionStage<@Unmodifiable List<ThemePackItem>> loadAll(Executor executor) {
            return Objects.requireNonNull(loads.poll(), "No load completion was queued");
        }

        /// Returns the next controllable import completion.
        @Override
        public CompletionStage<@Unmodifiable List<ThemePackItem>> importArchive(Path archive, Executor executor) {
            return Objects.requireNonNull(imports.poll(), "No import completion was queued");
        }

        /// Records the item and returns the next controllable delete completion.
        @Override
        public CompletionStage<@Nullable Void> deleteInstalled(ThemePackItem item, Executor executor) {
            deletedItem = item;
            return Objects.requireNonNull(deletions.poll(), "No delete completion was queued");
        }

        /// Returns the item's test directory without filesystem access.
        @Override
        public CompletionStage<Path> locateInstalled(ThemePackItem item, Executor executor) {
            return CompletableFuture.completedFuture(Objects.requireNonNull(
                    item.installedDirectory(),
                    "installedDirectory"));
        }
    }

    /// Application callback recording exact references.
    @NotNullByDefault
    private static final class RecordingApplication implements ThemePackApplication {
        /// Exact references applied in call order.
        private final List<ThemeReference> references = new ArrayList<>();

        /// Records and immediately completes one application request.
        @Override
        public CompletionStage<@Nullable Void> apply(ThemeReference reference) {
            references.add(Objects.requireNonNull(reference, "reference"));
            return CompletableFuture.completedFuture(null);
        }
    }
}

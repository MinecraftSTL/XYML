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
package space.minecraftstl.xyml.ui.swing.page.mods;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.addon.mod.ModLoaderType;
import space.minecraftstl.xyml.ui.swing.choice.ChoicePage;
import space.minecraftstl.xyml.ui.swing.choice.IndexRange;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Headless tests for background indexing, filtering, viewport materialization, and mutations.
@NotNullByDefault
public final class DefaultModCatalogModelTest {
    /// Deterministic localized status presentation.
    private static final ModCatalogStatusStrings STATUS_STRINGS = new ModCatalogStatusStrings(
            "Loading Mods",
            "No Mods",
            "%d Mods",
            "Load failed: %s",
            "Importing Mods",
            "Enabling Mod",
            "Disabling Mod",
            "Deleting Mod",
            "Write failed: %s");

    /// Verifies that EDT commands only enqueue work and range loads materialize exact slices.
    @Test
    public void refreshesOffEdtAndLoadsOnlyRequestedViewport() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        FakeAccess access = new FakeAccess(entries(20));
        DefaultModCatalogModel model = new DefaultModCatalogModel(access, executor, STATUS_STRINGS);

        SwingUtilities.invokeAndWait(model::loadIfNeeded);
        assertEquals(ModCatalogStatus.LOADING, model.snapshot().status());
        assertEquals(0, access.refreshCount());

        executor.runNext();
        assertEquals(ModCatalogStatus.READY, model.snapshot().status());
        assertEquals(20, model.exactItemCount().orElseThrow());
        assertEquals(1, access.refreshCount());

        CompletionStage<ChoicePage<ModCatalogItem>> load = model.load(
                new IndexRange(5, 8), new LoadCancellation());
        assertFalse(load.toCompletableFuture().isDone());
        executor.runNext();

        ChoicePage<ModCatalogItem> page = load.toCompletableFuture().join();
        assertEquals(new IndexRange(5, 8), page.range());
        assertEquals(List.of("mod-5", "mod-6", "mod-7"),
                page.items().stream().map(ModCatalogItem::localKey).toList());
        assertEquals(List.of("mod-5", "mod-6", "mod-7"), access.lastLoadedKeys());
        model.close();
    }

    /// Verifies that query and enabled-state filtering reuse the current immutable disk index.
    @Test
    public void filtersSearchAndEnabledStateWithoutRefreshingDisk() {
        ManualExecutor executor = new ManualExecutor();
        FakeAccess access = new FakeAccess(List.of(
                entry("sodium", "Sodium Renderer", true, ModLoaderType.FABRIC),
                entry("fabric-api", "Fabric API", false, ModLoaderType.FABRIC),
                entry("iris", "Iris Shaders", true, ModLoaderType.FABRIC)));
        DefaultModCatalogModel model = new DefaultModCatalogModel(access, executor, STATUS_STRINGS);

        model.loadIfNeeded();
        executor.runNext();
        model.setSearchQuery("fabric-api");
        assertEquals(1, model.exactItemCount().orElseThrow());
        assertEquals(List.of("fabric-api"), model.filteredLocalKeys());
        assertEquals(1, access.refreshCount());

        model.setFilter(ModCatalogFilter.DISABLED);
        assertEquals(1, model.exactItemCount().orElseThrow());
        model.setSearchQuery("");
        assertEquals(1, model.exactItemCount().orElseThrow());
        assertEquals(ModCatalogFilter.DISABLED, model.snapshot().filter());
        assertEquals(1, access.refreshCount());
        model.close();
    }

    /// Verifies that stable-key batches use one serialized mutation and one refreshed index each.
    @Test
    public void mutatesStableKeyBatchesWithSingleAccessRoundTrips() {
        ManualExecutor executor = new ManualExecutor();
        FakeAccess access = new FakeAccess(entries(4));
        DefaultModCatalogModel model = new DefaultModCatalogModel(access, executor, STATUS_STRINGS);
        model.loadIfNeeded();
        executor.runNext();
        assertEquals(List.of("mod-0", "mod-1", "mod-2", "mod-3"), model.filteredLocalKeys());

        CompletionStage<ModCatalogSnapshot> disabled = model.setModsEnabled(
                List.of("mod-0", "mod-2"),
                false);
        assertEquals(ModCatalogWriteStatus.BUSY, model.snapshot().writeStatus());
        executor.runNext();
        assertEquals(ModCatalogWriteStatus.IDLE, disabled.toCompletableFuture().join().writeStatus());
        ModCatalogMutation.EnabledBatch enabledBatch = assertInstanceOf(
                ModCatalogMutation.EnabledBatch.class,
                access.mutations().get(0));
        assertEquals(List.of("mod-0", "mod-2"), enabledBatch.localKeys());
        assertFalse(enabledBatch.enabled());

        CompletionStage<ModCatalogSnapshot> deleted = model.deleteMods(List.of("mod-1", "mod-3"));
        executor.runNext();
        assertEquals(2, deleted.toCompletableFuture().join().itemCount().orElseThrow());
        ModCatalogMutation.DeleteBatch deleteBatch = assertInstanceOf(
                ModCatalogMutation.DeleteBatch.class,
                access.mutations().get(1));
        assertEquals(List.of("mod-1", "mod-3"), deleteBatch.localKeys());
        assertEquals(List.of("mod-0", "mod-2"), model.filteredLocalKeys());
        assertEquals(2, access.mutations().size());
        model.close();
    }

    /// Verifies serialized mutations, actual refresh, and selection across disabled renames.
    @Test
    public void mutatesThroughStableKeysAndPreservesSelectionAcrossRename() {
        ManualExecutor executor = new ManualExecutor();
        FakeAccess access = new FakeAccess(List.of(
                entry("alpha", "Alpha", true, ModLoaderType.FORGE),
                entry("beta", "Beta", true, ModLoaderType.FABRIC)));
        DefaultModCatalogModel model = new DefaultModCatalogModel(access, executor, STATUS_STRINGS);
        model.loadIfNeeded();
        executor.runNext();
        model.selectMod("beta");

        CompletionStage<ModCatalogSnapshot> disable = model.setModEnabled("beta", false);
        assertEquals(ModCatalogWriteStatus.BUSY, model.snapshot().writeStatus());
        CompletionStage<ModCatalogSnapshot> overlappingDelete = model.deleteMod("alpha");
        assertThrows(CompletionException.class, () -> overlappingDelete.toCompletableFuture().join());
        executor.runNext();

        assertEquals(ModCatalogWriteStatus.IDLE, disable.toCompletableFuture().join().writeStatus());
        assertEquals(1, model.snapshot().selectedIndex().orElseThrow());
        assertInstanceOf(ModCatalogMutation.Enabled.class, access.mutations().get(0));

        CompletionStage<ChoicePage<ModCatalogItem>> load = model.load(
                new IndexRange(1, 2), new LoadCancellation());
        executor.runNext();
        ModCatalogItem beta = load.toCompletableFuture().join().items().get(0);
        assertFalse(beta.enabled());
        assertTrue(beta.fileName().endsWith(".disabled"));

        CompletionStage<ModCatalogSnapshot> imported = model.importMods(List.of(Path.of("gamma.jar")));
        executor.runNext();
        assertEquals(3, imported.toCompletableFuture().join().itemCount().orElseThrow());

        CompletionStage<ModCatalogSnapshot> deleted = model.deleteMod("alpha");
        executor.runNext();
        assertEquals(2, deleted.toCompletableFuture().join().itemCount().orElseThrow());
        assertEquals(3, access.mutations().size());
        model.close();
    }

    /// Verifies that closure invalidates queued refresh publication and later commands.
    @Test
    public void closeRejectsQueuedAndFutureWork() {
        ManualExecutor executor = new ManualExecutor();
        FakeAccess access = new FakeAccess(entries(2));
        DefaultModCatalogModel model = new DefaultModCatalogModel(access, executor, STATUS_STRINGS);

        model.loadIfNeeded();
        model.close();
        executor.runNext();

        assertEquals(ModCatalogStatus.LOADING, model.snapshot().status());
        assertThrows(IllegalStateException.class, model::refresh);
        CompletionStage<ChoicePage<ModCatalogItem>> load = model.load(
                new IndexRange(0, 1), new LoadCancellation());
        assertThrows(CompletionException.class, () -> load.toCompletableFuture().join());
    }

    /// Creates deterministic indexed entries.
    ///
    /// @param count entry count
    /// @return immutable entries
    private static @Unmodifiable List<ModCatalogEntry> entries(int count) {
        List<ModCatalogEntry> entries = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            entries.add(entry(
                    "mod-" + index,
                    "Mod " + index,
                    index % 2 == 0,
                    ModLoaderType.FABRIC));
        }
        return List.copyOf(entries);
    }

    /// Creates one internal entry with deterministic metadata.
    ///
    /// @param localKey stable key
    /// @param name display name
    /// @param enabled actual state
    /// @param loaderType detected loader
    /// @return immutable entry
    private static ModCatalogEntry entry(
            String localKey,
            String name,
            boolean enabled,
            ModLoaderType loaderType) {
        String fileName = localKey + ".jar" + (enabled ? "" : ".disabled");
        return new ModCatalogEntry(
                localKey,
                Path.of("mods", fileName),
                localKey,
                name,
                "Description for " + name,
                "Author",
                "1.0",
                "1.21.1",
                loaderType,
                fileName,
                enabled);
    }

    /// Queue-backed executor whose tasks run only when explicitly requested by a test.
    @NotNullByDefault
    private static final class ManualExecutor implements Executor {
        /// Pending tasks in submission order.
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        /// Enqueues one task without running it.
        @Override
        public void execute(Runnable command) {
            tasks.add(Objects.requireNonNull(command));
        }

        /// Runs the next pending task on the non-EDT test thread.
        private void runNext() {
            Runnable task = tasks.remove();
            task.run();
        }
    }

    /// Deterministic mutable access boundary modeling actual post-mutation refresh results.
    @NotNullByDefault
    private static final class FakeAccess implements ModCatalogAccess {
        /// Current complete index.
        private @Unmodifiable List<ModCatalogEntry> entries;

        /// Applied mutations in order.
        private final List<ModCatalogMutation> mutations = new ArrayList<>();

        /// Stable keys from the latest exact viewport request.
        private @Unmodifiable List<String> lastLoadedKeys = List.of();

        /// Number of full refresh invocations.
        private int refreshCount;

        /// Creates access with one initial complete index.
        ///
        /// @param entries initial entries
        private FakeAccess(@Unmodifiable List<ModCatalogEntry> entries) {
            this.entries = List.copyOf(entries);
        }

        /// Returns one deterministic normalized directory.
        @Override
        public Path modsDirectory() {
            return Path.of("mods").toAbsolutePath().normalize();
        }

        /// Returns the current complete index and records the refresh.
        @Override
        public ModCatalogIndex refresh(LoadCancellation cancellation) {
            cancellation.throwIfCancelled();
            refreshCount++;
            return new ModCatalogIndex(entries);
        }

        /// Materializes exactly the supplied entries and records their keys.
        @Override
        public @Unmodifiable List<ModCatalogItem> loadItems(
                @Unmodifiable List<ModCatalogEntry> requested,
                LoadCancellation cancellation) {
            cancellation.throwIfCancelled();
            lastLoadedKeys = requested.stream().map(ModCatalogEntry::localKey).toList();
            return requested.stream().map(ModCatalogEntry::toItem).toList();
        }

        /// Applies one deterministic mutation and returns its actual replacement index.
        @Override
        public ModCatalogMutationResult mutateAndRefresh(
                ModCatalogMutation mutation,
                LoadCancellation cancellation) {
            cancellation.throwIfCancelled();
            mutations.add(mutation);
            List<ModCatalogEntry> replacement = new ArrayList<>(entries);
            if (mutation instanceof ModCatalogMutation.Enabled enabledMutation) {
                for (int index = 0; index < replacement.size(); index++) {
                    ModCatalogEntry current = replacement.get(index);
                    if (current.localKey().equals(enabledMutation.localKey())) {
                        replacement.set(index, entry(
                                current.localKey(),
                                current.name(),
                                enabledMutation.enabled(),
                                current.loaderType()));
                    }
                }
            } else if (mutation instanceof ModCatalogMutation.EnabledBatch enabledBatch) {
                for (int index = 0; index < replacement.size(); index++) {
                    ModCatalogEntry current = replacement.get(index);
                    if (enabledBatch.localKeys().contains(current.localKey())) {
                        replacement.set(index, entry(
                                current.localKey(),
                                current.name(),
                                enabledBatch.enabled(),
                                current.loaderType()));
                    }
                }
            } else if (mutation instanceof ModCatalogMutation.Import importMutation) {
                for (Path source : importMutation.sources()) {
                    String fileName = source.getFileName().toString();
                    String localKey = fileName.substring(0, fileName.lastIndexOf('.'));
                    replacement.add(entry(localKey, localKey, true, ModLoaderType.UNKNOWN));
                }
            } else if (mutation instanceof ModCatalogMutation.Delete deleteMutation) {
                replacement.removeIf(entry -> entry.localKey().equals(deleteMutation.localKey()));
            } else if (mutation instanceof ModCatalogMutation.DeleteBatch deleteBatch) {
                replacement.removeIf(entry -> deleteBatch.localKeys().contains(entry.localKey()));
            }
            entries = List.copyOf(replacement);
            return new ModCatalogMutationResult(new ModCatalogIndex(entries), null);
        }

        /// Returns how many full refreshes occurred.
        ///
        /// @return refresh count
        private int refreshCount() {
            return refreshCount;
        }

        /// Returns keys from the latest exact range materialization.
        ///
        /// @return immutable loaded keys
        private @Unmodifiable List<String> lastLoadedKeys() {
            return lastLoadedKeys;
        }

        /// Returns applied mutations in order.
        ///
        /// @return immutable mutation list
        private @Unmodifiable List<ModCatalogMutation> mutations() {
            return List.copyOf(mutations);
        }
    }
}

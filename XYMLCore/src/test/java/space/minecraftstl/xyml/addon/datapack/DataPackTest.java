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
package space.minecraftstl.xyml.addon.datapack;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.observable.Subscription;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies toolkit-neutral data-pack snapshots, synchronous publication, deletion, and active-state transitions.
@NotNullByDefault
final class DataPackTest {
    /// Temporary world data-pack directory.
    @TempDir
    private Path temporaryDirectory;

    /// Loads sorted immutable snapshots, retains old snapshots, and publishes on the mutating thread.
    @Test
    void loadsAndPublishesImmutableSnapshotsOnCallingThread() throws IOException {
        writeDirectoryPack("zeta", true);
        writeDirectoryPack("Alpha", false);
        DataPack dataPack = new DataPack(temporaryDirectory);
        List<Thread> notificationThreads = new ArrayList<>();
        List<@Unmodifiable List<DataPack.Pack>> snapshots = new ArrayList<>();
        Subscription subscription = dataPack.subscribePacks(change -> {
            notificationThreads.add(Thread.currentThread());
            snapshots.add(change.currentValue());
        });

        dataPack.loadFromDir();

        @Unmodifiable List<DataPack.Pack> firstSnapshot = dataPack.getPacks();
        assertEquals(List.of("Alpha", "zeta"), ids(firstSnapshot));
        assertThrows(UnsupportedOperationException.class, () -> firstSnapshot.remove(0));
        assertEquals(List.of(Thread.currentThread()), notificationThreads);
        assertSame(firstSnapshot, snapshots.get(0));

        writeDirectoryPack("middle", true);
        dataPack.loadFromDir();

        assertEquals(List.of("Alpha", "zeta"), ids(firstSnapshot));
        assertEquals(List.of("Alpha", "middle", "zeta"), ids(dataPack.getPacks()));
        assertEquals(List.of(Thread.currentThread(), Thread.currentThread()), notificationThreads);
        subscription.unsubscribe();
    }

    /// Deletes files and replaces the visible snapshot before returning to the caller.
    @Test
    void deletesPackAndPublishesRemovalSynchronously() throws IOException {
        Path packDirectory = writeDirectoryPack("delete-me", true);
        DataPack dataPack = new DataPack(temporaryDirectory);
        dataPack.loadFromDir();
        DataPack.Pack pack = dataPack.getPacks().get(0);
        List<@Unmodifiable List<DataPack.Pack>> snapshots = new ArrayList<>();
        dataPack.subscribePacks(change -> snapshots.add(change.currentValue()));

        dataPack.deletePack(pack);

        assertFalse(Files.exists(packDirectory));
        assertTrue(dataPack.getPacks().isEmpty());
        assertEquals(1, snapshots.size());
        assertTrue(snapshots.get(0).isEmpty());
    }

    /// Active state uses plain Core state, renames metadata, and publishes synchronously without JavaFX properties.
    @Test
    void changesActiveStateWithoutJavaFxProperty() throws IOException {
        Path packDirectory = writeDirectoryPack("toggle-me", true);
        DataPack dataPack = new DataPack(temporaryDirectory);
        dataPack.loadFromDir();
        DataPack.Pack pack = dataPack.getPacks().get(0);
        List<Boolean> activeStates = new ArrayList<>();
        List<Thread> notificationThreads = new ArrayList<>();
        pack.subscribeActive(change -> {
            activeStates.add(change.currentValue());
            notificationThreads.add(Thread.currentThread());
        });

        pack.setActive(false);

        assertFalse(pack.isActive());
        assertEquals(packDirectory, pack.getPath());
        assertFalse(Files.exists(packDirectory.resolve("pack.mcmeta")));
        assertTrue(Files.exists(packDirectory.resolve("pack.mcmeta.disabled")));

        pack.setActive(true);

        assertTrue(pack.isActive());
        assertTrue(Files.exists(packDirectory.resolve("pack.mcmeta")));
        assertEquals(List.of(false, true), activeStates);
        assertEquals(List.of(Thread.currentThread(), Thread.currentThread()), notificationThreads);
    }

    /// Writes one valid directory data pack in its enabled or disabled state.
    ///
    /// @param name directory name and stable pack identifier
    /// @param active whether enabled metadata should be written
    /// @return created pack directory
    /// @throws IOException when the fixture cannot be written
    private Path writeDirectoryPack(String name, boolean active) throws IOException {
        Path directory = Files.createDirectories(temporaryDirectory.resolve(name));
        String metadataName = active ? "pack.mcmeta" : "pack.mcmeta.disabled";
        Files.writeString(directory.resolve(metadataName), packMetadata());
        return directory;
    }

    /// Returns deterministic valid pack metadata.
    ///
    /// @return pack metadata JSON
    private static String packMetadata() {
        return "{\"pack\":{\"pack_format\":15,\"description\":\"fixture\"}}";
    }

    /// Extracts stable identifiers from an immutable snapshot.
    ///
    /// @param packs immutable pack snapshot
    /// @return immutable identifiers in snapshot order
    private static @Unmodifiable List<String> ids(
            @Unmodifiable List<DataPack.Pack> packs) {
        return packs.stream().map(DataPack.Pack::getId).toList();
    }
}

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
package space.minecraftstl.xyml.ui.swing.page.instances.management.servers;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameRepository;
import space.minecraftstl.xyml.library.nbt.io.NBTFile;
import space.minecraftstl.xyml.library.nbt.tag.CompoundTag;
import space.minecraftstl.xyml.library.nbt.tag.ListTag;
import space.minecraftstl.xyml.library.nbt.tag.TagType;
import space.minecraftstl.xyml.ui.swing.choice.CatalogLayoutAssertions;

import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Exercises the production panel and real NBT files with an injected instance-directory resolver.
///
/// This fixture does not launch Minecraft, open network connections, or alter user game data.
@NotNullByDefault
final class ServerCatalogDirectoryIntegrationTest {
    /// Missing isolated files never fall back to a populated global list or create placeholder entries.
    ///
    /// @param directory temporary repository boundary
    /// @throws Exception when fixture I/O or Swing dispatch fails
    @Test
    void missingIsolatedFileDoesNotLoadGlobalServers(@TempDir Path directory) throws Exception {
        Path global = directory.resolve("global");
        Path isolated = directory.resolve("instance");
        writeServers(global, "Global A", "Global B");
        byte[] globalBefore = Files.readAllBytes(global.resolve("servers.dat"));
        ManualExecutor executor = new ManualExecutor();
        ServerCatalogPanel panel = open(repository(global, new AtomicReference<>(isolated)), executor);
        try {
            executor.runAll();
            SwingUtilities.invokeAndWait(() -> assertEquals(0, panel.serverList().getModel().getSize()));
            assertFalse(Files.exists(isolated.resolve("servers.dat")));
            assertArrayEquals(globalBefore, Files.readAllBytes(global.resolve("servers.dat")));
        } finally {
            SwingUtilities.invokeAndWait(panel::close);
        }
    }

    /// Reordering affects only the captured instance file; a recreated page resolves a changed directory.
    ///
    /// @param directory temporary repository boundary
    /// @throws Exception when fixture I/O or Swing dispatch fails
    @Test
    void reordersIsolatedListAndReopensChangedDirectory(@TempDir Path directory) throws Exception {
        Path global = directory.resolve("global");
        Path isolated = directory.resolve("instance");
        Path custom = directory.resolve("custom");
        writeServers(global, "Global A", "Global B");
        writeServers(isolated, "Isolated A", "Isolated B");
        writeServers(custom, "Custom");
        byte[] globalBefore = Files.readAllBytes(global.resolve("servers.dat"));
        AtomicReference<Path> selectedDirectory = new AtomicReference<>(isolated);
        GameRepository repository = repository(global, selectedDirectory);
        ManualExecutor executor = new ManualExecutor();
        ServerCatalogPanel panel = open(repository, executor);
        try {
            executor.runAll();
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(isolated.resolve("servers.dat").toAbsolutePath().normalize().toString(),
                        CatalogLayoutAssertions.requireNamed(panel, "serverCatalogSourceFile", JTextArea.class).getText());
                assertEquals("Isolated A", panel.serverList().getModel().getElementAt(0).name());
                assertEquals("Isolated B", panel.serverList().getModel().getElementAt(1).name());
                ServerCatalogReorderSupport handler = assertInstanceOf(ServerCatalogReorderSupport.class,
                        panel.serverList().getTransferHandler());
                java.awt.datatransfer.Transferable transfer = handler.createTransferable(panel.serverList());
                assertNotNull(transfer);
                assertTrue(handler.drop(transfer, 2));
            });
            executor.runAll();
            SwingUtilities.invokeAndWait(() -> assertEquals(
                    "Isolated B", panel.serverList().getModel().getElementAt(0).name()));
            try (NBTFile<CompoundTag> nbt = NBTFile.openTag(isolated.resolve("servers.dat"), TagType.COMPOUND)) {
                CompoundTag root = nbt.getEditor().getRootSnapshot();
                assertEquals("preserved root metadata", root.getStringOrEmpty("unrelated"));
                ListTag<?> entries = assertInstanceOf(ListTag.class, root.get("servers"));
                assertEquals("Isolated B", assertInstanceOf(CompoundTag.class, entries.getTag(0)).getStringOrEmpty("name"));
                assertEquals("preserved entry metadata",
                        assertInstanceOf(CompoundTag.class, entries.getTag(0)).getStringOrEmpty("extra"));
            }
        } finally {
            SwingUtilities.invokeAndWait(panel::close);
        }
        selectedDirectory.set(custom);
        ServerCatalogPanel recreated = open(repository, executor);
        try {
            executor.runAll();
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(1, recreated.serverList().getModel().getSize());
                assertEquals("Custom", recreated.serverList().getModel().getElementAt(0).name());
            });
        } finally {
            SwingUtilities.invokeAndWait(recreated::close);
        }
        assertArrayEquals(globalBefore, Files.readAllBytes(global.resolve("servers.dat")));
    }

    /// Constructs the actual public panel without executing its file read on the EDT.
    ///
    /// @param repository injectable directory resolver
    /// @param executor deferred storage worker
    /// @return constructed panel owned by the caller
    /// @throws Exception when Swing dispatch fails
    private static ServerCatalogPanel open(GameRepository repository, Executor executor) throws Exception {
        AtomicReference<@Nullable ServerCatalogPanel> panel = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> panel.set(new ServerCatalogPanel(
                repository, new GameInstanceID("managed-instance"), executor)));
        return Objects.requireNonNull(panel.get());
    }

    /// Injects only directory resolution; any unexpected repository interaction fails the fixture.
    ///
    /// @param global repository-wide directory containing unrelated entries
    /// @param selectedDirectory current effective isolated or custom directory
    /// @return repository test double
    private static GameRepository repository(Path global, AtomicReference<Path> selectedDirectory) {
        return (GameRepository) Proxy.newProxyInstance(GameRepository.class.getClassLoader(),
                new Class<?>[] {GameRepository.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("getRunDirectory")) {
                        assertEquals(new GameInstanceID("managed-instance"), Objects.requireNonNull(arguments)[0]);
                        return selectedDirectory.get();
                    }
                    if (method.getName().equals("getBaseDirectory")) return global;
                    throw new AssertionError("Unexpected repository call: " + method.getName());
                });
    }

    /// Writes independent fixture files with root and entry metadata that must survive ordering changes.
    ///
    /// @param directory fixture run directory
    /// @param names fixture entry names in stored order
    /// @throws IOException when the fixture file cannot be written
    private static void writeServers(Path directory, String... names) throws IOException {
        Files.createDirectories(directory);
        ListTag<CompoundTag> entries = new ListTag<>(TagType.COMPOUND).setName("servers");
        for (String name : names) {
            entries.addTag(new CompoundTag().addString("name", name).addString("ip", "example.test")
                    .addString("extra", "preserved entry metadata"));
        }
        CompoundTag root = new CompoundTag().addString("unrelated", "preserved root metadata").addTag(entries);
        try (NBTFile<CompoundTag> nbt = NBTFile.createTag(directory.resolve("servers.dat"))) {
            try {
                nbt.getEditor().replaceContent(nbt.getEditor().rootNode(), root);
            } catch (space.minecraftstl.xyml.library.nbt.edit.NBTEditException failure) {
                throw new IOException("Cannot create test fixture", failure);
            }
            nbt.save();
        }
    }

    /// Deterministic worker executor; callers run storage operations outside the EDT.
    @NotNullByDefault
    private static final class ManualExecutor implements Executor {
        /// Worker operations queued in submission order.
        private final Queue<Runnable> commands = new ArrayDeque<>();

        /// Queues one storage operation without running it on the submitting thread.
        ///
        /// @param command storage operation
        @Override
        public void execute(Runnable command) {
            commands.add(command);
        }

        /// Runs all storage operations, including ordered follow-up operations.
        private void runAll() {
            assertFalse(SwingUtilities.isEventDispatchThread());
            while (!commands.isEmpty()) commands.remove().run();
        }
    }
}

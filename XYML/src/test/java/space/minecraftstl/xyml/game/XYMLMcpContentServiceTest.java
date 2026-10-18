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
package space.minecraftstl.xyml.game;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.mcp.McpDeletionConfirmation;
import space.minecraftstl.xyml.mcp.XYMLMcpContentOperations;
import space.minecraftstl.xyml.mcp.XYMLMcpService;
import space.minecraftstl.xyml.setting.GameDirectory;
import space.minecraftstl.xyml.setting.GameDirectoryID;
import space.minecraftstl.xyml.setting.GameSettings;
import space.minecraftstl.xyml.setting.LauncherSettings;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.ui.swing.page.instances.management.servers.FileSystemServerCatalogAccess;
import space.minecraftstl.xyml.ui.swing.page.instances.management.servers.ServerCatalogItem;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.util.i18n.LocalizedText;
import space.minecraftstl.xyml.util.PortablePath;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Integration coverage for headless content adapters, revision checks, ownership, and cancellation.
@NotNullByDefault
final class XYMLMcpContentServiceTest {
    /// Lists all five new categories without invoking any destructive confirmation.
    @Test
    void listsAllFiveManagedCatalogs(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root, request -> { throw new AssertionError("Read-only tool asked for approval"); });
        Path run = fixture.repository().getRunDirectory(fixture.id());
        Path pack = Files.createDirectories(fixture.repository().getResourcePackDirectory(fixture.id()).resolve("pack"));
        Files.writeString(pack.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":15,\"description\":\"test\"}}");
        Path world = Files.createDirectories(run.resolve("saves/world"));
        Path shader = Files.createDirectories(run.resolve("shaderpacks/shader/shaders"));
        Files.writeString(shader.resolve("main.fsh"), "void main() {}");
        Path schematics = Files.createDirectories(run.resolve("schematics/folder"));
        Files.writeString(schematics.resolve("one.litematic"), "schematic fixture");
        Files.writeString(run.resolve("schematics/ignored.txt"), "not a schematic");
        FileSystemServerCatalogAccess servers = new FileSystemServerCatalogAccess(fixture.repository(), fixture.id());
        servers.write(List.of(new ServerCatalogItem("one", "localhost:25565")));
        assertEquals(pack.toString(), run(fixture.content().listResourcePacks("demo")).get(0).get("path"));
        assertEquals(world.toString(), run(fixture.content().listWorlds("demo")).get(0).get("path"));
        assertEquals(shader.getParent().toString(), run(fixture.content().listShaderPacks("demo")).get(0).get("path"));
        assertEquals(1, run(fixture.content().listSchematics("demo", null)).size());
        assertEquals(schematics.resolve("one.litematic").toString(),
                run(fixture.content().listSchematics("demo", schematics.toString())).get(0).get("path"));
        Map<String, Object> serverList = run(fixture.content().listServers("demo"));
        assertFalse(((String) serverList.get("revision")).isBlank());
        assertEquals(1, ((List<?>) serverList.get("items")).size());
    }

    /// Server removal uses list revision plus exact indices, leaving duplicate-address siblings intact.
    @Test
    void serverDeletionRejectsStaleRevisionAndPreservesDuplicateSibling(@TempDir Path root) throws Exception {
        AtomicInteger confirmations = new AtomicInteger();
        Fixture fixture = fixture(root, request -> { confirmations.incrementAndGet(); return true; });
        FileSystemServerCatalogAccess storage = new FileSystemServerCatalogAccess(fixture.repository(), fixture.id());
        storage.write(List.of(new ServerCatalogItem("one", "localhost"), new ServerCatalogItem("two", "localhost")));
        String revision = (String) run(fixture.content().listServers("demo")).get("revision");
        Map<String, Object> result = run(fixture.content().removeServers("demo", List.of(0), revision));
        assertEquals(List.of(0), result.get("removed"));
        assertEquals(List.of("two"), storage.read().stream().map(ServerCatalogItem::name).toList());
        assertFalse(fixture.content().removeServers("demo", List.of(0), revision).executor().test());
        assertEquals(1, confirmations.get());
        assertTrue(Files.isRegularFile(fixture.repository().getRunDirectory(fixture.id()).resolve("servers.dat")));
    }

    /// Removing a stale server snapshot after another writer runs during confirmation cannot overwrite it.
    @Test
    void serverDeletionRejectsChangesDuringConfirmation(@TempDir Path root) throws Exception {
        AtomicReference<Runnable> duringConfirmation = new AtomicReference<>(() -> { });
        Fixture fixture = fixture(root, request -> { duringConfirmation.get().run(); return true; });
        FileSystemServerCatalogAccess storage = new FileSystemServerCatalogAccess(fixture.repository(), fixture.id());
        storage.write(List.of(new ServerCatalogItem("one", "localhost")));
        String revision = (String) run(fixture.content().listServers("demo")).get("revision");
        duringConfirmation.set(() -> {
            try {
                storage.read();
                storage.write(List.of(new ServerCatalogItem("replacement", "example.invalid")));
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        });
        assertFalse(fixture.content().removeServers("demo", List.of(0), revision).executor().test());
        assertEquals("replacement", storage.read().get(0).name());
    }

    /// Schematic overlaps collapse to a single recursive target and cancellation leaves all entries intact.
    @Test
    void schematicOverlapUsesOneApprovalAndRejectsEscape(@TempDir Path root) throws Exception {
        AtomicInteger selectedCount = new AtomicInteger();
        Fixture fixture = fixture(root, request -> { selectedCount.set(request.itemCount()); return false; });
        Path folder = Files.createDirectories(fixture.repository().getRunDirectory(fixture.id()).resolve("schematics/sub"));
        Path file = Files.writeString(folder.resolve("one.litematic"), "fixture");
        Map<String, Object> result = run(fixture.content().removeSchematics(
                "demo", List.of(folder.toString(), file.toString(), file.toString())));
        assertEquals(1, selectedCount.get());
        assertEquals(false, result.get("approved"));
        assertEquals(List.of(folder.toString()), result.get("remaining"));
        assertTrue(Files.exists(file));
        assertFalse(fixture.content().removeSchematics("demo", List.of(root.toString())).executor().test());
        assertFalse(fixture.content().listSchematics("demo", root.toString()).executor().test());
    }

    /// A replaced schematic object after approval is retained, not deleted as if it were the approved file.
    @Test
    void schematicReplacementIsRejectedBeforeStorageMutation(@TempDir Path root) throws Exception {
        AtomicReference<Runnable> replacement = new AtomicReference<>(() -> { });
        Fixture fixture = fixture(root, request -> { replacement.get().run(); return true; });
        Path folder = Files.createDirectories(fixture.repository().getRunDirectory(fixture.id()).resolve("schematics"));
        Path file = Files.writeString(folder.resolve("one.litematic"), "original");
        replacement.set(() -> {
            try {
                Files.move(file, folder.resolve("original.litematic"));
                Files.writeString(file, "replacement");
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        });
        assertFalse(fixture.content().removeSchematics("demo", List.of(file.toString())).executor().test());
        assertEquals("replacement", Files.readString(file));
    }

    /// The owning task's cancellation state prevents side effects after an approval dialog returns.
    @Test
    void cancellationDuringContentConfirmationPreventsMutation(@TempDir Path root) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Fixture fixture = fixture(root, request -> {
            entered.countDown();
            try {
                assertTrue(release.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
            return true;
        });
        Path folder = Files.createDirectories(fixture.repository().getRunDirectory(fixture.id()).resolve("schematics"));
        Path file = Files.writeString(folder.resolve("one.litematic"), "fixture");
        var executor = fixture.content().removeSchematics("demo", List.of(file.toString())).executor();
        CompletableFuture<Boolean> result = CompletableFuture.supplyAsync(executor::test);
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            executor.cancel();
            release.countDown();
            assertFalse(result.get(10, TimeUnit.SECONDS));
            assertTrue(Files.exists(file));
        } finally {
            release.countDown();
        }
    }

    /// Creates a minimal isolated instance whose content is restricted to the temporary fixture.
    /// @param root temporary repository root
    /// @param confirmation scripted launcher decisions
    /// @return installed isolated fixture
    /// @throws IOException when fixture creation fails
    private static Fixture fixture(Path root, McpDeletionConfirmation confirmation) throws IOException {
        XYMLGameRepository repository = new XYMLGameRepository(new GameDirectory(GameDirectoryID.generate(),
                LocalizedText.plain("MCP content tests"), PortablePath.of(root.toString())), new LauncherSettings());
        GameInstanceID id = new GameInstanceID("demo");
        Path manifest = repository.getInstanceJson(id);
        Files.createDirectories(manifest.getParent());
        JsonUtils.writeToJsonFile(manifest, new GameInstanceManifest(id));
        try (java.util.zip.ZipOutputStream jar = new java.util.zip.ZipOutputStream(
                Files.newOutputStream(manifest.resolveSibling(id.id() + ".jar")))) {
            jar.putNextEntry(new java.util.zip.ZipEntry("version.json"));
            jar.write("{\"id\":\"1.20.1\",\"pack_version\":{\"resource\":15}}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        repository.refresh();
        GameSettings.Instance settings = Objects.requireNonNull(repository.createInstanceGameSettings(id));
        settings.getOverrideProperties().add(GameSettings.PROPERTY_RUNNING_DIRECTORY);
        settings.runningDirectoryProperty().setValue("");
        XYMLMcpService service = new XYMLMcpService(repository, confirmation);
        return new Fixture(repository, id, service.contentOperations());
    }

    /// Executes one real task graph and requires a successful immutable result.
    /// @param task unstarted task
    /// @param <T> result type
    /// @return non-null task result
    private static <T> T run(Task<T> task) {
        assertTrue(task.executor().test(), () -> String.valueOf(task.getException()));
        @org.jetbrains.annotations.Nullable T result = task.getResult();
        assertNotNull(result);
        return Objects.requireNonNull(result);
    }

    /// Installed fixture sharing production instance settings and task execution.
    /// @param repository isolated repository
    /// @param id installed instance
    /// @param content installed-content operations
    @NotNullByDefault
    private record Fixture(XYMLGameRepository repository, GameInstanceID id, XYMLMcpContentOperations content) {
        /// Stores non-null fixture components.
        private Fixture {
            Objects.requireNonNull(repository, "repository");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(content, "content");
        }
    }
}

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
package space.minecraftstl.xyml.ui.swing.page.resourcepacks;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.game.GameRepository;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests serialized real-file access, strict persistence, safe staging, and path ownership.
@NotNullByDefault
public final class FileSystemResourcePackCatalogAccessTest {
    /// Maximum time allotted to one deterministic concurrency checkpoint.
    private static final long TIMEOUT_SECONDS = 5L;

    /// Isolated test root.
    @TempDir
    private Path temporaryDirectory;

    /// Imports multiple supported shapes and rejects an existing target without overwriting it.
    @Test
    public void importsMultiplePacksAndNeverOverwritesExistingTarget() throws IOException {
        Fixture fixture = fixture("import");
        Path directorySource = createPackDirectory(
                fixture.root().resolve("sources/alpha"),
                15,
                "Alpha");
        Path zipSource = createPackZip(fixture.root().resolve("sources/beta.zip"), 15, "Beta");
        FileSystemResourcePackCatalogAccess access = fixture.access();
        AtomicInteger commits = new AtomicInteger();

        ResourcePackCatalogMutationAccessResult imported = access.mutateAndLoadIndex(
                new ResourcePackImportMutation(List.of(directorySource, zipSource)),
                new LoadCancellation(),
                commits::incrementAndGet);

        assertAll(
                () -> assertEquals(null, imported.mutationFailure()),
                () -> assertEquals(1, commits.get()),
                () -> assertEquals(
                        List.of(
                                fixture.packDirectory().resolve("alpha"),
                                fixture.packDirectory().resolve("beta.zip")).stream()
                                .map(path -> path.toAbsolutePath().normalize())
                                .toList(),
                        imported.refreshedIndex().paths()),
                () -> assertTrue(Files.isRegularFile(
                        fixture.packDirectory().resolve("alpha/pack.mcmeta"))),
                () -> assertTrue(Files.isRegularFile(
                        fixture.packDirectory().resolve("beta.zip"))));

        byte[] targetBeforeConflict = Files.readAllBytes(
                fixture.packDirectory().resolve("beta.zip"));
        Path conflictSource = fixture.root().resolve("other/beta.zip");
        Files.createDirectories(conflictSource.getParent());
        Files.writeString(conflictSource, "replacement", StandardCharsets.UTF_8);
        ResourcePackCatalogMutationAccessResult conflict = access.mutateAndLoadIndex(
                new ResourcePackImportMutation(List.of(conflictSource)),
                new LoadCancellation(),
                () -> { });

        assertAll(
                () -> assertInstanceOf(
                        java.nio.file.FileAlreadyExistsException.class,
                        conflict.mutationFailure()),
                () -> assertArrayEquals(
                        targetBeforeConflict,
                        Files.readAllBytes(fixture.packDirectory().resolve("beta.zip"))),
                () -> assertFalse(hasStagingArtifact(fixture.packDirectory())));
    }

    /// Keeps both an already published pack and an external later target after partial failure.
    @Test
    public void partialPublicationNeverRollsBackByUnprovenPathOwnership() throws IOException {
        Fixture fixture = fixture("concurrent-target");
        Path firstSource = createPackZip(
                fixture.root().resolve("source/first.zip"),
                15,
                "First");
        Path secondSource = createPackZip(
                fixture.root().resolve("source/second.zip"),
                15,
                "Second");
        Path firstTarget = fixture.packDirectory().resolve("first.zip");
        Path secondTarget = fixture.packDirectory().resolve("second.zip");
        AtomicInteger commits = new AtomicInteger();

        ResourcePackCatalogMutationAccessResult result = fixture.access().mutateAndLoadIndex(
                new ResourcePackImportMutation(List.of(firstSource, secondSource)),
                new LoadCancellation(),
                () -> {
                    commits.incrementAndGet();
                    writeUnchecked(secondTarget, "external owner");
                });

        assertAll(
                () -> assertInstanceOf(
                        java.nio.file.FileAlreadyExistsException.class,
                        result.mutationFailure()),
                () -> assertEquals(1, commits.get()),
                () -> assertArrayEquals(
                        Files.readAllBytes(firstSource),
                        Files.readAllBytes(firstTarget)),
                () -> assertEquals(
                        "external owner",
                        Files.readString(secondTarget, StandardCharsets.UTF_8)),
                () -> assertEquals(
                        List.of(firstTarget, secondTarget).stream()
                                .map(path -> path.toAbsolutePath().normalize())
                                .toList(),
                        result.refreshedIndex().paths()),
                () -> assertFalse(hasStagingArtifact(fixture.packDirectory())));
    }

    /// Rejects a source containing the managed root before staging can recurse into itself.
    @Test
    public void rejectsAncestorDirectoryImportBeforeCommit() throws IOException {
        Path source = temporaryDirectory.resolve("ancestor-source");
        createPackDirectory(source, 15, "Ancestor");
        Path managed = source.resolve("managed-resourcepacks");
        Path run = temporaryDirectory.resolve("ancestor-run");
        writeVersionJar(temporaryDirectory.resolve("ancestor-version.jar"));
        FileSystemResourcePackCatalogAccess access = new FileSystemResourcePackCatalogAccess(
                repository(managed, run, temporaryDirectory.resolve("ancestor-version.jar")),
                "test-instance");
        AtomicInteger commits = new AtomicInteger();

        ResourcePackCatalogMutationAccessResult result = access.mutateAndLoadIndex(
                new ResourcePackImportMutation(List.of(source)),
                new LoadCancellation(),
                commits::incrementAndGet);

        assertAll(
                () -> assertInstanceOf(IllegalArgumentException.class, result.mutationFailure()),
                () -> assertEquals(0, commits.get()),
                () -> assertFalse(Files.exists(managed)),
                () -> assertFalse(hasStagingArtifact(managed)));
    }

    /// Preserves a pre-existing regular file at the configured managed-directory path.
    @Test
    public void neverDeletesInvalidPreExistingManagedRoot() throws IOException {
        Fixture fixture = fixture("invalid-root");
        Files.createDirectories(fixture.packDirectory().getParent());
        Files.writeString(fixture.packDirectory(), "keep me", StandardCharsets.UTF_8);
        Path source = createPackZip(fixture.root().resolve("source/pack.zip"), 15, "Pack");
        AtomicInteger commits = new AtomicInteger();

        ResourcePackCatalogMutationAccessResult result = fixture.access().mutateAndLoadIndex(
                new ResourcePackImportMutation(List.of(source)),
                new LoadCancellation(),
                commits::incrementAndGet);

        assertAll(
                () -> assertInstanceOf(IOException.class, result.mutationFailure()),
                () -> assertEquals(0, commits.get()),
                () -> assertEquals(
                        "keep me",
                        Files.readString(fixture.packDirectory(), StandardCharsets.UTF_8)));
    }

    /// Persists enabled state strictly while retaining unrelated lines, order, and CRLF endings.
    @Test
    public void persistsOptionsStrictlyWithoutRewritingUnknownLines() throws IOException {
        Fixture fixture = fixture("options");
        Path pack = createPackDirectory(
                fixture.packDirectory().resolve("enabled-pack"),
                15,
                "Enabled");
        Files.createDirectories(fixture.runDirectory());
        String original = "music:0.7\r\n"
                + "resourcePacks:[]\r\n"
                + "custom line without colon\r\n"
                + "incompatibleResourcePacks:[]\r\n";
        Files.writeString(fixture.optionsFile(), original, StandardCharsets.UTF_8);
        AtomicInteger commits = new AtomicInteger();

        ResourcePackCatalogMutationAccessResult result = fixture.access().mutateAndLoadIndex(
                new ResourcePackEnabledMutation(pack.toAbsolutePath().normalize(), true),
                new LoadCancellation(),
                commits::incrementAndGet);
        String persisted = Files.readString(fixture.optionsFile(), StandardCharsets.UTF_8);
        List<ResourcePackCatalogItem> items = fixture.access().loadItems(
                result.refreshedIndex().paths(),
                new LoadCancellation());

        assertAll(
                () -> assertEquals(null, result.mutationFailure()),
                () -> assertEquals(1, commits.get()),
                () -> assertTrue(persisted.startsWith("music:0.7\r\n")),
                () -> assertTrue(persisted.contains("custom line without colon\r\n")),
                () -> assertTrue(persisted.contains(
                        "resourcePacks:[\"file/enabled-pack\"]\r\n")),
                () -> assertTrue(persisted.endsWith("incompatibleResourcePacks:[]\r\n")),
                () -> assertTrue(items.get(0).enabled()));
    }

    /// Preserves a detected legacy encoding while updating only the resource-pack option lines.
    @Test
    public void preservesLegacyOptionsEncodingAndUnknownText() throws IOException {
        Fixture fixture = fixture("legacy-options");
        Path pack = createPackDirectory(
                fixture.packDirectory().resolve("legacy-pack"),
                15,
                "Legacy");
        Files.createDirectories(fixture.runDirectory());
        Charset legacyEncoding = Charset.forName("GB18030");
        String marker = "保留未知中文设置：资源包启用时不应破坏原始编码。".repeat(20);
        String original = marker + "\r\n"
                + "resourcePacks:[]\r\n"
                + "incompatibleResourcePacks:[]\r\n";
        Files.write(fixture.optionsFile(), original.getBytes(legacyEncoding));

        ResourcePackCatalogMutationAccessResult result = fixture.access().mutateAndLoadIndex(
                new ResourcePackEnabledMutation(pack.toAbsolutePath().normalize(), true),
                new LoadCancellation(),
                () -> { });
        String persisted = new String(Files.readAllBytes(fixture.optionsFile()), legacyEncoding);

        assertAll(
                () -> assertEquals(null, result.mutationFailure()),
                () -> assertTrue(persisted.startsWith(marker + "\r\n")),
                () -> assertTrue(persisted.contains("resourcePacks:[\"file/legacy-pack\"]\r\n")),
                () -> assertTrue(persisted.endsWith("incompatibleResourcePacks:[]\r\n")));
    }

    /// Propagates malformed JSON and safe-save failures instead of silently discarding them.
    @Test
    public void rejectsMalformedOrUnpersistableOptions() throws IOException {
        Fixture fixture = fixture("strict-options");
        Path pack = createPackDirectory(
                fixture.packDirectory().resolve("strict-pack"),
                15,
                "Strict");
        Files.createDirectories(fixture.runDirectory());
        Files.writeString(
                fixture.optionsFile(),
                "resourcePacks:[broken\nincompatibleResourcePacks:[]\n",
                StandardCharsets.UTF_8);
        ResourcePackCatalogIndex index = fixture.access().loadIndex(new LoadCancellation());
        assertThrows(
                IOException.class,
                () -> fixture.access().loadItems(index.paths(), new LoadCancellation()));

        List<String> nonStringValues = List.of(
                "[null]",
                "[1]",
                "[true]",
                "[{}]",
                "[[]]",
                "{}");
        for (String invalidValue : nonStringValues) {
            Files.writeString(
                    fixture.optionsFile(),
                    "resourcePacks:" + invalidValue
                            + "\nincompatibleResourcePacks:[]\n",
                    StandardCharsets.UTF_8);
            assertThrows(
                    IOException.class,
                    () -> fixture.access().loadItems(index.paths(), new LoadCancellation()),
                    "Expected strict rejection for " + invalidValue);
        }

        String disabled = "resourcePacks:[]\nincompatibleResourcePacks:[]\n";
        Files.writeString(fixture.optionsFile(), disabled, StandardCharsets.UTF_8);
        Files.createDirectory(fixture.runDirectory().resolve(".options.txt.tmp"));
        ResourcePackCatalogMutationAccessResult result = fixture.access().mutateAndLoadIndex(
                new ResourcePackEnabledMutation(pack.toAbsolutePath().normalize(), true),
                new LoadCancellation(),
                () -> { });

        assertAll(
                () -> assertInstanceOf(IOException.class, result.mutationFailure()),
                () -> assertEquals(
                        disabled,
                        Files.readString(fixture.optionsFile(), StandardCharsets.UTF_8)));
    }

    /// Stops deletion when strict disable persistence fails, leaving both file and option intact.
    @Test
    public void persistsDisableBeforeDeletingPack() throws IOException {
        Fixture fixture = fixture("delete-order");
        Path pack = createPackDirectory(
                fixture.packDirectory().resolve("delete-pack"),
                15,
                "Delete");
        Files.createDirectories(fixture.runDirectory());
        String enabled = "resourcePacks:[\"file/delete-pack\"]\n"
                + "incompatibleResourcePacks:[]\n";
        Files.writeString(fixture.optionsFile(), enabled, StandardCharsets.UTF_8);
        Files.createDirectory(fixture.runDirectory().resolve(".options.txt.tmp"));

        ResourcePackCatalogMutationAccessResult result = fixture.access().mutateAndLoadIndex(
                new ResourcePackDeleteMutation(pack.toAbsolutePath().normalize()),
                new LoadCancellation(),
                () -> { });

        assertAll(
                () -> assertInstanceOf(IOException.class, result.mutationFailure()),
                () -> assertTrue(Files.isRegularFile(pack.resolve("pack.mcmeta"))),
                () -> assertEquals(
                        enabled,
                        Files.readString(fixture.optionsFile(), StandardCharsets.UTF_8)),
                () -> assertEquals(List.of(pack.toAbsolutePath().normalize()),
                        result.refreshedIndex().paths()));
    }

    /// Allows strict disable and deletion of a corrupt ZIP retained as an INVALID catalog row.
    @Test
    public void disablesAndDeletesCorruptZipWithoutParsingMetadata() throws IOException {
        Fixture fixture = fixture("corrupt-delete");
        Files.createDirectories(fixture.packDirectory());
        Files.createDirectories(fixture.runDirectory());
        Path corrupt = fixture.packDirectory().resolve("corrupt.zip");
        Files.writeString(corrupt, "not a ZIP", StandardCharsets.UTF_8);
        String enabled = "resourcePacks:[\"file/corrupt.zip\"]\n"
                + "incompatibleResourcePacks:[\"file/corrupt.zip\"]\n";
        Files.writeString(fixture.optionsFile(), enabled, StandardCharsets.UTF_8);
        AtomicInteger commits = new AtomicInteger();

        ResourcePackCatalogMutationAccessResult disabled = fixture.access().mutateAndLoadIndex(
                new ResourcePackEnabledMutation(corrupt.toAbsolutePath().normalize(), false),
                new LoadCancellation(),
                commits::incrementAndGet);
        String disabledOptions = Files.readString(fixture.optionsFile(), StandardCharsets.UTF_8);
        Files.writeString(fixture.optionsFile(), enabled, StandardCharsets.UTF_8);
        ResourcePackCatalogMutationAccessResult deleted = fixture.access().mutateAndLoadIndex(
                new ResourcePackDeleteMutation(corrupt.toAbsolutePath().normalize()),
                new LoadCancellation(),
                commits::incrementAndGet);

        assertAll(
                () -> assertEquals(null, disabled.mutationFailure()),
                () -> assertEquals(
                        "resourcePacks:[]\nincompatibleResourcePacks:[]\n",
                        disabledOptions),
                () -> assertTrue(disabled.refreshedIndex().paths().contains(
                        corrupt.toAbsolutePath().normalize())),
                () -> assertEquals(null, deleted.mutationFailure()),
                () -> assertFalse(Files.exists(corrupt)),
                () -> assertTrue(deleted.refreshedIndex().paths().isEmpty()),
                () -> assertEquals(2, commits.get()));
    }

    /// Cancels pre-commit staging promptly and removes every private staging artifact.
    @Test
    public void cancellationDuringStagingLeavesNoTargetOrPrivateArtifacts() throws IOException {
        Fixture fixture = fixture("cancel-staging");
        Files.createDirectories(fixture.packDirectory());
        Path source = createPackDirectory(
                fixture.root().resolve("source/cancel-pack"),
                15,
                "Cancel");
        CountDownLatch hookEntered = new CountDownLatch(1);
        CountDownLatch releaseHook = new CountDownLatch(1);
        LoadCancellation cancellation = new LoadCancellation();
        AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();
        AtomicInteger commits = new AtomicInteger();
        FileSystemResourcePackCatalogAccess access = new FileSystemResourcePackCatalogAccess(
                fixture.repository(),
                "test-instance",
                () -> {
                    hookEntered.countDown();
                    await(releaseHook, "staging hook was not released");
                });
        Thread worker = daemonThread("resource-pack-staging-cancel", () -> {
            try {
                access.mutateAndLoadIndex(
                        new ResourcePackImportMutation(List.of(source)),
                        cancellation,
                        commits::incrementAndGet);
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });

        worker.start();
        await(hookEntered, "staging hook was not reached");
        cancellation.cancel();
        releaseHook.countDown();
        join(worker, "cancelled staging did not finish");

        assertAll(
                () -> assertInstanceOf(CancellationException.class, failure.get()),
                () -> assertEquals(0, commits.get()),
                () -> assertFalse(Files.exists(fixture.packDirectory().resolve("cancel-pack"))),
                () -> assertFalse(hasStagingArtifact(fixture.packDirectory())));
    }

    /// Serializes two adapters sharing the same normalized directory and options file.
    @Test
    public void sharesOperationGateAcrossAdaptersForTheSameInstanceStorage() throws IOException {
        Fixture fixture = fixture("shared-gate");
        Path source = createPackZip(fixture.root().resolve("source/gated.zip"), 15, "Gated");
        CountDownLatch firstInsideGate = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);
        AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();
        FileSystemResourcePackCatalogAccess first = new FileSystemResourcePackCatalogAccess(
                fixture.repository(),
                "test-instance",
                () -> {
                    firstInsideGate.countDown();
                    await(releaseFirst, "first adapter gate was not released");
                });
        FileSystemResourcePackCatalogAccess second = fixture.access();
        Thread writer = daemonThread("resource-pack-shared-gate-writer", () -> {
            try {
                first.mutateAndLoadIndex(
                        new ResourcePackImportMutation(List.of(source)),
                        new LoadCancellation(),
                        () -> { });
            } catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            }
        });
        Thread reader = daemonThread("resource-pack-shared-gate-reader", () -> {
            try {
                second.loadIndex(new LoadCancellation());
            } catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            } finally {
                secondFinished.countDown();
            }
        });

        writer.start();
        await(firstInsideGate, "first adapter never entered the shared gate");
        reader.start();
        assertFalse(awaitBriefly(secondFinished), "second adapter bypassed the shared gate");
        releaseFirst.countDown();
        join(writer, "shared-gate writer did not finish");
        join(reader, "shared-gate reader did not finish");

        assertAll(
                () -> assertEquals(null, failure.get()),
                () -> assertTrue(Files.isRegularFile(
                        fixture.packDirectory().resolve("gated.zip"))));
    }

    /// Creates one isolated production access fixture.
    ///
    /// @param name fixture directory name
    /// @return initialized paths, repository, and access
    /// @throws IOException when the version archive cannot be created
    private Fixture fixture(String name) throws IOException {
        Path root = temporaryDirectory.resolve(name);
        Path packDirectory = root.resolve("resourcepacks");
        Path runDirectory = root.resolve("run");
        Path versionJar = root.resolve("version.jar");
        Files.createDirectories(root);
        writeVersionJar(versionJar);
        GameRepository repository = repository(packDirectory, runDirectory, versionJar);
        return new Fixture(
                root,
                packDirectory,
                runDirectory,
                runDirectory.resolve("options.txt"),
                repository,
                new FileSystemResourcePackCatalogAccess(repository, "test-instance"));
    }

    /// Creates one supported resource-pack directory.
    ///
    /// @param directory target directory
    /// @param packFormat declared pack format
    /// @param description pack description
    /// @return normalized target directory
    /// @throws IOException when metadata cannot be written
    private static Path createPackDirectory(
            Path directory,
            int packFormat,
            String description) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(
                directory.resolve("pack.mcmeta"),
                "{\"pack\":{\"pack_format\":" + packFormat
                        + ",\"description\":\"" + description + "\"}}",
                StandardCharsets.UTF_8);
        return directory.toAbsolutePath().normalize();
    }

    /// Creates one supported ZIP resource pack with minimal metadata.
    ///
    /// @param archive target ZIP
    /// @param packFormat declared pack format
    /// @param description pack description
    /// @return normalized target archive
    /// @throws IOException when the archive cannot be written
    private static Path createPackZip(
            Path archive,
            int packFormat,
            String description) throws IOException {
        Files.createDirectories(archive.getParent());
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("pack.mcmeta"));
            output.write(("{\"pack\":{\"pack_format\":" + packFormat
                    + ",\"description\":\"" + description + "\"}}")
                    .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return archive.toAbsolutePath().normalize();
    }

    /// Writes a minimal Minecraft version archive declaring resource-pack format 15.
    ///
    /// @param versionJar target archive
    /// @throws IOException when the archive cannot be written
    private static void writeVersionJar(Path versionJar) throws IOException {
        Files.createDirectories(versionJar.getParent());
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(versionJar))) {
            output.putNextEntry(new ZipEntry("version.json"));
            output.write(("{\"pack_version\":{\"resource\":15,"
                    + "\"resource_major\":15,\"resource_minor\":0}}")
                    .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    /// Creates a non-networking repository proxy for one explicit storage layout.
    ///
    /// @param packDirectory managed resource-pack directory
    /// @param runDirectory instance run directory
    /// @param versionJar local version archive
    /// @return local repository fixture
    private static GameRepository repository(
            Path packDirectory,
            Path runDirectory,
            Path versionJar) {
        return (GameRepository) Proxy.newProxyInstance(
                GameRepository.class.getClassLoader(),
                new Class<?>[]{GameRepository.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getResourcePackDirectory" -> packDirectory;
                    case "getRunDirectory" -> runDirectory;
                    case "getInstanceJar" -> versionJar;
                    case "getGameVersion" -> Optional.of("1.20.1");
                    case "toString" -> "FileSystemResourcePackCatalogRepository";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == requireArguments(arguments)[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    /// Writes UTF-8 text from a callback that cannot declare checked failures.
    ///
    /// @param target target path
    /// @param text text to write
    private static void writeUnchecked(Path target, String text) {
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, text, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /// Returns whether a private import staging artifact remains inside or beside a managed root.
    ///
    /// @param managedDirectory managed directory
    /// @return whether a staging artifact remains
    /// @throws IOException when the parent cannot be listed
    private static boolean hasStagingArtifact(Path managedDirectory) throws IOException {
        if (Files.isDirectory(managedDirectory)) {
            try (var children = Files.list(managedDirectory)) {
                if (children.anyMatch(path -> requireFileName(path).startsWith(".xyml-import-"))) {
                    return true;
                }
            }
        }
        Path parent = managedDirectory.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return false;
        }
        String prefix = "." + requireFileName(managedDirectory) + "-import-";
        try (var children = Files.list(parent)) {
            return children.anyMatch(path -> requireFileName(path).startsWith(prefix));
        }
    }

    /// Waits for one latch with the shared finite timeout.
    ///
    /// @param latch latch to wait for
    /// @param message timeout message
    private static void await(CountDownLatch latch, String message) {
        try {
            assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), message);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting", interrupted);
        }
    }

    /// Checks briefly whether a latch completed unexpectedly early.
    ///
    /// @param latch latch to inspect
    /// @return whether it completed during the brief interval
    private static boolean awaitBriefly(CountDownLatch latch) {
        try {
            return latch.await(100L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while checking gate", interrupted);
        }
    }

    /// Joins one worker with a finite timeout.
    ///
    /// @param thread worker to join
    /// @param message timeout message
    private static void join(Thread thread, String message) {
        try {
            thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while joining worker", interrupted);
        }
        assertFalse(thread.isAlive(), message);
    }

    /// Creates one unstarted daemon worker.
    ///
    /// @param name thread name
    /// @param task worker task
    /// @return daemon thread
    private static Thread daemonThread(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    /// Returns proxy arguments after enforcing their non-null contract.
    ///
    /// @param arguments nullable proxy arguments
    /// @return non-null argument array
    private static Object[] requireArguments(Object @Nullable [] arguments) {
        if (arguments == null) {
            throw new AssertionError("Expected proxy arguments");
        }
        return arguments;
    }

    /// Returns one path's required final component.
    ///
    /// @param path path to inspect
    /// @return final component text
    private static String requireFileName(Path path) {
        Path name = path.getFileName();
        if (name == null) {
            throw new AssertionError("Expected final path component");
        }
        return name.toString();
    }

    /// Returns a harmless proxy default for an unused repository method.
    ///
    /// @param returnType reflected return type
    /// @return primitive zero, false, or null
    private static @Nullable Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        return null;
    }

    /// Isolated real-file fixture.
    ///
    /// @param root fixture root
    /// @param packDirectory managed resource-pack directory
    /// @param runDirectory instance run directory
    /// @param optionsFile strict options path
    /// @param repository local repository proxy
    /// @param access production access adapter
    @NotNullByDefault
    private record Fixture(
            Path root,
            Path packDirectory,
            Path runDirectory,
            Path optionsFile,
            GameRepository repository,
            FileSystemResourcePackCatalogAccess access) {
    }
}

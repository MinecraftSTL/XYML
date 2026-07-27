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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.addon.RemoteAddon;
import space.minecraftstl.xyml.addon.RemoteAddonRepository;
import space.minecraftstl.xyml.addon.mod.ModLoaderType;
import space.minecraftstl.xyml.addon.repository.CurseForgeRemoteAddonRepository;
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JPanel;
import java.awt.Component;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies explicit world save-as selection, cancellation, and exact destination safety.
@NotNullByDefault
final class SwingRemoteWorldSaveTargetResolverTest {
    /// Temporary root receiving deterministic save-as selections.
    @TempDir
    private Path temporaryDirectory;

    /// Maps the native WORLD category to the existing CurseForge world repository only.
    @Test
    void mapsWorldCatalogToCurseForgeWorldRepository() {
        assertTrue(RemoteAddonCatalogSource.CURSEFORGE.supports(RemoteAddonCatalogKind.WORLD));
        assertFalse(RemoteAddonCatalogSource.MODRINTH.supports(RemoteAddonCatalogKind.WORLD));
        assertSame(
                CurseForgeRemoteAddonRepository.WORLDS,
                RemoteAddonCatalogSource.CURSEFORGE.repository(RemoteAddonCatalogKind.WORLD));
    }

    /// Keeps routine target availability checks dialog-free and preserves a renamed exact destination.
    @Test
    void opensChooserOnlyForExplicitWorldSelectionAndPreservesExactDestination() {
        AtomicInteger chooserCalls = new AtomicInteger();
        AtomicReference<@Nullable String> suggestedName = new AtomicReference<>();
        AtomicReference<@Nullable Component> chooserOwner = new AtomicReference<>();
        Path destination = temporaryDirectory.resolve("My downloaded world.zip");
        SwingRemoteWorldSaveTargetResolver resolver = new SwingRemoteWorldSaveTargetResolver(
                (owner, suggestion) -> {
                    chooserCalls.incrementAndGet();
                    chooserOwner.set(owner);
                    suggestedName.set(suggestion);
                    return Optional.of(destination);
                });

        assertTrue(resolver.isSelectionAvailable(RemoteAddonCatalogKind.WORLD));
        assertFalse(resolver.isSelectionAvailable(RemoteAddonCatalogKind.MOD));
        assertTrue(resolver.resolve(RemoteAddonCatalogKind.WORLD).isEmpty());
        assertEquals(0, chooserCalls.get());

        JPanel owner = new JPanel();
        AtomicReference<Optional<RemoteAddonInstallTarget>> result = new AtomicReference<>(Optional.empty());
        RemoteAddon.Version version = fixtureVersion("provider-world.zip");
        EdtDispatcher.executeAndWait(() -> result.set(resolver.resolveSelection(
                RemoteAddonCatalogKind.WORLD,
                fixtureItem(),
                version,
                owner)));

        RemoteAddonInstallTarget target = result.get().orElseThrow();
        assertEquals(1, chooserCalls.get());
        assertEquals("provider-world.zip", suggestedName.get());
        assertSame(owner, chooserOwner.get());
        assertEquals(destination.toAbsolutePath().normalize(), target.exactDestination());
        assertEquals(destination.toAbsolutePath().normalize(), target.resolveDestination(version));
        assertEquals(RemoteAddonCatalogKind.WORLD, target.kind());
    }

    /// Uses a safe fallback suggestion for path-bearing provider metadata and leaves no target after cancellation.
    @Test
    void sanitizesProviderSuggestionAndHonorsCancellation() {
        AtomicReference<@Nullable String> suggestedName = new AtomicReference<>();
        SwingRemoteWorldSaveTargetResolver resolver = new SwingRemoteWorldSaveTargetResolver(
                (owner, suggestion) -> {
                    Objects.requireNonNull(owner, "owner");
                    suggestedName.set(suggestion);
                    return Optional.empty();
                });
        AtomicReference<Optional<RemoteAddonInstallTarget>> result = new AtomicReference<>(Optional.empty());

        EdtDispatcher.executeAndWait(() -> result.set(resolver.resolveSelection(
                RemoteAddonCatalogKind.WORLD,
                fixtureItem(),
                fixtureVersion("../escaped.zip"),
                new JPanel())));

        assertEquals("world.zip", suggestedName.get());
        assertTrue(result.get().isEmpty());
    }

    /// Creates one deterministic remote world item backed by CurseForge provenance.
    ///
    /// @return remote world fixture
    private static RemoteAddonCatalogItem fixtureItem() {
        RemoteAddon addon = new RemoteAddon(
                "fixture-world",
                "fixture-author",
                "Fixture World",
                "Fixture description",
                List.of(),
                "https://example.invalid/fixture-world",
                "https://example.invalid/fixture-world.png",
                new FixtureAddonData(),
                RemoteAddonRepository.Type.WORLD);
        return new RemoteAddonCatalogItem(
                addon,
                RemoteAddonCatalogKind.WORLD,
                RemoteAddonCatalogSource.CURSEFORGE);
    }

    /// Creates one deterministic downloadable world archive version.
    ///
    /// @param fileName provider-returned artifact filename
    /// @return remote version fixture
    private static RemoteAddon.Version fixtureVersion(String fileName) {
        return new RemoteAddon.Version(
                () -> RemoteAddon.Source.CURSEFORGE,
                "fixture-world",
                "Fixture World 1.0",
                "1.0.0",
                "",
                Instant.EPOCH,
                RemoteAddon.VersionType.Release,
                new RemoteAddon.File(
                        Map.of("sha256", "0123456789012345678901234567890123456789012345678901234567890123"),
                        "https://example.invalid/fixture-world.zip",
                        Objects.requireNonNull(fileName, "fileName")),
                List.of(),
                List.of("1.20.1"),
                List.<ModLoaderType>of());
    }

    /// Provides unused Core contracts required by the remote world fixture.
    @NotNullByDefault
    private static final class FixtureAddonData implements RemoteAddon.IMod {
        /// Rejects dependency resolution because this target test never requests it.
        ///
        /// @param modRepository unused source repository
        /// @param downloadProvider unused download provider
        /// @return never returns normally
        /// @throws IOException always because dependency resolution is outside this test
        @Override
        public List<RemoteAddon> loadDependencies(
                RemoteAddonRepository modRepository,
                DownloadProvider downloadProvider) throws IOException {
            throw new IOException("Fixture dependencies are outside the save-target test");
        }

        /// Rejects backend version loading because this target test supplies its version directly.
        ///
        /// @param modRepository unused source repository
        /// @param downloadProvider unused download provider
        /// @return never returns normally
        /// @throws IOException always because backend loading is outside this test
        @Override
        public Stream<RemoteAddon.Version> loadVersions(
                RemoteAddonRepository modRepository,
                DownloadProvider downloadProvider) throws IOException {
            throw new IOException("Fixture versions are supplied directly");
        }
    }
}

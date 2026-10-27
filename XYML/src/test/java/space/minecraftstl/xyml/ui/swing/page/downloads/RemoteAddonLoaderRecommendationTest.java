// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.addon.RemoteAddon;
import space.minecraftstl.xyml.addon.RemoteAddonRepository;
import space.minecraftstl.xyml.addon.mod.ModLoaderType;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/// Exercises instance loader recommendations through the catalog's asynchronous version callback.
@NotNullByDefault
final class RemoteAddonLoaderRecommendationTest {
    /// A Fabric instance must not automatically select the higher-ranked NeoForge artifact.
    @Test
    void recommendsFabricForSameGameReleaseAndKeepsAllVersions() {
        RemoteAddon.Version fabric = version("fabric", "0.8.13", "1.21.1", ModLoaderType.FABRIC);
        RemoteAddon.Version neoforge = version("neoforge", "0.8.13", "1.21.1", ModLoaderType.NEO_FORGE);
        assertPanelRecommendation(RemoteAddonCatalogKind.MOD, "1.21.1", ModLoaderType.FABRIC,
                List.of(neoforge, fabric), fabric);
    }

    /// The original Fabric 1.20.1 context retains its matching stable artifact.
    @Test
    void retainsFabric1201Recommendation() {
        RemoteAddon.Version fabric = version("fabric", "0.5.13", "1.20.1", ModLoaderType.FABRIC);
        RemoteAddon.Version neoforge = version("neoforge", "0.8.13", "1.21.1", ModLoaderType.NEO_FORGE);
        assertPanelRecommendation(RemoteAddonCatalogKind.MOD, "1.20.1", ModLoaderType.FABRIC,
                List.of(neoforge, fabric), fabric);
    }

    /// Browsing without an instance loader retains the existing unrestricted recommendation.
    @Test
    void preservesUnrestrictedRecommendationWithoutLoader() {
        RemoteAddon.Version fabric = version("fabric", "0.8.13", "1.21.1", ModLoaderType.FABRIC);
        RemoteAddon.Version neoforge = version("neoforge", "0.8.13", "1.21.1", ModLoaderType.NEO_FORGE);
        assertPanelRecommendation(RemoteAddonCatalogKind.MOD, "1.21.1", null,
                List.of(neoforge, fabric), neoforge);
    }

    /// Non-mod catalogs must not filter their recommendations by the instance's mod loader.
    @Test
    void preservesOtherContentRecommendations() {
        for (RemoteAddonCatalogKind kind : List.of(RemoteAddonCatalogKind.RESOURCE_PACK,
                RemoteAddonCatalogKind.SHADER_PACK)) {
            RemoteAddon.Version archive = version("archive", "1.0", "1.21.1", null);
            assertPanelRecommendation(kind, "1.21.1", ModLoaderType.FABRIC, List.of(archive), archive);
        }
    }

    /// Unknown loader context retains general browsing, including provider versions without loader metadata.
    @Test
    void unknownLoaderKeepsGeneralBrowsing() {
        RemoteAddon.Version archive = version("unknown", "1.0", "1.21.1", null);
        assertPanelRecommendation(RemoteAddonCatalogKind.MOD, "1.21.1", ModLoaderType.UNKNOWN,
                List.of(archive), archive);
    }

    /// The recommendation never labels an explicitly incompatible artifact as loader-compatible.
    @Test
    void returnsNoRecommendationWithoutCompatibleLoader() {
        RemoteAddon.Version neoforge = version("neoforge", "0.8.13", "1.21.1", ModLoaderType.NEO_FORGE);
        RemoteAddon.Version missing = version("missing", "0.8.13", "1.21.1", null);
        assertNull(RemoteAddonVersionOrdering.recommended(List.of(neoforge, missing), "1.21.1",
                ModLoaderType.FABRIC));
    }

    /// Missing loader matches leave the selector empty while retaining every manual choice.
    @Test
    void doesNotSelectIncompatibleFirstVersionWithoutRecommendation() {
        RemoteAddon.Version neoforge = version("neoforge", "0.8.13", "1.21.1", ModLoaderType.NEO_FORGE);
        RemoteAddon.Version missing = version("missing", "0.8.13", "1.21.1", null);
        assertPanelRecommendation(RemoteAddonCatalogKind.MOD, "1.21.1", ModLoaderType.FABRIC,
                List.of(neoforge, missing), null, false);
    }

    /// Provider-declared loader membership does not invent Fabric/Quilt or Forge/NeoForge compatibility.
    @Test
    void requiresExplicitProviderLoaderMembership() {
        RemoteAddon.Version fabric = version("fabric", "1.0", "1.21.1", ModLoaderType.FABRIC);
        RemoteAddon.Version forge = version("forge", "1.0", "1.21.1", ModLoaderType.FORGE);
        assertNull(RemoteAddonVersionOrdering.recommended(List.of(fabric), "1.21.1", ModLoaderType.QUILT));
        assertNull(RemoteAddonVersionOrdering.recommended(List.of(forge), "1.21.1", ModLoaderType.NEO_FORGE));
        RemoteAddon.Version multiple = new RemoteAddon.Version(fabric.self(), fabric.versionId(), fabric.projectId(),
                fabric.name(), fabric.version(), fabric.datePublished(), fabric.versionType(), fabric.file(),
                fabric.dependencies(), fabric.gameVersions(), List.of(ModLoaderType.FABRIC, ModLoaderType.QUILT));
        assertSame(multiple, RemoteAddonVersionOrdering.recommended(List.of(multiple), "1.21.1", ModLoaderType.QUILT));
    }

    /// Loader filtering retains the existing exact game-version and stable-channel priorities.
    @Test
    void preservesGameVersionAndReleasePrioritiesWithinLoader() {
        RemoteAddon.Version stable = version("stable", "1.0", "1.21.1", ModLoaderType.FABRIC);
        RemoteAddon.Version old = version("old", "99.0", "1.20.1", ModLoaderType.FABRIC);
        RemoteAddon.Version preview = new RemoteAddon.Version(stable.self(), "preview", stable.projectId(),
                "preview", "99.0", stable.datePublished(), RemoteAddon.VersionType.Beta, stable.file(),
                stable.dependencies(), stable.gameVersions(), stable.loaders());
        assertSame(stable, RemoteAddonVersionOrdering.recommended(List.of(preview, old, stable), "1.21.1",
                ModLoaderType.FABRIC));
    }

    /// A changed instance context invalidates the outstanding callback even with the same game version.
    @Test
    void discardsVersionCallbackAfterContextChange() {
        RemoteAddon.Version fabric = version("fabric", "0.8.13", "1.21.1", ModLoaderType.FABRIC);
        assertPanelRecommendation(RemoteAddonCatalogKind.MOD, "1.21.1", ModLoaderType.FABRIC,
                List.of(fabric), null, true);
    }

    /// Searches, selects and completes versions through deterministic queued worker callbacks.
    private static void assertPanelRecommendation(
            RemoteAddonCatalogKind kind,
            String game,
            @Nullable ModLoaderType loader,
            @Unmodifiable List<RemoteAddon.Version> versions,
            RemoteAddon.Version expected) {
        assertPanelRecommendation(kind, game, loader, versions, expected, false);
    }

    /// Optionally switches context while a provider version request is outstanding.
    private static void assertPanelRecommendation(
            RemoteAddonCatalogKind kind,
            String game,
            @Nullable ModLoaderType loader,
            @Unmodifiable List<RemoteAddon.Version> versions,
            @Nullable RemoteAddon.Version expected,
            boolean changeContext) {
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        RemoteAddonCatalogPanel[] owner = new RemoteAddonCatalogPanel[1];
        try {
            EdtDispatcher.executeAndWait(() -> {
                RemoteAddonCatalogPanel panel = new RemoteAddonCatalogPanel(kind, new Backend(kind, versions),
                        request -> Task.completed(null), ignored -> Optional.empty(), tasks::add,
                        RemoteAddonCatalogStrings.english(kind), TaskProgressStrings.english(), null, Duration.ZERO);
                owner[0] = panel;
                panel.instanceContext().apply(game, loader);
                panel.choiceList().setSize(480, 180);
                panel.choiceList().getViewport().setExtentSize(new Dimension(480, 160));
                panel.choiceList().getList().setSize(480, 160);
                panel.choiceList().refreshLoadPlan();
                find(panel, "remoteAddonSearchAction", JButton.class).doClick();
            });
            runTasks(tasks);
            EdtDispatcher.executeAndWait(() -> owner[0].choiceList().getList().setSelectedIndex(0));
            if (changeContext) {
                EdtDispatcher.executeAndWait(() -> owner[0].instanceContext().apply(game, ModLoaderType.NEO_FORGE));
            }
            runTasks(tasks);
            EdtDispatcher.executeAndWait(() -> {
                JComboBox<?> box = find(owner[0], "remoteAddonVersion", JComboBox.class);
                assertEquals(changeContext ? 0 : versions.size(), box.getItemCount());
                assertSame(expected, box.getSelectedItem());
                if (!changeContext) {
                    JComboBox<?> sort = find(owner[0], "remoteAddonVersionSort", JComboBox.class);
                    for (RemoteAddonVersionSortMode mode : RemoteAddonVersionSortMode.values()) {
                        sort.setSelectedItem(mode);
                        assertEquals(versions.size(), box.getItemCount());
                        assertSame(expected, box.getSelectedItem());
                    }
                    for (RemoteAddon.Version version : versions) {
                        box.setSelectedItem(version);
                        assertSame(version, box.getSelectedItem());
                        for (RemoteAddonVersionSortMode mode : RemoteAddonVersionSortMode.values()) {
                            sort.setSelectedItem(mode);
                            assertSame(version, box.getSelectedItem());
                        }
                    }
                }
            });
        } finally {
            if (owner[0] != null) owner[0].close();
        }
    }

    /// Completes worker batches and their queued EDT publication without network I/O.
    private static void runTasks(ArrayDeque<Runnable> tasks) {
        while (!tasks.isEmpty()) {
            tasks.removeFirst().run();
            EdtDispatcher.executeAndWait(() -> { });
        }
    }

    /// Finds an owned named component while keeping test interaction on the EDT.
    private static <T extends JComponent> T find(Container root, String name, Class<T> type) {
        @Nullable T result = findOrNull(root, name, type);
        assertNotNull(result, name);
        return Objects.requireNonNull(result);
    }

    /// Searches one component subtree, returning null when the named control is absent.
    private static <T extends JComponent> @Nullable T findOrNull(Container root, String name, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container container) {
                @Nullable T result = findOrNull(container, name, type);
                if (result != null) return result;
            }
        }
        return null;
    }

    /// Creates explicit provider loader metadata without relying on names or filenames for compatibility.
    private static RemoteAddon.Version version(String id, String number, String game, @Nullable ModLoaderType loader) {
        return new RemoteAddon.Version(() -> RemoteAddon.Source.MODRINTH, id, "fixture", id, number,
                "neoforge".equals(id) ? Instant.EPOCH.plusSeconds(1) : Instant.EPOCH, RemoteAddon.VersionType.Release,
                new RemoteAddon.File(Map.of(), "https://example.invalid/" + id, id + ".jar"),
                List.of(), List.of(game), loader == null ? List.of() : List.of(loader));
    }

    /// Supplies local provider fixtures while exercising the production panel callback and ordering.
    @NotNullByDefault
    private record Backend(RemoteAddonCatalogKind kind,
                           @Unmodifiable List<RemoteAddon.Version> versions) implements RemoteAddonCatalogBackend {
        /// Snapshots the fixture response without sharing a mutable provider collection.
        private Backend {
            versions = List.copyOf(versions);
        }

        /// Returns no categories because project search facets are not version compatibility metadata.
        @Override
        public @Unmodifiable List<RemoteAddonRepository.Category> loadCategories(
                RemoteAddonCatalogKind requestedKind, RemoteAddonCatalogSource source) {
            return List.of();
        }

        /// Supplies a project row for the requested catalog kind without network access.
        @Override
        public RemoteAddonCatalogPage search(RemoteAddonCatalogQuery query) {
            RemoteAddon addon = new RemoteAddon("fixture", "author", "Fixture", "Fixture", List.of(),
                    "https://example.invalid/fixture", "", null, kind.repositoryType());
            return new RemoteAddonCatalogPage(List.of(new RemoteAddonCatalogItem(addon, kind,
                    RemoteAddonCatalogSource.MODRINTH)), query.pageOffset(), 1);
        }

        /// Returns all artifacts, including incompatible ones that must remain manually selectable.
        @Override
        public @Unmodifiable List<RemoteAddon.Version> loadVersions(RemoteAddonCatalogItem item) {
            return versions;
        }
    }
}

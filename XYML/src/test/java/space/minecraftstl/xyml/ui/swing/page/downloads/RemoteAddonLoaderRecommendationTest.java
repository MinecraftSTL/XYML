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
import space.minecraftstl.xyml.addon.repository.CurseForgeRemoteAddonRepository;
import space.minecraftstl.xyml.addon.repository.ModrinthRemoteAddonRepository;
import space.minecraftstl.xyml.util.gson.JsonUtils;
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

    /// A matching loader cannot make an artifact for a different game version eligible.
    @Test
    void rejectsMatchingLoaderForDifferentGameVersion() {
        RemoteAddon.Version old = version("old", "99.0", "1.20.1", ModLoaderType.FABRIC);
        assertNull(RemoteAddonVersionOrdering.recommended(List.of(old), "1.21.1", ModLoaderType.FABRIC));
    }

    /// A project matching the game only through another loader must leave automatic selection empty.
    @Test
    void keepsCrossLoaderGameMatchesManualAcrossSorting() {
        RemoteAddon.Version old = version("old", "99.0", "1.20.1", ModLoaderType.FABRIC);
        RemoteAddon.Version neoforge = version("neoforge", "1.0", "1.21.1", ModLoaderType.NEO_FORGE);
        assertPanelRecommendation(RemoteAddonCatalogKind.MOD, "1.21.1", ModLoaderType.FABRIC,
                List.of(old, neoforge), null, false);
    }

    /// Preview channels remain eligible when they explicitly support both known instance dimensions.
    @Test
    void retainsPreviewFallbackWithinExactGameAndLoader() {
        RemoteAddon.Version old = version("old", "99.0", "1.20.1", ModLoaderType.FABRIC);
        RemoteAddon.Version base = version("preview", "1.0", "1.21.1", ModLoaderType.FABRIC);
        RemoteAddon.Version preview = new RemoteAddon.Version(base.self(), base.versionId(), base.projectId(),
                base.name(), base.version(), base.datePublished(), RemoteAddon.VersionType.Beta, base.file(),
                base.dependencies(), base.gameVersions(), base.loaders(), RemoteAddon.Environment.UNKNOWN);
        assertPanelRecommendation(RemoteAddonCatalogKind.MOD, "1.21.1", ModLoaderType.FABRIC,
                List.of(old, preview), preview);
    }

    /// Missing game or loader context preserves the existing general fallback rather than guessing compatibility.
    @Test
    void preservesFallbackWithIncompleteInstanceContext() {
        RemoteAddon.Version old = version("old", "1.0", "1.20.1", ModLoaderType.FABRIC);
        assertPanelRecommendation(RemoteAddonCatalogKind.MOD, "", ModLoaderType.FABRIC, List.of(old), old);
        assertPanelRecommendation(RemoteAddonCatalogKind.MOD, "1.21.1", null, List.of(old), old);
        assertPanelRecommendation(RemoteAddonCatalogKind.MOD, "1.21.1", ModLoaderType.UNKNOWN, List.of(old), old);
    }

    /// CurseForge metadata follows the same exact game/loader eligibility through its production adapter.
    @Test
    void enforcesExactGameAcrossCurseForgeArtifacts() {
        String json = """
                [
                  {"id":1,"modId":123,"displayName":"Old","fileName":"old.jar","releaseType":1,
                   "fileDate":"2026-01-01T00:00:00Z","downloadUrl":"https://example.invalid/old.jar",
                   "gameVersions":["1.20.1","Fabric"],"dependencies":[]},
                  {"id":2,"modId":123,"displayName":"Other loader","fileName":"other.jar","releaseType":1,
                   "fileDate":"2026-01-01T00:00:00Z","downloadUrl":"https://example.invalid/other.jar",
                   "gameVersions":["1.21.1","NeoForge"],"dependencies":[]},
                  {"id":3,"modId":123,"displayName":"Preview","fileName":"preview.jar","releaseType":2,
                   "fileDate":"2026-01-01T00:00:00Z","downloadUrl":"https://example.invalid/preview.jar",
                   "gameVersions":["1.21.1","Fabric"],"dependencies":[]}
                ]
                """;
        CurseForgeRemoteAddonRepository.CurseAddon.LatestFile[] files = JsonUtils.GSON.fromJson(json,
                CurseForgeRemoteAddonRepository.CurseAddon.LatestFile[].class);
        RemoteAddon.Version old = files[0].toVersion();
        RemoteAddon.Version other = files[1].toVersion();
        RemoteAddon.Version preview = files[2].toVersion();
        assertPanelRecommendation(RemoteAddonCatalogKind.MOD, "1.21.1", ModLoaderType.FABRIC,
                List.of(old, other), null, false);
        assertPanelRecommendation(RemoteAddonCatalogKind.MOD, "1.21.1", ModLoaderType.FABRIC,
                List.of(old, other, preview), preview);
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
                fabric.dependencies(), fabric.gameVersions(), List.of(ModLoaderType.FABRIC, ModLoaderType.QUILT), RemoteAddon.Environment.UNKNOWN);
        assertSame(multiple, RemoteAddonVersionOrdering.recommended(List.of(multiple), "1.21.1", ModLoaderType.QUILT));
    }

    /// Loader filtering retains the existing exact game-version and stable-channel priorities.
    @Test
    void preservesGameVersionAndReleasePrioritiesWithinLoader() {
        RemoteAddon.Version stable = version("stable", "1.0", "1.21.1", ModLoaderType.FABRIC);
        RemoteAddon.Version old = version("old", "99.0", "1.20.1", ModLoaderType.FABRIC);
        RemoteAddon.Version preview = new RemoteAddon.Version(stable.self(), "preview", stable.projectId(),
                "preview", "99.0", stable.datePublished(), RemoteAddon.VersionType.Beta, stable.file(),
                stable.dependencies(), stable.gameVersions(), stable.loaders(), RemoteAddon.Environment.UNKNOWN);
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

    /// Only dedicated-server-only versions are unsuitable for the client's integrated-server context.
    @Test
    void appliesAllProviderEnvironmentsThroughTheCatalog() {
        for (String environment : List.of("client_only", "server_only", "dedicated_server_only",
                "client_and_server", "server_only_client_optional", "client_only_server_optional",
                "client_or_server_prefers_both", "client_or_server", "singleplayer_only", "unknown", "future_value")) {
            RemoteAddon.Version candidate = providerVersion(environment);
            assertPanelRecommendation(RemoteAddonCatalogKind.MOD, "1.21.1", ModLoaderType.FABRIC,
                    List.of(candidate), environment.equals("dedicated_server_only") ? null : candidate, false);
        }
    }

    /// Missing environment metadata remains eligible rather than inventing runtime incompatibility.
    @Test
    void acceptsMissingProviderEnvironment() {
        RemoteAddon.Version candidate = providerVersion(null);
        assertEquals(RemoteAddon.Environment.UNKNOWN, candidate.environment());
        assertPanelRecommendation(RemoteAddonCatalogKind.MOD, "1.21.1", ModLoaderType.FABRIC,
                List.of(candidate), candidate);
    }

    /// Environment restrictions neither filter unrelated catalogs nor change unknown-context browsing.
    @Test
    void preservesEnvironmentBrowsingOutsideKnownModInstances() {
        RemoteAddon.Version dedicated = providerVersion("dedicated_server_only");
        for (RemoteAddonCatalogKind kind : List.of(RemoteAddonCatalogKind.RESOURCE_PACK,
                RemoteAddonCatalogKind.SHADER_PACK)) {
            assertPanelRecommendation(kind, "1.21.1", ModLoaderType.FABRIC, List.of(dedicated), dedicated);
        }
        assertPanelRecommendation(RemoteAddonCatalogKind.MOD, "1.21.1", null, List.of(dedicated), dedicated);
        assertPanelRecommendation(RemoteAddonCatalogKind.MOD, "1.21.1", ModLoaderType.UNKNOWN,
                List.of(dedicated), dedicated);
    }

    /// Converts current provider JSON through the production adapter before any recommendation is made.
    private static RemoteAddon.Version providerVersion(@Nullable String environment) {
        String json = """
                {"id":"fixture","project_id":"fixture","name":"Fixture","version_number":"1.0",
                 "date_published":"2026-01-01T00:00:00Z","version_type":"release",
                 "game_versions":["1.21.1","1.21"],"loaders":["fabric","quilt"],"dependencies":[],
                 "files":[{"hashes":{},"url":"https://example.invalid/fixture.jar",
                           "filename":"fixture.jar","primary":true}]}
                """;
        var object = JsonUtils.GSON.fromJson(json, com.google.gson.JsonObject.class);
        if (environment != null) object.addProperty("environment", environment);
        return JsonUtils.GSON.fromJson(object, ModrinthRemoteAddonRepository.ProjectVersion.class)
                .toVersion().orElseThrow();
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
                List.of(), List.of(game), loader == null ? List.of() : List.of(loader), RemoteAddon.Environment.UNKNOWN);
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

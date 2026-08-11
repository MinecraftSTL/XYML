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
package space.minecraftstl.xyml.ui.swing.page.instances.management.addonupdates;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.addon.LocalAddonFile;
import space.minecraftstl.xyml.addon.RemoteAddon;
import space.minecraftstl.xyml.addon.RemoteAddonRepository;
import space.minecraftstl.xyml.addon.mod.LocalModFile;
import space.minecraftstl.xyml.addon.mod.ModManager;
import space.minecraftstl.xyml.addon.resourcepack.ResourcePackFile;
import space.minecraftstl.xyml.addon.resourcepack.ResourcePackManager;
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.game.GameRepository;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

/// Real blocking repository adapter for installed Mod and resource-pack update checks.
///
/// No I/O happens while the adapter is constructed. A scan reads the local instance and only then
/// contacts each add-on source; source failures stay attached to the affected local add-on so one
/// unavailable service cannot hide independently found updates.
@NotNullByDefault
final class RepositoryAddonUpdateScanAccess implements AddonUpdateScanAccess {
    /// Stable repository containing the managed instance.
    private final GameRepository repository;

    /// Stable target instance identifier.
    private final GameInstanceID instanceId;

    /// Launcher-configured provider used by every remote metadata request.
    private final DownloadProvider downloadProvider;

    /// Immutable source order matching the former launcher update checker.
    private static final @Unmodifiable List<RemoteAddon.Source> UPDATE_SOURCES =
            List.of(RemoteAddon.Source.values());

    /// Creates a real scanner without starting local or remote I/O.
    ///
    /// @param repository repository containing the managed instance
    /// @param instanceId stable non-blank managed instance identifier
    /// @param downloadProvider configured provider used for source requests
    RepositoryAddonUpdateScanAccess(
            GameRepository repository,
            GameInstanceID instanceId,
            DownloadProvider downloadProvider) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.downloadProvider = Objects.requireNonNull(downloadProvider, "downloadProvider");
    }

    /// Performs one fully explicit local scan and remote update check.
    ///
    /// @return immutable scan result with update and per-file failure rows
    /// @throws IOException when local instance discovery cannot be completed
    @Override
    public AddonUpdateScanResult scan() throws IOException {
        String gameVersion = resolveGameVersion();
        @Unmodifiable List<LocalAddonFile> addons = discoverInstalledAddons();
        List<AddonUpdateItem> updates = new ArrayList<>();
        List<AddonUpdateCheckFailure> failures = new ArrayList<>();
        for (LocalAddonFile addon : addons) {
            scanAddon(addon, gameVersion, updates, failures);
        }
        updates.sort(Comparator.comparing(AddonUpdateItem::fileName, String.CASE_INSENSITIVE_ORDER));
        failures.sort(Comparator.comparing(AddonUpdateCheckFailure::fileName, String.CASE_INSENSITIVE_ORDER));
        return new AddonUpdateScanResult(addons.size(), updates, failures);
    }

    /// Resolves the instance's concrete Minecraft version before any remote query starts.
    ///
    /// @return non-blank detected game version
    /// @throws IOException when the instance cannot supply a game version
    private String resolveGameVersion() throws IOException {
        try {
            return repository.getGameVersion(instanceId)
                    .filter(version -> !version.isBlank())
                    .orElseThrow(() -> new IOException("Could not determine the game version for " + instanceId));
        } catch (IOException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new IOException("Could not determine the game version for " + instanceId, failure);
        }
    }

    /// Builds the installed Mod and resource-pack list only after a user starts the scan.
    ///
    /// @return immutable local add-on list
    /// @throws IOException when either local manager cannot read its managed directory
    private @Unmodifiable List<LocalAddonFile> discoverInstalledAddons() throws IOException {
        ModManager modManager = new ModManager(repository, instanceId);
        ResourcePackManager resourcePackManager = new ResourcePackManager(repository, instanceId);
        List<LocalAddonFile> addons = new ArrayList<>();
        addons.addAll(modManager.getLocalFiles());
        addons.addAll(resourcePackManager.getLocalFiles());
        return List.copyOf(addons);
    }

    /// Checks one local file against every supported source and records either its newest update or a failure.
    ///
    /// @param addon locally discovered add-on
    /// @param gameVersion concrete managed game version
    /// @param updates mutable output list for successful update candidates
    /// @param failures mutable output list for all-source failure summaries
    private void scanAddon(
            LocalAddonFile addon,
            String gameVersion,
            List<AddonUpdateItem> updates,
            List<AddonUpdateCheckFailure> failures) {
        @Nullable LocalAddonFile.AddonUpdate newest = null;
        List<String> sourceFailures = new ArrayList<>();
        for (RemoteAddon.Source source : UPDATE_SOURCES) {
            try {
                @Nullable LocalAddonFile.AddonUpdate update = addon.checkUpdates(
                        downloadProvider,
                        gameVersion,
                        source);
                if (update != null && isNewer(update, newest)) {
                    newest = update;
                }
            } catch (IOException | RuntimeException failure) {
                sourceFailures.add(source.name() + ": " + describeFailure(failure));
            }
        }
        if (newest != null) {
            recordWinner(
                    addon,
                    newest,
                    sourceFailures,
                    updates,
                    failures,
                    (update, source) -> resolveSourcePage(addon, update, source));
        } else if (!sourceFailures.isEmpty()) {
            failures.add(new AddonUpdateCheckFailure(
                    addon.getFileName(),
                    addon.getFile(),
                    String.join("; ", sourceFailures)));
        }
    }

    /// Records one selected winner without allowing malformed remote metadata to abort the full scan.
    ///
    /// This package-visible seam accepts the already selected winner so tests can verify final modeling
    /// without constructing a real game repository or scanning an instance directory.
    ///
    /// @param addon local add-on represented by the winner
    /// @param winner newest compatible update selected across all sources
    /// @param sourceFailures recoverable failures already collected while querying individual sources
    /// @param updates mutable output list for a successfully modeled update
    /// @param failures mutable output list for a malformed winner failure
    /// @param sourcePageResolver resolver for the optional source page
    static void recordWinner(
            LocalAddonFile addon,
            LocalAddonFile.AddonUpdate winner,
            List<String> sourceFailures,
            List<AddonUpdateItem> updates,
            List<AddonUpdateCheckFailure> failures,
            BiFunction<LocalAddonFile.AddonUpdate, RemoteAddon.Source, @Nullable URI> sourcePageResolver) {
        Objects.requireNonNull(addon, "addon");
        Objects.requireNonNull(winner, "winner");
        Objects.requireNonNull(sourceFailures, "sourceFailures");
        Objects.requireNonNull(updates, "updates");
        Objects.requireNonNull(failures, "failures");
        Objects.requireNonNull(sourcePageResolver, "sourcePageResolver");
        try {
            RemoteAddon.Source source = winner.targetVersion().self().getSource();
            @Nullable URI sourcePage;
            try {
                sourcePage = sourcePageResolver.apply(winner, source);
            } catch (RuntimeException failure) {
                sourcePage = null;
            }
            updates.add(AddonUpdateItem.from(winner, sourcePage));
        } catch (RuntimeException failure) {
            List<String> details = new ArrayList<>(sourceFailures);
            details.add("Selected update: " + describeFailure(failure));
            failures.add(new AddonUpdateCheckFailure(
                    addon.getFileName(),
                    addon.getFile(),
                    String.join("; ", details)));
        }
    }

    /// Decides whether a candidate has a newer publication timestamp than the current winner.
    ///
    /// @param candidate candidate update returned by one remote source
    /// @param current current newest update, or `null` before the first match
    /// @return whether the candidate should replace the current winner
    private static boolean isNewer(
            LocalAddonFile.AddonUpdate candidate,
            @Nullable LocalAddonFile.AddonUpdate current) {
        return current == null || current.targetVersion().datePublished()
                .isBefore(candidate.targetVersion().datePublished());
    }

    /// Resolves an exact remote project page only after a matching update already exists.
    ///
    /// A failed project-page lookup never discards a valid update result; it only disables the
    /// optional source-page command for that row.
    ///
    /// @param addon local add-on identifying the remote repository type
    /// @param update accepted update candidate
    /// @param source source that supplied the accepted candidate
    /// @return project page URI, or `null` when no reliable page is available
    private @Nullable URI resolveSourcePage(
            LocalAddonFile addon,
            LocalAddonFile.AddonUpdate update,
            RemoteAddon.Source source) {
        @Nullable RemoteAddonRepository remoteRepository = source.getRepoForType(repositoryType(addon));
        if (remoteRepository == null) {
            return null;
        }
        try {
            String sourcePage = remoteRepository.getAddonById(downloadProvider, update.targetVersion().projectId())
                    .pageUrl();
            return sourcePage.isBlank() ? null : URI.create(sourcePage);
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    /// Maps the supported local file implementation to its matching remote catalog type.
    ///
    /// @param addon local add-on selected for update checking
    /// @return matching remote repository type
    private static RemoteAddon.Type repositoryType(LocalAddonFile addon) {
        if (addon instanceof LocalModFile) {
            return RemoteAddon.Type.MOD;
        }
        if (addon instanceof ResourcePackFile) {
            return RemoteAddon.Type.RESOURCE_PACK;
        }
        throw new IllegalArgumentException("Unsupported installed add-on: " + addon.getClass().getName());
    }

    /// Converts a recoverable source exception to a concise non-blank row detail.
    ///
    /// @param failure source failure
    /// @return human-readable concise detail
    private static String describeFailure(Throwable failure) {
        @Nullable String message = Objects.requireNonNull(failure, "failure").getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message;
    }
}

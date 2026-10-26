// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.setting.GameDirectoryManager;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/// Resolves direct-install targets from the launcher-wide currently selected repository.
///
/// The resolver only reads existing launcher state and computes paths. It never creates directories,
/// starts a repository refresh, or contacts a remote source while the catalog is merely displayed.
@NotNullByDefault
public final class LauncherRemoteAddonInstallTargetResolver implements RemoteAddonInstallTargetResolver {
    /// Supplies the repository selected when a target is resolved.
    private final Supplier<XYMLGameRepository> repositorySupplier;

    /// Creates the production resolver backed by the launcher-wide selected repository.
    public LauncherRemoteAddonInstallTargetResolver() {
        this(GameDirectoryManager::getSelectedRepository);
    }

    /// Creates a resolver with an explicit repository boundary for focused tests.
    ///
    /// @param repositorySupplier selected-repository supplier evaluated for each resolution
    LauncherRemoteAddonInstallTargetResolver(Supplier<XYMLGameRepository> repositorySupplier) {
        this.repositorySupplier = Objects.requireNonNull(repositorySupplier, "repositorySupplier");
    }

    /// Resolves a selected direct-install target, returning empty for unavailable or stale launcher state.
    ///
    /// @param kind requested direct-install category
    /// @return selected immutable target, or empty without a usable selected instance
    @Override
    public Optional<RemoteAddonInstallTarget> resolve(RemoteAddonCatalogKind kind) {
        RemoteAddonCatalogKind requestedKind = Objects.requireNonNull(kind, "kind");
        try {
            XYMLGameRepository repository = Objects.requireNonNull(
                    repositorySupplier.get(),
                    "repositorySupplier returned null");
            @Nullable GameInstanceID instanceId = repository.getSelectedInstance();
            return resolve(repository, requestedKind, instanceId);
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    /// Resolves a direct-install target for the supplied instance without consulting global instance selection.
    ///
    /// @param kind requested direct-install category
    /// @param targetInstanceId explicitly selected instance, or null when none is selected
    /// @return selected immutable target, or empty when the explicit instance is unavailable
    @Override
    public Optional<RemoteAddonInstallTarget> resolve(
            RemoteAddonCatalogKind kind,
            @Nullable GameInstanceID targetInstanceId) {
        RemoteAddonCatalogKind requestedKind = Objects.requireNonNull(kind, "kind");
        try {
            XYMLGameRepository repository = Objects.requireNonNull(
                    repositorySupplier.get(),
                    "repositorySupplier returned null");
            return resolve(repository, requestedKind, targetInstanceId);
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    /// Computes one immutable target from a repository snapshot and explicit instance identifier.
    ///
    /// @param repository repository selected for this resolution
    /// @param kind requested direct-install category
    /// @param targetInstanceId explicit target instance, or null when none is selected
    /// @return selected immutable target, or empty for unsupported or stale input
    private static Optional<RemoteAddonInstallTarget> resolve(
            XYMLGameRepository repository,
            RemoteAddonCatalogKind kind,
            @Nullable GameInstanceID targetInstanceId) {
        if (targetInstanceId == null
                || kind == RemoteAddonCatalogKind.WORLD
                || !repository.hasInstance(targetInstanceId)) {
            return Optional.empty();
        }
        Path directory = switch (kind) {
            case MOD -> repository.getModsDirectory(targetInstanceId);
            case RESOURCE_PACK -> repository.getResourcePackDirectory(targetInstanceId);
            case SHADER_PACK -> repository.getRunDirectory(targetInstanceId).resolve("shaderpacks");
            case WORLD -> throw new IllegalStateException("World targets require an explicit save-as resolver");
        };
        return Optional.of(new RemoteAddonInstallTarget(kind, targetInstanceId, directory));
    }
}

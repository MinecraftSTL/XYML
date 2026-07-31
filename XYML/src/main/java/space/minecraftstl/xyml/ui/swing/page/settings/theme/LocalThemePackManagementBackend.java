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
package space.minecraftstl.xyml.ui.swing.page.settings.theme;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.theme.BuiltinThemePack;
import space.minecraftstl.xyml.theme.BuiltinThemePackCatalog;
import space.minecraftstl.xyml.theme.InstalledThemePack;
import space.minecraftstl.xyml.theme.LocalThemePackRepository;
import space.minecraftstl.xyml.theme.Theme;
import space.minecraftstl.xyml.theme.ThemePackAuthor;
import space.minecraftstl.xyml.theme.ThemePackManifest;
import space.minecraftstl.xyml.theme.ThemePackPackage;
import space.minecraftstl.xyml.theme.ThemeReference;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/// Production theme management backend backed only by bundled resources and the local repository.
@NotNullByDefault
public final class LocalThemePackManagementBackend implements ThemePackManagementBackend {
    /// Offline catalog embedded in the launcher artifact.
    private final BuiltinThemePackCatalog builtinCatalog;

    /// Strict local installed-package repository.
    private final LocalThemePackRepository localRepository;

    /// Creates the production backend.
    ///
    /// @param builtinCatalog bundled package catalog
    /// @param localRepository local package repository
    public LocalThemePackManagementBackend(
            BuiltinThemePackCatalog builtinCatalog,
            LocalThemePackRepository localRepository) {
        this.builtinCatalog = Objects.requireNonNull(builtinCatalog, "builtinCatalog");
        this.localRepository = Objects.requireNonNull(localRepository, "localRepository");
    }

    /// Loads bundled and installed manifests concurrently on the supplied worker.
    ///
    /// @param executor caller-owned non-EDT worker executor
    /// @return immutable combined theme index
    @Override
    public CompletionStage<@Unmodifiable List<ThemePackItem>> loadAll(Executor executor) {
        Executor worker = Objects.requireNonNull(executor, "executor");
        CompletionStage<@Unmodifiable List<BuiltinThemePack>> builtinStage = builtinCatalog.loadAll(worker);
        CompletionStage<@Unmodifiable List<InstalledThemePack>> installedStage = localRepository.listInstalled(worker);
        return builtinStage.thenCombine(installedStage, LocalThemePackManagementBackend::combineItems);
    }

    /// Imports and indexes one newly installed local package.
    ///
    /// @param archive selected archive
    /// @param executor caller-owned non-EDT worker executor
    /// @return immutable imported theme index
    @Override
    public CompletionStage<@Unmodifiable List<ThemePackItem>> importArchive(Path archive, Executor executor) {
        return localRepository.importArchive(
                        Objects.requireNonNull(archive, "archive"),
                        Objects.requireNonNull(executor, "executor"))
                .thenApply(pack -> itemsFor(pack, false, pack.directory()));
    }

    /// Revalidates and deletes an exact installed package.
    ///
    /// @param item installed item
    /// @param executor caller-owned non-EDT worker executor
    /// @return completion stage resolved after deletion
    @Override
    public CompletionStage<@Nullable Void> deleteInstalled(ThemePackItem item, Executor executor) {
        ThemePackItem installed = requireInstalled(item);
        return localRepository.deleteInstalled(
                installed.reference().packId(),
                Objects.requireNonNull(installed.installedDirectory(), "installedDirectory"),
                Objects.requireNonNull(executor, "executor"));
    }

    /// Revalidates an installed package and returns only its unchanged exact directory.
    ///
    /// @param item installed item
    /// @param executor caller-owned non-EDT worker executor
    /// @return exact validated directory
    @Override
    public CompletionStage<Path> locateInstalled(ThemePackItem item, Executor executor) {
        ThemePackItem installed = requireInstalled(item);
        Path expected = Objects.requireNonNull(installed.installedDirectory(), "installedDirectory");
        return localRepository.findInstalled(installed.reference().packId(), Objects.requireNonNull(executor, "executor"))
                .thenApply(current -> {
                    if (current == null || !current.directory().equals(expected)) {
                        throw new IllegalStateException("Theme-pack installation changed before it could be opened");
                    }
                    return current.directory();
                });
    }

    /// Combines built-in items before local items and rejects ambiguous exact references.
    ///
    /// @param builtinPacks validated built-in packages
    /// @param installedPacks validated installed packages
    /// @return immutable deterministic theme index
    private static @Unmodifiable List<ThemePackItem> combineItems(
            @Unmodifiable List<BuiltinThemePack> builtinPacks,
            @Unmodifiable List<InstalledThemePack> installedPacks) {
        List<ThemePackItem> result = new ArrayList<>();
        for (BuiltinThemePack pack : builtinPacks) {
            result.addAll(itemsFor(pack, true, null));
        }
        for (InstalledThemePack pack : installedPacks) {
            result.addAll(itemsFor(pack, false, pack.directory()));
        }
        Set<ThemeReference> references = new HashSet<>();
        for (ThemePackItem item : result) {
            if (!references.add(item.reference())) {
                throw new IllegalStateException("Theme inventory contains an ambiguous reference: " + item.reference());
            }
        }
        return List.copyOf(result);
    }

    /// Converts one already validated manifest to lightweight selectable rows without opening assets.
    ///
    /// @param pack validated package
    /// @param builtIn whether the package is embedded
    /// @param installedDirectory exact installed directory, or `null`
    /// @return immutable rows in manifest declaration order
    private static @Unmodifiable List<ThemePackItem> itemsFor(
            ThemePackPackage pack,
            boolean builtIn,
            @Nullable Path installedDirectory) {
        ThemePackManifest manifest = pack.manifest();
        List<ThemePackItem> result = new ArrayList<>(manifest.themes().size());
        for (Theme theme : manifest.themes()) {
            @Nullable String themeName = theme.displayName();
            @Nullable String themeDescription = theme.displayDescription();
            @Unmodifiable List<ThemePackAuthor> authors = theme.authors().isEmpty()
                    ? manifest.authors()
                    : theme.authors();
            result.add(new ThemePackItem(
                    pack.referenceFor(theme),
                    themeName != null ? themeName : manifest.displayName(),
                    manifest.displayName(),
                    manifest.version(),
                    authors.stream().map(ThemePackAuthor::displayName).reduce((left, right) -> left + ", " + right)
                            .orElse(""),
                    themeDescription != null ? themeDescription : manifest.displayDescription(),
                    builtIn,
                    installedDirectory));
        }
        return List.copyOf(result);
    }

    /// Requires an installed item before repository access.
    ///
    /// @param item candidate item
    /// @return the same installed item
    private static ThemePackItem requireInstalled(ThemePackItem item) {
        ThemePackItem checked = Objects.requireNonNull(item, "item");
        if (checked.builtIn() || checked.installedDirectory() == null) {
            throw new IllegalArgumentException("Built-in theme packs cannot be located or deleted");
        }
        return checked;
    }
}

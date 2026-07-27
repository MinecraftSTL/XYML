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
import space.minecraftstl.xyml.addon.RemoteAddon;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JFileChooser;
import java.awt.Component;
import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Resolves remote world downloads through a native save-as chooser after explicit user action.
///
/// Routine panel construction and control refreshes call only [#isSelectionAvailable(RemoteAddonCatalogKind)]
/// and therefore never touch the filesystem or show a dialog. The chooser is opened on the Swing EDT
/// only after the user presses the world download command.
@NotNullByDefault
public final class SwingRemoteWorldSaveTargetResolver implements RemoteAddonInstallTargetResolver {
    /// Interactive chooser boundary retained for deterministic headless tests.
    private final RemoteWorldArchiveSaveChooser saveChooser;

    /// Creates the production resolver backed by `JFileChooser`.
    public SwingRemoteWorldSaveTargetResolver() {
        this(SwingRemoteWorldSaveTargetResolver::showSaveDialog);
    }

    /// Creates a resolver with an explicit save chooser for focused tests.
    ///
    /// @param saveChooser chooser invoked only for an explicit selected-world command
    SwingRemoteWorldSaveTargetResolver(RemoteWorldArchiveSaveChooser saveChooser) {
        this.saveChooser = Objects.requireNonNull(saveChooser, "saveChooser");
    }

    /// Returns no category-only target because a world destination requires the selected artifact name.
    ///
    /// @param kind requested category
    /// @return always empty; callers must use [#resolveSelection]
    @Override
    public Optional<RemoteAddonInstallTarget> resolve(RemoteAddonCatalogKind kind) {
        Objects.requireNonNull(kind, "kind");
        return Optional.empty();
    }

    /// Reports that an explicit world selection can open the save-as chooser without opening it now.
    ///
    /// @param kind requested category
    /// @return true only for the remote world category
    @Override
    public boolean isSelectionAvailable(RemoteAddonCatalogKind kind) {
        return Objects.requireNonNull(kind, "kind") == RemoteAddonCatalogKind.WORLD;
    }

    /// Opens the world save-as chooser and snapshots its exact normalized destination.
    ///
    /// @param kind selected catalog category
    /// @param item selected remote world project
    /// @param version exact selected remote world version
    /// @param owner component owning the modal save chooser
    /// @return exact world archive target, or empty after cancellation
    @Override
    public Optional<RemoteAddonInstallTarget> resolveSelection(
            RemoteAddonCatalogKind kind,
            RemoteAddonCatalogItem item,
            RemoteAddon.Version version,
            Component owner) {
        EdtDispatcher.requireEventDispatchThread();
        RemoteAddonCatalogKind selectedKind = Objects.requireNonNull(kind, "kind");
        RemoteAddonCatalogItem selectedItem = Objects.requireNonNull(item, "item");
        RemoteAddon.Version selectedVersion = Objects.requireNonNull(version, "version");
        Component dialogOwner = Objects.requireNonNull(owner, "owner");
        if (selectedKind != RemoteAddonCatalogKind.WORLD || selectedItem.kind() != selectedKind) {
            return Optional.empty();
        }
        Optional<Path> destination = Objects.requireNonNull(
                saveChooser.choose(dialogOwner, suggestedFileName(selectedVersion)),
                "saveChooser returned null optional");
        return destination.map(RemoteAddonInstallTarget::worldSaveAs);
    }

    /// Opens a native save dialog with the provider filename as an editable suggestion.
    ///
    /// @param owner component owning the modal chooser
    /// @param suggestedFileName safe single-component suggested filename
    /// @return selected path, or empty after cancellation
    private static Optional<Path> showSaveDialog(Component owner, String suggestedFileName) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(i18n("world.download"));
        chooser.setDialogType(JFileChooser.SAVE_DIALOG);
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setSelectedFile(Path.of(suggestedFileName).toFile());
        if (chooser.showSaveDialog(owner) != JFileChooser.APPROVE_OPTION) {
            return Optional.empty();
        }
        @Nullable File selectedFile = chooser.getSelectedFile();
        return selectedFile == null
                ? Optional.empty()
                : Optional.of(selectedFile.toPath().toAbsolutePath().normalize());
    }

    /// Converts untrusted provider metadata into a safe editable filename suggestion.
    ///
    /// The selected exact destination remains independent of this suggestion. Invalid, absolute, or
    /// path-bearing provider names fall back to a stable ZIP filename.
    ///
    /// @param version selected provider version
    /// @return safe single-component filename
    private static String suggestedFileName(RemoteAddon.Version version) {
        @Nullable String candidate = Objects.requireNonNull(version, "version").file().filename();
        if (candidate == null || candidate.isBlank()) {
            return "world.zip";
        }
        try {
            Path path = Path.of(candidate);
            @Nullable Path fileName = path.getFileName();
            if (path.isAbsolute() || path.getNameCount() != 1 || fileName == null || fileName.toString().isBlank()) {
                return "world.zip";
            }
            return fileName.toString();
        } catch (InvalidPathException ignored) {
            return "world.zip";
        }
    }
}

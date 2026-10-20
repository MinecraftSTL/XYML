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
package space.minecraftstl.xyml.ui.swing.page.shaderpacks;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.util.io.DeletionMode;
import space.minecraftstl.xyml.util.io.FileUtils;

import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Component;
import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/// Default Swing and AWT implementation for shader-pack dialogs and desktop actions.
@NotNullByDefault
public final class DefaultShaderPackCatalogInteractions implements ShaderPackCatalogInteractions {
    /// Localized action text.
    private final ShaderPackCatalogActionStrings strings;

    /// Caller-owned executor for desktop and filesystem work.
    private final Executor executor;

    /// Creates production interactions.
    ///
    /// @param strings localized action text
    /// @param executor caller-owned background executor
    public DefaultShaderPackCatalogInteractions(
            ShaderPackCatalogActionStrings strings,
            Executor executor) {
        this.strings = Objects.requireNonNull(strings, "strings");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /// Opens a multi-selection file chooser accepting ZIP files and directories.
    @Override
    public List<Path> chooseImportFiles(Component owner, Path currentDirectory) {
        EdtDispatcher.requireEventDispatchThread();
        JFileChooser chooser = new JFileChooser(Objects.requireNonNull(currentDirectory, "currentDirectory").toFile());
        chooser.setDialogTitle(strings.importDialogTitle());
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setMultiSelectionEnabled(true);
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter(strings.zipFileDescription(), "zip"));
        if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) {
            return List.of();
        }
        List<Path> selected = new ArrayList<>();
        for (java.io.File file : chooser.getSelectedFiles()) {
            selected.add(file.toPath().toAbsolutePath().normalize());
        }
        return List.copyOf(selected);
    }

    /// Chooses one deletion mode with recycle-bin-first behavior.
    @Override
    public @Nullable DeletionMode chooseDeleteMode(Component owner, ShaderPackCatalogItem target) {
        Objects.requireNonNull(target, "target");
        return chooseDeleteMode(owner, strings.deleteConfirmationFormat().formatted(target.fileName()));
    }

    /// Chooses a batch deletion mode with recycle-bin-first behavior.
    @Override
    public @Nullable DeletionMode chooseDeleteModeSelected(Component owner, int selectedCount) {
        if (selectedCount <= 0) {
            throw new IllegalArgumentException("selectedCount must be positive");
        }
        return chooseDeleteMode(
                owner,
                strings.batchDeleteConfirmationFormat().formatted(selectedCount));
    }

    /// Chooses one or more backends with all detected backends selected by default.
    @Override
    public @Nullable Set<ShaderPackBackend> chooseBackends(
            Component owner,
            Set<ShaderPackBackend> availableBackends) {
        EdtDispatcher.requireEventDispatchThread();
        Set<ShaderPackBackend> checkedBackends = Set.copyOf(
                Objects.requireNonNull(availableBackends, "availableBackends"));
        if (checkedBackends.isEmpty()) {
            return null;
        }
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.add(new javax.swing.JLabel(strings.backendDialogPrompt()));
        EnumSet<ShaderPackBackend> selected = EnumSet.noneOf(ShaderPackBackend.class);
        for (ShaderPackBackend backend : ShaderPackBackend.values()) {
            if (!checkedBackends.contains(backend)) {
                continue;
            }
            JCheckBox checkbox = new JCheckBox(backend.displayName(), true);
            checkbox.setName("shaderPackBackend" + backend.name());
            panel.add(checkbox);
            checkbox.addActionListener(event -> {
                if (checkbox.isSelected()) {
                    selected.add(backend);
                } else {
                    selected.remove(backend);
                }
            });
            selected.add(backend);
        }
        int result = JOptionPane.showConfirmDialog(
                owner,
                panel,
                strings.backendDialogTitle(),
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        return result == JOptionPane.OK_OPTION && !selected.isEmpty() ? Set.copyOf(selected) : null;
    }

    /// Reveals one pack through the platform desktop.
    @Override
    public CompletionStage<@Nullable Void> reveal(ShaderPackCatalogItem target) {
        Path path = Objects.requireNonNull(target, "target").path();
        return runDesktop(() -> {
            Desktop desktop = desktop();
            if (desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
                desktop.browseFileDirectory(path.toFile());
                return;
            }
            Path parent = Objects.requireNonNull(path.getParent(), "target parent");
            desktop.open(parent.toFile());
        });
    }

    /// Ensures and opens the shaderpacks directory.
    @Override
    public CompletionStage<@Nullable Void> openDirectory(Path directory) {
        Path checked = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        return runDesktop(() -> {
            Files.createDirectories(checked);
            desktop().open(checked.toFile());
        });
    }

    /// Shows one failure dialog on the event-dispatch thread.
    @Override
    public void showFailure(Component owner, String title, String detail) {
        EdtDispatcher.requireEventDispatchThread();
        JOptionPane.showMessageDialog(
                owner,
                Objects.requireNonNull(detail, "detail"),
                Objects.requireNonNull(title, "title"),
                JOptionPane.ERROR_MESSAGE);
    }

    /// Chooses a deletion mode after confirmation when recycle-bin support is unavailable.
    ///
    /// @param owner dialog owner
    /// @param confirmation localized confirmation message
    /// @return selected deletion mode, or null after cancellation
    private @Nullable DeletionMode chooseDeleteMode(Component owner, String confirmation) {
        EdtDispatcher.requireEventDispatchThread();
        if (FileUtils.isMoveToTrashSupported()) {
            return DeletionMode.RECYCLE_BIN_FIRST;
        }
        int result = JOptionPane.showConfirmDialog(
                owner,
                confirmation,
                strings.deleteAction(),
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        return result == JOptionPane.OK_OPTION ? DeletionMode.PERMANENT : null;
    }

    /// Runs one desktop action on the injected executor.
    ///
    /// @param action action to execute
    /// @return completion stage
    private CompletionStage<@Nullable Void> runDesktop(DesktopAction action) {
        CompletableFuture<@Nullable Void> result = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    action.run();
                    result.complete(null);
                } catch (IOException | RuntimeException | Error failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    /// Resolves the supported platform desktop.
    ///
    /// @return platform desktop
    private static Desktop desktop() {
        if (!Desktop.isDesktopSupported()) {
            throw new UnsupportedOperationException("Desktop integration is unavailable");
        }
        return Desktop.getDesktop();
    }

    /// One desktop operation.
    @FunctionalInterface
    @NotNullByDefault
    private interface DesktopAction {
        /// Runs the platform operation.
        ///
        /// @throws IOException when the platform declines the request
        void run() throws IOException;
    }
}

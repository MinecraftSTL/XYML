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

import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.setting.GameDirectoryManager;
import space.minecraftstl.xyml.task.Schedulers;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.SwingUiDispatcher;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingConstants;
import java.awt.Desktop;
import java.awt.Font;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Provides usable add-on category workflows beside the built-in game-version installer.
///
/// Every category exposes a user-triggered Modrinth browse action. Categories associated with a
/// selected instance additionally create and reveal the exact instance directory, so the page has
/// an offline local workflow even when a user does not want to use a browser. Local modpack imports
/// remain inside the modpack category and use the existing task and progress infrastructure.
@NotNullByDefault
public final class DownloadCategoryPanel extends JPanel implements AutoCloseable {
    /// Category tab host retained for keyboard navigation and focused UI verification.
    private final JTabbedPane categoryTabs;

    /// Real local-archive importer owned by the modpack tab.
    private final LocalModpackImportPanel localModpackImporter;

    /// Feedback for the latest external browse or directory-reveal request.
    private final JLabel statusLabel;

    /// Whether this panel no longer accepts user actions or worker-to-EDT feedback.
    private volatile boolean closed;

    /// Creates every content category without starting network work or opening platform applications.
    ///
    /// @param taskProgressStrings localized task lifecycle controls for local modpack imports
    /// @param animator optional shared determinate-progress animator
    /// @param progressAnimationDuration non-negative determinate-progress animation duration
    public DownloadCategoryPanel(
            TaskProgressStrings taskProgressStrings,
            @Nullable SwingAnimator animator,
            Duration progressAnimationDuration) {
        super(new MigLayout("insets 0, fill, wrap 1", "[grow,fill]", "[grow,fill]8[]"));
        EdtDispatcher.requireEventDispatchThread();

        categoryTabs = new JTabbedPane();
        categoryTabs.setName("downloadsCategoryTabs");
        localModpackImporter = new LocalModpackImportPanel(
                Objects.requireNonNull(taskProgressStrings, "taskProgressStrings"),
                animator,
                Objects.requireNonNull(progressAnimationDuration, "progressAnimationDuration"));
        for (DownloadCategory category : DownloadCategory.values()) {
            categoryTabs.addTab(
                    i18n(category.titleKey()),
                    category == DownloadCategory.MODPACK
                            ? createModpackTab(category)
                            : createDirectoryTab(category));
        }
        add(categoryTabs, "grow");

        statusLabel = new JLabel("", SwingConstants.LEADING);
        statusLabel.setName("downloadsCategoryStatus");
        add(statusLabel, "growx, h 24!");
    }

    /// Returns the stable category tab host.
    ///
    /// @return user-selectable content categories
    public JTabbedPane categoryTabs() {
        return categoryTabs;
    }

    /// Cancels and releases the local importer, then rejects late desktop-action feedback.
    @Override
    public void close() {
        closed = true;
        localModpackImporter.close();
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            categoryTabs.setEnabled(false);
            statusLabel.setText("");
            statusLabel.setToolTipText(null);
        });
    }

    /// Builds the modpack tab with its real local import surface and external catalog route.
    ///
    /// @param category fixed modpack category metadata
    /// @return configured modpack tab
    private JPanel createModpackTab(DownloadCategory category) {
        JPanel panel = new JPanel(new MigLayout(
                "insets 0, fill, wrap 1",
                "[grow,fill]",
                "[]12[grow,fill]"));
        panel.setOpaque(false);
        panel.setName("downloadsCategory" + category.name());
        panel.add(createCategoryActions(category), "growx");
        localModpackImporter.setName("downloadsLocalModpackImporter");
        panel.add(localModpackImporter, "grow");
        return panel;
    }

    /// Builds one add-on category with immediate catalog browsing and local directory reveal commands.
    ///
    /// @param category category whose commands the tab represents
    /// @return configured add-on category tab
    private JPanel createDirectoryTab(DownloadCategory category) {
        JPanel panel = new JPanel(new MigLayout(
                "insets 20, fillx, wrap 1",
                "[grow,fill]",
                "[]16[]"));
        panel.setOpaque(false);
        panel.setName("downloadsCategory" + category.name());

        JLabel heading = new JLabel(i18n(category.titleKey()));
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 22.0F));
        panel.add(heading, "growx");
        panel.add(createCategoryActions(category), "growx");
        return panel;
    }

    /// Creates the two command buttons shared by every category.
    ///
    /// @param category category for the commands
    /// @return compact action band
    private JPanel createCategoryActions(DownloadCategory category) {
        JPanel actions = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill][grow,fill]", "[40!]"));
        actions.setOpaque(false);

        JButton browseButton = new JButton("Modrinth");
        browseButton.setName("downloadsBrowse" + category.name());
        browseButton.setToolTipText(i18n("download.external_link"));
        browseButton.addActionListener(event -> browseCategory(category));
        actions.add(browseButton, "grow, h 40!");

        JButton revealButton = new JButton(i18n("button.reveal_dir"));
        revealButton.setName("downloadsReveal" + category.name());
        revealButton.setToolTipText(i18n(category.directoryKey()));
        revealButton.addActionListener(event -> revealCategoryDirectory(category));
        actions.add(revealButton, "grow, h 40!");
        return actions;
    }

    /// Opens the selected category's Modrinth catalog after an explicit user request.
    ///
    /// @param category category whose catalog should open in the platform browser
    private void browseCategory(DownloadCategory category) {
        runDesktopAction(
                desktop -> {
                    if (!desktop.isSupported(Desktop.Action.BROWSE)) {
                        throw new IOException("The platform desktop cannot browse external download catalogs");
                    }
                    desktop.browse(category.catalogUri());
                },
                i18n("download.external_link"));
    }

    /// Creates and opens the exact category directory for the selected instance or configured game root.
    ///
    /// @param category category whose managed directory should open
    private void revealCategoryDirectory(DownloadCategory category) {
        @Nullable Path directory = resolveCategoryDirectory(category);
        if (directory == null) {
            publishStatus(i18n("version.switch"));
            return;
        }

        Path target = directory;
        runDesktopAction(
                desktop -> {
                    if (!desktop.isSupported(Desktop.Action.OPEN)) {
                        throw new IOException("The platform desktop cannot open local download directories");
                    }
                    Files.createDirectories(target);
                    desktop.open(target.toFile());
                },
                i18n("button.reveal_dir"));
    }

    /// Resolves a category's true local destination from the current repository selection.
    ///
    /// A modpack catalog belongs to the configured game root. Every other category belongs to an
    /// instance running directory and therefore requires that an instance is selected.
    ///
    /// @param category category whose directory is required
    /// @return a concrete managed directory, or null when no selected instance can supply one
    private static @Nullable Path resolveCategoryDirectory(DownloadCategory category) {
        try {
            XYMLGameRepository repository = GameDirectoryManager.getSelectedRepository();
            if (!category.requiresSelectedInstance()) {
                return repository.getGameDirectory().getPath().toPath();
            }

            @Nullable String instanceId = repository.getSelectedInstance();
            if (instanceId == null || instanceId.isBlank()) {
                return null;
            }
            return switch (category) {
                case MODS -> repository.getModsDirectory(instanceId);
                case RESOURCE_PACKS -> repository.getResourcePackDirectory(instanceId);
                case SHADERS -> repository.getRunDirectory(instanceId).resolve("shaderpacks");
                case WORLDS -> repository.getSavesDirectory(instanceId);
                case MODPACK -> repository.getGameDirectory().getPath().toPath();
            };
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /// Schedules one potentially blocking platform-desktop action away from the Swing event dispatch thread.
    ///
    /// @param action desktop action to execute
    /// @param successStatus localized feedback shown when the desktop accepts the request
    private void runDesktopAction(DesktopAction action, String successStatus) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(successStatus, "successStatus");
        if (closed) {
            return;
        }

        try {
            Schedulers.io().execute(() -> {
                try {
                    if (!Desktop.isDesktopSupported()) {
                        throw new IOException("The platform does not provide desktop integration");
                    }
                    action.run(Desktop.getDesktop());
                    publishStatus(successStatus);
                } catch (IOException | RuntimeException failure) {
                    LOG.warning("Failed to execute a download-category desktop action", failure);
                    publishStatus(i18n("download.failed.no_code"));
                }
            });
        } catch (RuntimeException schedulingFailure) {
            LOG.warning("Failed to schedule a download-category desktop action", schedulingFailure);
            publishStatus(i18n("download.failed.no_code"));
        }
    }

    /// Publishes one status result only while this panel remains usable.
    ///
    /// @param status localized user-visible result
    private void publishStatus(String status) {
        Objects.requireNonNull(status, "status");
        SwingUiDispatcher.INSTANCE.dispatchOrRun(() -> {
            if (closed) {
                return;
            }
            statusLabel.setText(status);
            statusLabel.setToolTipText(status.isBlank() ? null : status);
        });
    }

    /// Executes one desktop operation after platform capability checks complete.
    @FunctionalInterface
    @NotNullByDefault
    private interface DesktopAction {
        /// Runs the action against a supported platform desktop.
        ///
        /// @param desktop supported platform desktop
        /// @throws IOException when the platform declines the requested operation
        void run(Desktop desktop) throws IOException;
    }

    /// Describes one restored content category and its stable external/local routes.
    @NotNullByDefault
    private enum DownloadCategory {
        /// Curated modpack archives and the configured game-root directory.
        MODPACK("modpack", "folder.game", false, "https://modrinth.com/modpacks"),

        /// Instance mod archives and their managed mods directory.
        MODS("mods.manage", "folder.mod", true, "https://modrinth.com/mods"),

        /// Instance resource packs and their managed resource-pack directory.
        RESOURCE_PACKS("resourcepack", "folder.resourcepacks", true, "https://modrinth.com/resourcepacks"),

        /// Instance shader packs and their standard shaderpacks directory.
        SHADERS("download.shader", "folder.shaderpacks", true, "https://modrinth.com/shaders"),

        /// Instance world archives and their Minecraft saves directory.
        WORLDS("world", "folder.saves", true, "https://modrinth.com/worlds");

        /// Localization key used as the tab title.
        private final String titleKey;

        /// Localization key used as the reveal-directory command tooltip.
        private final String directoryKey;

        /// Browser endpoint opened after an explicit user command.
        private final URI catalogUri;

        /// Whether the directory must resolve from a selected game instance.
        private final boolean requiresSelectedInstance;

        /// Creates fixed metadata for one content category.
        ///
        /// @param titleKey localization key used for the tab title
        /// @param directoryKey localization key used for the local directory
        /// @param requiresSelectedInstance whether a selected instance supplies the local directory
        /// @param catalogUri browser endpoint for external discovery
        DownloadCategory(
                String titleKey,
                String directoryKey,
                boolean requiresSelectedInstance,
                String catalogUri) {
            this.titleKey = titleKey;
            this.directoryKey = directoryKey;
            this.requiresSelectedInstance = requiresSelectedInstance;
            this.catalogUri = URI.create(catalogUri);
        }

        /// Returns the localization key for the category tab.
        ///
        /// @return category-title localization key
        String titleKey() {
            return titleKey;
        }

        /// Returns the localization key for the local directory tooltip.
        ///
        /// @return directory-label localization key
        String directoryKey() {
            return directoryKey;
        }

        /// Returns the external browser catalog endpoint.
        ///
        /// @return fixed category catalog URI
        URI catalogUri() {
            return catalogUri;
        }

        /// Returns whether a selected instance is required for local-directory resolution.
        ///
        /// @return true when the local target belongs to an instance
        boolean requiresSelectedInstance() {
            return requiresSelectedInstance;
        }
    }
}

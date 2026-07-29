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
package space.minecraftstl.xyml.ui.swing.page.instances.management;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.GameRepository;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.page.instances.management.addonupdates.AddonUpdatesPanel;
import space.minecraftstl.xyml.ui.swing.page.instances.management.backups.WorldBackupsPanel;
import space.minecraftstl.xyml.ui.swing.page.instances.management.datapacks.DataPackManagementPanel;
import space.minecraftstl.xyml.ui.swing.page.instances.management.export.ModpackExportPanel;
import space.minecraftstl.xyml.ui.swing.page.instances.management.installers.InstanceInstallerPanel;
import space.minecraftstl.xyml.ui.swing.page.instances.management.maintenance.InstanceMaintenanceLaunchActions;
import space.minecraftstl.xyml.ui.swing.page.instances.management.maintenance.InstanceMaintenancePanel;
import space.minecraftstl.xyml.ui.swing.page.instances.management.worlds.WorldCatalogPanel;
import space.minecraftstl.xyml.ui.swing.page.instances.management.worlds.WorldQuickPlayActions;
import space.minecraftstl.xyml.ui.swing.page.mods.DefaultModCatalogModel;
import space.minecraftstl.xyml.ui.swing.page.mods.ModCatalogActionStrings;
import space.minecraftstl.xyml.ui.swing.page.mods.ModCatalogInteractions;
import space.minecraftstl.xyml.ui.swing.page.mods.ModCatalogPanel;
import space.minecraftstl.xyml.ui.swing.page.mods.ModCatalogStatusStrings;
import space.minecraftstl.xyml.ui.swing.page.mods.ModCatalogStrings;
import space.minecraftstl.xyml.ui.swing.page.resourcepacks.DefaultResourcePackCatalogModel;
import space.minecraftstl.xyml.ui.swing.page.resourcepacks.ResourcePackCatalogActionStrings;
import space.minecraftstl.xyml.ui.swing.page.resourcepacks.ResourcePackCatalogInteractions;
import space.minecraftstl.xyml.ui.swing.page.resourcepacks.ResourcePackCatalogPanel;
import space.minecraftstl.xyml.ui.swing.page.resourcepacks.ResourcePackCatalogStatusStrings;
import space.minecraftstl.xyml.ui.swing.page.resourcepacks.ResourcePackCatalogStrings;
import space.minecraftstl.xyml.ui.swing.page.schematics.SchematicBrowserInteractions;
import space.minecraftstl.xyml.ui.swing.page.schematics.SchematicBrowserStrings;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.event.ChangeListener;
import java.awt.Font;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Hosts one instance overview alongside lifecycle, settings, loader, add-on, world, backup, and schematic tools.
///
/// All tabs are constructed on the EDT, while their filesystem work remains lazy and uses the supplied
/// executor. The view owns all child lifecycles and returns to the instance list through one shared toolbar.
@NotNullByDefault
public final class DefaultInstanceManagementView extends JPanel implements InstanceManagementView {
    /// Stable repository instance identifier represented by this view.
    private final String instanceId;

    /// Overview and local file operations owned by the first management tab.
    private final InstanceOverviewPanel overview;

    /// Rename, duplicate, and delete controls when the repository exposes XYML lifecycle APIs, or null otherwise.
    private final @Nullable InstanceLifecyclePanel lifecycle;

    /// Instance-specific launch settings when the repository exposes XYML settings persistence, or null otherwise.
    private final @Nullable InstanceGameSettingsPanel gameSettings;

    /// Lazy existing-instance loader and installer management when XYML dependency APIs are available.
    private final @Nullable InstanceInstallerPanel installers;

    /// Lazy launch, repair, and cleanup tools backed by application-owned commands, or null when unavailable.
    private final @Nullable InstanceMaintenancePanel maintenance;

    /// Installed-Mod catalog owned by the Mods tab.
    private final ModCatalogPanel mods;

    /// Resource-pack catalog owned by the resource-pack tab.
    private final ResourcePackCatalogPanel resourcePacks;

    /// Lazy world catalog owned by the Worlds tab.
    private final WorldCatalogPanel worlds;

    /// Lazy selected-world data-pack catalog.
    private final DataPackManagementPanel dataPacks;

    /// Lazy local world-backup management surface.
    private final WorldBackupsPanel backups;

    /// Lazy offline modpack archive exporter for repositories exposing XYML export APIs, or null otherwise.
    private final @Nullable ModpackExportPanel modpackExport;

    /// Explicit scan-and-apply page for installed Mod and resource-pack updates.
    private final AddonUpdatesPanel addonUpdates;

    /// Schematic browser host owned by the schematic tab.
    private final SchematicInstanceManagementView schematics;

    /// Shared return command disabled after close begins.
    private final JButton returnButton = new JButton();

    /// Stable tab container retaining each tool's independent lazy state.
    private final JTabbedPane tabs = new JTabbedPane();

    /// Activates local-catalog sources only after users select their corresponding tab.
    private final ChangeListener lazyTabListener = event -> activateSelectedLazyTab();

    /// Prevents repeated child and component cleanup.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Creates the complete production tabs with world quick play and instance maintenance commands.
    ///
    /// @param repository repository containing the managed instance
    /// @param schematicDirectoryResolver resolver for the managed instance's schematic root
    /// @param instanceId stable non-blank repository instance identifier
    /// @param executor caller-owned executor for filesystem and metadata work
    /// @param managementStrings localized outer management text
    /// @param schematicStrings localized schematic-browser text
    /// @param schematicInteractions schematic dialog and desktop interactions
    /// @param modStrings localized installed-Mod content text
    /// @param modStatusStrings localized installed-Mod lifecycle text
    /// @param modActionStrings localized installed-Mod action text
    /// @param modInteractions installed-Mod dialog and desktop interactions
    /// @param resourcePackStrings localized resource-pack content text
    /// @param resourcePackStatusStrings localized resource-pack lifecycle text
    /// @param resourcePackActionStrings localized resource-pack action text
    /// @param resourcePackInteractions resource-pack dialog and desktop interactions
    /// @param returnCommand coordinator command returning to the instance list
    /// @param taskProgressStrings localized task-progress labels for long-running instance operations
    /// @param animator optional shared motion-aware progress animator
    /// @param progressAnimationDuration non-negative progress animation duration for instance operations
    /// @param worldQuickPlayActions non-blocking launch and script commands bound to this instance's worlds
    /// @param maintenanceLaunchActions test-launch and script commands, or null when the caller cannot provide them
    public DefaultInstanceManagementView(
            GameRepository repository,
            SchematicDirectoryResolver schematicDirectoryResolver,
            String instanceId,
            Executor executor,
            SchematicInstanceManagementStrings managementStrings,
            SchematicBrowserStrings schematicStrings,
            SchematicBrowserInteractions schematicInteractions,
            ModCatalogStrings modStrings,
            ModCatalogStatusStrings modStatusStrings,
            ModCatalogActionStrings modActionStrings,
            ModCatalogInteractions modInteractions,
            ResourcePackCatalogStrings resourcePackStrings,
            ResourcePackCatalogStatusStrings resourcePackStatusStrings,
            ResourcePackCatalogActionStrings resourcePackActionStrings,
            ResourcePackCatalogInteractions resourcePackInteractions,
            Runnable returnCommand,
            TaskProgressStrings taskProgressStrings,
            @Nullable SwingAnimator animator,
            Duration progressAnimationDuration,
            WorldQuickPlayActions worldQuickPlayActions,
            @Nullable InstanceMaintenanceLaunchActions maintenanceLaunchActions) {
        super(new MigLayout(
                "insets 0, fill, wrap 1",
                "[grow,fill]",
                "[]12[grow,fill]"));
        EdtDispatcher.requireEventDispatchThread();
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(schematicDirectoryResolver, "schematicDirectoryResolver");
        this.instanceId = requireNonBlank(instanceId, "instanceId");
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(managementStrings, "managementStrings");
        Objects.requireNonNull(schematicStrings, "schematicStrings");
        Objects.requireNonNull(schematicInteractions, "schematicInteractions");
        Objects.requireNonNull(modStrings, "modStrings");
        Objects.requireNonNull(modStatusStrings, "modStatusStrings");
        Objects.requireNonNull(modActionStrings, "modActionStrings");
        Objects.requireNonNull(modInteractions, "modInteractions");
        Objects.requireNonNull(resourcePackStrings, "resourcePackStrings");
        Objects.requireNonNull(resourcePackStatusStrings, "resourcePackStatusStrings");
        Objects.requireNonNull(resourcePackActionStrings, "resourcePackActionStrings");
        Objects.requireNonNull(resourcePackInteractions, "resourcePackInteractions");
        Objects.requireNonNull(returnCommand, "returnCommand");
        Objects.requireNonNull(taskProgressStrings, "taskProgressStrings");
        Objects.requireNonNull(progressAnimationDuration, "progressAnimationDuration");
        Objects.requireNonNull(worldQuickPlayActions, "worldQuickPlayActions");
        if (progressAnimationDuration.isNegative()) {
            throw new IllegalArgumentException("progressAnimationDuration must not be negative");
        }

        @Nullable InstanceOverviewPanel createdOverview = null;
        @Nullable InstanceLifecyclePanel createdLifecycle = null;
        @Nullable InstanceGameSettingsPanel createdGameSettings = null;
        @Nullable InstanceInstallerPanel createdInstallers = null;
        @Nullable InstanceMaintenancePanel createdMaintenance = null;
        @Nullable ModCatalogPanel createdMods = null;
        @Nullable ResourcePackCatalogPanel createdResourcePacks = null;
        @Nullable WorldCatalogPanel createdWorlds = null;
        @Nullable DataPackManagementPanel createdDataPacks = null;
        @Nullable WorldBackupsPanel createdBackups = null;
        @Nullable ModpackExportPanel createdModpackExport = null;
        @Nullable AddonUpdatesPanel createdAddonUpdates = null;
        @Nullable SchematicInstanceManagementView createdSchematics = null;
        try {
            createdOverview = new InstanceOverviewPanel(repository, this.instanceId, executor);
            if (repository instanceof XYMLGameRepository xymlRepository) {
                createdLifecycle = new InstanceLifecyclePanel(
                        xymlRepository,
                        this.instanceId,
                        executor,
                        returnCommand);
                createdGameSettings = new InstanceGameSettingsPanel(xymlRepository, this.instanceId, executor);
                createdInstallers = new InstanceInstallerPanel(
                        xymlRepository,
                        this.instanceId,
                        taskProgressStrings,
                        animator,
                        progressAnimationDuration);
                if (maintenanceLaunchActions != null) {
                    createdMaintenance = new InstanceMaintenancePanel(
                            xymlRepository,
                            this.instanceId,
                            maintenanceLaunchActions,
                            taskProgressStrings,
                            animator,
                            progressAnimationDuration);
                }
                createdModpackExport = new ModpackExportPanel(
                        xymlRepository,
                        this.instanceId,
                        executor,
                        taskProgressStrings,
                        animator,
                        progressAnimationDuration);
            }
            createdMods = new ModCatalogPanel(
                    new DefaultModCatalogModel(
                            repository,
                            this.instanceId,
                            executor,
                            modStatusStrings),
                    modStrings,
                    modActionStrings,
                    modInteractions);
            createdResourcePacks = new ResourcePackCatalogPanel(
                    new DefaultResourcePackCatalogModel(
                            repository,
                            this.instanceId,
                            executor,
                            resourcePackStatusStrings),
                    resourcePackStrings,
                    resourcePackActionStrings,
                    resourcePackInteractions,
                    repository.getResourcePackDirectory(this.instanceId));
            createdWorlds = new WorldCatalogPanel(
                    repository,
                    this.instanceId,
                    executor,
                    worldQuickPlayActions);
            createdDataPacks = new DataPackManagementPanel(repository, this.instanceId, executor);
            createdBackups = new WorldBackupsPanel(repository, this.instanceId, executor);
            createdAddonUpdates = new AddonUpdatesPanel(
                    repository,
                    this.instanceId,
                    executor,
                    taskProgressStrings,
                    animator,
                    progressAnimationDuration);
            createdSchematics = new SchematicInstanceManagementView(
                    this.instanceId,
                    schematicDirectoryResolver,
                    executor,
                    managementStrings,
                    schematicStrings,
                    schematicInteractions,
                    () -> { },
                    false);
            overview = createdOverview;
            lifecycle = createdLifecycle;
            gameSettings = createdGameSettings;
            installers = createdInstallers;
            maintenance = createdMaintenance;
            mods = createdMods;
            resourcePacks = createdResourcePacks;
            worlds = createdWorlds;
            dataPacks = createdDataPacks;
            backups = createdBackups;
            modpackExport = createdModpackExport;
            addonUpdates = createdAddonUpdates;
            schematics = createdSchematics;
            configureComponents(
                    managementStrings,
                    modStrings,
                    resourcePackStrings,
                    schematicStrings,
                    returnCommand);
        } catch (RuntimeException | Error constructionFailure) {
            @Nullable Throwable cleanupFailure = null;
            if (createdSchematics != null) {
                cleanupFailure = attemptCleanup(cleanupFailure, createdSchematics::close);
            }
            if (createdWorlds != null) {
                cleanupFailure = attemptCleanup(cleanupFailure, createdWorlds::close);
            }
            if (createdAddonUpdates != null) {
                cleanupFailure = attemptCleanup(cleanupFailure, createdAddonUpdates::close);
            }
            if (createdModpackExport != null) {
                cleanupFailure = attemptCleanup(cleanupFailure, createdModpackExport::close);
            }
            if (createdBackups != null) {
                cleanupFailure = attemptCleanup(cleanupFailure, createdBackups::close);
            }
            if (createdDataPacks != null) {
                cleanupFailure = attemptCleanup(cleanupFailure, createdDataPacks::close);
            }
            if (createdResourcePacks != null) {
                cleanupFailure = attemptCleanup(cleanupFailure, createdResourcePacks::close);
            }
            if (createdMods != null) {
                cleanupFailure = attemptCleanup(cleanupFailure, createdMods::close);
            }
            if (createdMaintenance != null) {
                cleanupFailure = attemptCleanup(cleanupFailure, createdMaintenance::close);
            }
            if (createdInstallers != null) {
                cleanupFailure = attemptCleanup(cleanupFailure, createdInstallers::close);
            }
            if (createdGameSettings != null) {
                cleanupFailure = attemptCleanup(cleanupFailure, createdGameSettings::close);
            }
            if (createdLifecycle != null) {
                cleanupFailure = attemptCleanup(cleanupFailure, createdLifecycle::close);
            }
            if (createdOverview != null) {
                cleanupFailure = attemptCleanup(cleanupFailure, createdOverview::close);
            }
            if (cleanupFailure != null && cleanupFailure != constructionFailure) {
                constructionFailure.addSuppressed(cleanupFailure);
            }
            throw constructionFailure;
        }
    }

    /// Returns the stable repository identifier represented by this view.
    ///
    /// @return stable instance identifier
    @Override
    public String instanceId() {
        return instanceId;
    }

    /// Returns this management root for coordinator hosting on the EDT.
    ///
    /// @return this view component
    @Override
    public JComponent component() {
        EdtDispatcher.requireEventDispatchThread();
        return this;
    }

    /// Closes all tabs and releases their Swing component trees exactly once.
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        AtomicReference<@Nullable Throwable> cleanupFailure = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> {
                @Nullable Throwable failure = null;
                failure = attemptCleanup(failure, schematics::close);
                failure = attemptCleanup(failure, addonUpdates::close);
                @Nullable ModpackExportPanel currentModpackExport = modpackExport;
                if (currentModpackExport != null) {
                    failure = attemptCleanup(failure, currentModpackExport::close);
                }
                failure = attemptCleanup(failure, backups::close);
                failure = attemptCleanup(failure, dataPacks::close);
                failure = attemptCleanup(failure, worlds::close);
                failure = attemptCleanup(failure, resourcePacks::close);
                failure = attemptCleanup(failure, mods::close);
                @Nullable InstanceInstallerPanel currentInstallers = installers;
                if (currentInstallers != null) {
                    failure = attemptCleanup(failure, currentInstallers::close);
                }
                @Nullable InstanceMaintenancePanel currentMaintenance = maintenance;
                if (currentMaintenance != null) {
                    failure = attemptCleanup(failure, currentMaintenance::close);
                }
                @Nullable InstanceGameSettingsPanel currentGameSettings = gameSettings;
                if (currentGameSettings != null) {
                    failure = attemptCleanup(failure, currentGameSettings::close);
                }
                @Nullable InstanceLifecyclePanel currentLifecycle = lifecycle;
                if (currentLifecycle != null) {
                    failure = attemptCleanup(failure, currentLifecycle::close);
                }
                failure = attemptCleanup(failure, overview::close);
                returnButton.setEnabled(false);
                tabs.removeChangeListener(lazyTabListener);
                tabs.removeAll();
                removeAll();
                cleanupFailure.set(failure);
            });
        } catch (RuntimeException | Error componentFailure) {
            cleanupFailure.set(combineFailure(cleanupFailure.get(), componentFailure));
        }
        rethrowFailure(cleanupFailure.get());
    }

    /// Builds the top-level return toolbar and named tool tabs.
    ///
    /// @param managementStrings localized outer management text
    /// @param modStrings localized installed-Mod content text
    /// @param resourcePackStrings localized resource-pack content text
    /// @param schematicStrings localized schematic-browser text
    /// @param returnCommand coordinator command returning to the instance list
    private void configureComponents(
            SchematicInstanceManagementStrings managementStrings,
            ModCatalogStrings modStrings,
            ResourcePackCatalogStrings resourcePackStrings,
            SchematicBrowserStrings schematicStrings,
            Runnable returnCommand) {
        JPanel toolbar = new JPanel(new MigLayout("insets 0, fillx", "[][grow,fill]", "[40!]"));
        toolbar.setOpaque(false);
        returnButton.setName("instanceManagementReturn");
        returnButton.setText(null);
        returnButton.setToolTipText(managementStrings.returnTooltip());
        returnButton.setIcon(new FlatSVGIcon("assets/swing/icons/arrow-back.svg", 18, 18));
        returnButton.getAccessibleContext().setAccessibleName(managementStrings.returnTooltip());
        returnButton.addActionListener(event -> {
            if (!closed.get()) {
                returnCommand.run();
            }
        });
        toolbar.add(returnButton, "h 40!");

        JLabel title = new JLabel(managementStrings.returnAction());
        title.setName("instanceManagementTitle");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22.0F));
        toolbar.add(title, "growx");
        add(toolbar, "growx");

        tabs.setName("instanceManagementTabs");
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        tabs.addTab(overview.title(), overview);
        @Nullable InstanceLifecyclePanel currentLifecycle = lifecycle;
        if (currentLifecycle != null) {
            tabs.addTab(currentLifecycle.title(), currentLifecycle);
        }
        @Nullable InstanceGameSettingsPanel currentGameSettings = gameSettings;
        if (currentGameSettings != null) {
            tabs.addTab(i18n("settings.game"), currentGameSettings);
        }
        @Nullable InstanceInstallerPanel currentInstallers = installers;
        if (currentInstallers != null) {
            tabs.addTab(i18n("settings.tabs.installers"), currentInstallers);
        }
        @Nullable InstanceMaintenancePanel currentMaintenance = maintenance;
        if (currentMaintenance != null) {
            tabs.addTab(currentMaintenance.title(), currentMaintenance);
        }
        tabs.addTab(modStrings.title(), mods);
        tabs.addTab(resourcePackStrings.pageTitle(), resourcePacks);
        tabs.addTab(worlds.title(), worlds);
        tabs.addTab(dataPacks.title(), dataPacks);
        tabs.addTab(backups.title(), backups);
        @Nullable ModpackExportPanel currentModpackExport = modpackExport;
        if (currentModpackExport != null) {
            tabs.addTab(currentModpackExport.title(), currentModpackExport);
        }
        tabs.addTab(addonUpdates.title(), addonUpdates);
        tabs.addTab(schematicStrings.pageTitle(), schematics);
        tabs.addChangeListener(lazyTabListener);
        activateSelectedLazyTab();
        tabs.getAccessibleContext().setAccessibleName(managementStrings.returnAction());
        add(tabs, "grow");
    }

    /// Starts selected local-catalog work only after the corresponding tab becomes visible.
    ///
    /// The installed add-on update page intentionally is excluded because its explicit button is the
    /// only operation allowed to contact a remote catalogue.
    private void activateSelectedLazyTab() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get()) {
            return;
        }
        if (tabs.getSelectedComponent() == worlds) {
            worlds.activate();
        }
        if (tabs.getSelectedComponent() == dataPacks) {
            dataPacks.activate();
        }
        if (tabs.getSelectedComponent() == backups) {
            backups.activate();
        }
        @Nullable InstanceInstallerPanel currentInstallers = installers;
        if (tabs.getSelectedComponent() == currentInstallers && currentInstallers != null) {
            currentInstallers.activate();
        }
        @Nullable InstanceMaintenancePanel currentMaintenance = maintenance;
        if (tabs.getSelectedComponent() == currentMaintenance && currentMaintenance != null) {
            currentMaintenance.activate();
        }
        @Nullable ModpackExportPanel currentModpackExport = modpackExport;
        if (tabs.getSelectedComponent() == currentModpackExport && currentModpackExport != null) {
            currentModpackExport.activate();
        }
    }

    /// Attempts one cleanup action and retains the first failure identity.
    ///
    /// @param previous first cleanup failure, or null
    /// @param cleanup cleanup action
    /// @return first failure with later failures suppressed, or null
    private static @Nullable Throwable attemptCleanup(
            @Nullable Throwable previous,
            Runnable cleanup) {
        try {
            cleanup.run();
            return previous;
        } catch (RuntimeException | Error failure) {
            return combineFailure(previous, failure);
        }
    }

    /// Appends one cleanup failure without replacing an earlier failure.
    ///
    /// @param previous first cleanup failure, or null
    /// @param current later cleanup failure
    /// @return first failure with later failures suppressed
    private static Throwable combineFailure(@Nullable Throwable previous, Throwable current) {
        Objects.requireNonNull(current, "current");
        if (previous == null) {
            return current;
        }
        if (previous != current) {
            previous.addSuppressed(current);
        }
        return previous;
    }

    /// Rethrows an accumulated unchecked cleanup failure.
    ///
    /// @param failure cleanup failure, or null
    private static void rethrowFailure(@Nullable Throwable failure) {
        if (failure == null) {
            return;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Unexpected checked instance-management failure", failure);
    }

    /// Validates one required non-blank text value.
    ///
    /// @param value source value
    /// @param name parameter name
    /// @return validated value
    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}

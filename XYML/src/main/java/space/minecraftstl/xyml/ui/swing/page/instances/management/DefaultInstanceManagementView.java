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

import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.GameRepository;
import space.minecraftstl.xyml.game.XYMLGameRepository;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;
import space.minecraftstl.xyml.ui.swing.SwingAnimator;
import space.minecraftstl.xyml.ui.swing.page.home.HomeModel;
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

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.Dimension;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Hosts the launcher's persistent instance workspace with a compact summary and grouped direct navigation.
///
/// The overview is created for the initial page. Every other management surface is constructed only on first visit,
/// while repository and filesystem work continues to use the supplied executor. The view owns every created child
/// lifecycle and borrows the application-owned home model only for its summary subscription.
@NotNullByDefault
public final class DefaultInstanceManagementView extends JPanel implements InstanceManagementView {
    /// Stable repository instance identifier represented by this view.
    private final String instanceId;

    /// Persistent identity, version, icon, launch-state, and common-actions header.
    private final InstanceWorkspaceSummaryPanel summary;

    /// Scrollable whole-row navigation containing only pages supported by the repository.
    private final InstanceManagementNavigationPanel navigation;

    /// Transparent card deck constructing management pages on their first visit.
    private final InstanceManagementPageDeck pageDeck;

    /// Prevents repeated child and component cleanup.
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Creates the complete production workspace with world quick play and instance maintenance commands.
    ///
    /// @param homeModel borrowed launcher selection and launch-state model
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
    /// @param returnCommand shell command opening the instance-list side page
    /// @param taskProgressStrings localized task-progress labels for long-running instance operations
    /// @param animator optional shared motion-aware progress animator
    /// @param progressAnimationDuration non-negative progress animation duration for instance operations
    /// @param worldQuickPlayActions non-blocking launch and script commands bound to this instance's worlds
    /// @param maintenanceLaunchActions test-launch and script commands, or null when the caller cannot provide them
    public DefaultInstanceManagementView(
            HomeModel homeModel,
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
                "[]10[grow,fill]"));
        EdtDispatcher.requireEventDispatchThread();
        HomeModel requiredHomeModel = Objects.requireNonNull(homeModel, "homeModel");
        GameRepository requiredRepository = Objects.requireNonNull(repository, "repository");
        SchematicDirectoryResolver requiredSchematicResolver =
                Objects.requireNonNull(schematicDirectoryResolver, "schematicDirectoryResolver");
        this.instanceId = requireNonBlank(instanceId, "instanceId");
        Executor requiredExecutor = Objects.requireNonNull(executor, "executor");
        SchematicInstanceManagementStrings requiredManagementStrings =
                Objects.requireNonNull(managementStrings, "managementStrings");
        SchematicBrowserStrings requiredSchematicStrings =
                Objects.requireNonNull(schematicStrings, "schematicStrings");
        SchematicBrowserInteractions requiredSchematicInteractions =
                Objects.requireNonNull(schematicInteractions, "schematicInteractions");
        ModCatalogStrings requiredModStrings = Objects.requireNonNull(modStrings, "modStrings");
        ModCatalogStatusStrings requiredModStatusStrings =
                Objects.requireNonNull(modStatusStrings, "modStatusStrings");
        ModCatalogActionStrings requiredModActionStrings =
                Objects.requireNonNull(modActionStrings, "modActionStrings");
        ModCatalogInteractions requiredModInteractions =
                Objects.requireNonNull(modInteractions, "modInteractions");
        ResourcePackCatalogStrings requiredResourcePackStrings =
                Objects.requireNonNull(resourcePackStrings, "resourcePackStrings");
        ResourcePackCatalogStatusStrings requiredResourcePackStatusStrings =
                Objects.requireNonNull(resourcePackStatusStrings, "resourcePackStatusStrings");
        ResourcePackCatalogActionStrings requiredResourcePackActionStrings =
                Objects.requireNonNull(resourcePackActionStrings, "resourcePackActionStrings");
        ResourcePackCatalogInteractions requiredResourcePackInteractions =
                Objects.requireNonNull(resourcePackInteractions, "resourcePackInteractions");
        Runnable requiredReturnCommand = Objects.requireNonNull(returnCommand, "returnCommand");
        TaskProgressStrings requiredTaskProgressStrings =
                Objects.requireNonNull(taskProgressStrings, "taskProgressStrings");
        Duration requiredAnimationDuration =
                Objects.requireNonNull(progressAnimationDuration, "progressAnimationDuration");
        WorldQuickPlayActions requiredWorldQuickPlayActions =
                Objects.requireNonNull(worldQuickPlayActions, "worldQuickPlayActions");
        if (requiredAnimationDuration.isNegative()) {
            throw new IllegalArgumentException("progressAnimationDuration must not be negative");
        }
        setOpaque(false);

        AtomicReference<@Nullable InstanceOverviewPanel> overviewReference = new AtomicReference<>();
        @Nullable InstanceWorkspaceSummaryPanel createdSummary = null;
        @Nullable InstanceOverviewPanel createdOverview = null;
        @Nullable InstanceManagementPageDeck createdPageDeck = null;
        try {
            createdSummary = new InstanceWorkspaceSummaryPanel(
                    requiredHomeModel,
                    this.instanceId,
                    () -> requireOverview(overviewReference).refresh(),
                    () -> requireOverview(overviewReference).openInstanceDirectory(),
                    invoker -> requireOverview(overviewReference).showDirectoryMenuAt(invoker));
            InstanceWorkspaceSummaryPanel summarySink = createdSummary;
            createdOverview = new InstanceOverviewPanel(
                    requiredRepository,
                    this.instanceId,
                    requiredExecutor,
                    summarySink::applyOverviewSummary);
            overviewReference.set(createdOverview);
            createdPageDeck = new InstanceManagementPageDeck(createPageFactories(
                    requiredRepository,
                    this.instanceId,
                    requiredExecutor,
                    new SchematicPageDependencies(
                            requiredSchematicResolver,
                            requiredManagementStrings,
                            requiredSchematicStrings,
                            requiredSchematicInteractions),
                    new ModPageDependencies(
                            requiredModStrings,
                            requiredModStatusStrings,
                            requiredModActionStrings,
                            requiredModInteractions),
                    new ResourcePackPageDependencies(
                            requiredResourcePackStrings,
                            requiredResourcePackStatusStrings,
                            requiredResourcePackActionStrings,
                            requiredResourcePackInteractions),
                    new OperationPageDependencies(
                            requiredReturnCommand,
                            requiredTaskProgressStrings,
                            animator,
                            requiredAnimationDuration,
                            requiredWorldQuickPlayActions,
                            maintenanceLaunchActions),
                    createdOverview));
            InstanceManagementNavigationPanel createdNavigation = new InstanceManagementNavigationPanel(
                    createdPageDeck.availablePages(),
                    InstanceManagementPageId.OVERVIEW,
                    this::selectPage);
            summary = createdSummary;
            pageDeck = createdPageDeck;
            navigation = createdNavigation;
            configureComponents();
            pageDeck.showPage(InstanceManagementPageId.OVERVIEW);
        } catch (RuntimeException | Error constructionFailure) {
            @Nullable Throwable cleanupFailure = null;
            if (createdPageDeck != null) {
                cleanupFailure = attemptCleanup(cleanupFailure, createdPageDeck::close);
            }
            if (createdOverview != null) {
                cleanupFailure = attemptCleanup(cleanupFailure, createdOverview::close);
            }
            if (createdSummary != null) {
                cleanupFailure = attemptCleanup(cleanupFailure, createdSummary::close);
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

    /// Closes the summary subscription and every lazily created page exactly once.
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        AtomicReference<@Nullable Throwable> cleanupFailure = new AtomicReference<>();
        try {
            EdtDispatcher.executeAndWait(() -> {
                @Nullable Throwable failure = null;
                failure = attemptCleanup(failure, pageDeck::close);
                failure = attemptCleanup(failure, summary::close);
                removeAll();
                cleanupFailure.set(failure);
            });
        } catch (RuntimeException | Error componentFailure) {
            cleanupFailure.set(combineFailure(cleanupFailure.get(), componentFailure));
        }
        rethrowFailure(cleanupFailure.get());
    }

    /// Builds the persistent summary above grouped instance navigation and its transparent page deck.
    private void configureComponents() {
        setName("instanceManagementWorkspace");
        setMinimumSize(new Dimension(0, 0));
        getAccessibleContext().setAccessibleName(i18n("instance.manage.manage.title", instanceId));
        add(summary, "growx");

        JPanel workspaceBody = new JPanel(new MigLayout(
                "insets 0, fill",
                "[190!,shrink 150]12[grow,fill]",
                "[grow,fill]"));
        workspaceBody.setName("instanceManagementWorkspaceBody");
        workspaceBody.setOpaque(false);
        workspaceBody.setMinimumSize(new Dimension(0, 0));
        workspaceBody.add(navigation, "grow, wmin 0, hmin 0");
        workspaceBody.add(pageDeck, "grow, wmin 0, hmin 0");
        add(workspaceBody, "grow, wmin 0, hmin 0");
    }

    /// Displays one user-selected page and restores navigation selection if activation fails.
    ///
    /// @param page requested supported destination
    private void selectPage(InstanceManagementPageId page) {
        EdtDispatcher.requireEventDispatchThread();
        if (closed.get()) {
            return;
        }
        @Nullable InstanceManagementPageId previousPage = pageDeck.selectedPage();
        try {
            pageDeck.showPage(Objects.requireNonNull(page, "page"));
        } catch (RuntimeException | Error failure) {
            if (previousPage != null) {
                navigation.setSelectedPage(previousPage);
            }
            throw failure;
        }
    }

    /// Creates lazy factories for every management feature supported by the current repository.
    ///
    /// @param repository repository containing the managed instance
    /// @param instanceId stable repository instance identifier
    /// @param executor caller-owned executor for filesystem and metadata work
    /// @param schematicDependencies schematic resolver, strings, and interactions
    /// @param modDependencies installed-Mod strings and interactions
    /// @param resourcePackDependencies resource-pack strings and interactions
    /// @param operationDependencies lifecycle, progress, animation, world, and maintenance actions
    /// @param overview eagerly created default overview page
    /// @return destination factories without eagerly constructing optional pages
    private static EnumMap<InstanceManagementPageId, InstanceManagementPageDeck.PageFactory> createPageFactories(
            GameRepository repository,
            String instanceId,
            Executor executor,
            SchematicPageDependencies schematicDependencies,
            ModPageDependencies modDependencies,
            ResourcePackPageDependencies resourcePackDependencies,
            OperationPageDependencies operationDependencies,
            InstanceOverviewPanel overview) {
        EnumMap<InstanceManagementPageId, InstanceManagementPageDeck.PageFactory> factories =
                new EnumMap<>(InstanceManagementPageId.class);
        factories.put(
                InstanceManagementPageId.OVERVIEW,
                () -> InstanceManagementPage.passive(overview, overview::close));
        factories.put(InstanceManagementPageId.MODS, () -> {
            ModCatalogPanel panel = new ModCatalogPanel(
                    new DefaultModCatalogModel(
                            repository,
                            instanceId,
                            executor,
                            modDependencies.statusStrings()),
                    modDependencies.strings(),
                    modDependencies.actionStrings(),
                    modDependencies.interactions());
            return InstanceManagementPage.passive(panel, panel::close);
        });
        factories.put(InstanceManagementPageId.RESOURCE_PACKS, () -> {
            ResourcePackCatalogPanel panel = new ResourcePackCatalogPanel(
                    new DefaultResourcePackCatalogModel(
                            repository,
                            instanceId,
                            executor,
                            resourcePackDependencies.statusStrings()),
                    resourcePackDependencies.strings(),
                    resourcePackDependencies.actionStrings(),
                    resourcePackDependencies.interactions(),
                    repository.getResourcePackDirectory(instanceId));
            return InstanceManagementPage.passive(panel, panel::close);
        });
        factories.put(InstanceManagementPageId.WORLDS, () -> {
            WorldCatalogPanel panel = new WorldCatalogPanel(
                    repository,
                    instanceId,
                    executor,
                    operationDependencies.worldQuickPlayActions());
            return new InstanceManagementPage(panel, panel::activate, panel::close);
        });
        factories.put(InstanceManagementPageId.DATA_PACKS, () -> {
            DataPackManagementPanel panel = new DataPackManagementPanel(repository, instanceId, executor);
            return new InstanceManagementPage(panel, panel::activate, panel::close);
        });
        factories.put(InstanceManagementPageId.SCHEMATICS, () -> {
            SchematicInstanceManagementView panel = new SchematicInstanceManagementView(
                    instanceId,
                    schematicDependencies.directoryResolver(),
                    executor,
                    schematicDependencies.managementStrings(),
                    schematicDependencies.browserStrings(),
                    schematicDependencies.interactions(),
                    () -> { },
                    false);
            return InstanceManagementPage.passive(panel, panel::close);
        });
        factories.put(InstanceManagementPageId.BACKUPS, () -> {
            WorldBackupsPanel panel = new WorldBackupsPanel(repository, instanceId, executor);
            return new InstanceManagementPage(panel, panel::activate, panel::close);
        });
        factories.put(InstanceManagementPageId.FILE_UPDATE_CHECK, () -> {
            AddonUpdatesPanel panel = new AddonUpdatesPanel(
                    repository,
                    instanceId,
                    executor,
                    operationDependencies.taskProgressStrings(),
                    operationDependencies.animator(),
                    operationDependencies.progressAnimationDuration());
            return InstanceManagementPage.passive(panel, panel::close);
        });

        if (repository instanceof XYMLGameRepository xymlRepository) {
            factories.put(InstanceManagementPageId.GAME_SETTINGS, () -> {
                InstanceGameSettingsPanel panel = new InstanceGameSettingsPanel(
                        xymlRepository,
                        instanceId,
                        executor);
                return InstanceManagementPage.passive(panel, panel::close);
            });
            factories.put(InstanceManagementPageId.AUTOMATIC_INSTALL, () -> {
                InstanceInstallerPanel panel = new InstanceInstallerPanel(
                        xymlRepository,
                        instanceId,
                        operationDependencies.taskProgressStrings(),
                        operationDependencies.animator(),
                        operationDependencies.progressAnimationDuration());
                return new InstanceManagementPage(panel, panel::activate, panel::close);
            });
            factories.put(InstanceManagementPageId.MODPACK_EXPORT, () -> {
                ModpackExportPanel panel = new ModpackExportPanel(
                        xymlRepository,
                        instanceId,
                        executor,
                        operationDependencies.taskProgressStrings(),
                        operationDependencies.animator(),
                        operationDependencies.progressAnimationDuration());
                return new InstanceManagementPage(panel, panel::activate, panel::close);
            });
            factories.put(InstanceManagementPageId.INSTANCE_OPERATIONS, () -> {
                InstanceLifecyclePanel panel = new InstanceLifecyclePanel(
                        xymlRepository,
                        instanceId,
                        executor,
                        operationDependencies.returnCommand());
                return InstanceManagementPage.passive(panel, panel::close);
            });
            @Nullable InstanceMaintenanceLaunchActions availableMaintenanceActions =
                    operationDependencies.maintenanceLaunchActions();
            if (availableMaintenanceActions != null) {
                factories.put(InstanceManagementPageId.MAINTENANCE_TOOLS, () -> {
                    InstanceMaintenancePanel panel = new InstanceMaintenancePanel(
                            xymlRepository,
                            instanceId,
                            availableMaintenanceActions,
                            operationDependencies.taskProgressStrings(),
                            operationDependencies.animator(),
                            operationDependencies.progressAnimationDuration());
                    return new InstanceManagementPage(panel, panel::activate, panel::close);
                });
            }
        }
        return factories;
    }

    /// Immutable schematic-page construction dependencies.
    ///
    /// @param directoryResolver managed-instance schematic root resolver
    /// @param managementStrings localized host text
    /// @param browserStrings localized schematic-browser text
    /// @param interactions schematic dialogs and desktop interactions
    @NotNullByDefault
    private record SchematicPageDependencies(
            SchematicDirectoryResolver directoryResolver,
            SchematicInstanceManagementStrings managementStrings,
            SchematicBrowserStrings browserStrings,
            SchematicBrowserInteractions interactions) {
        /// Validates schematic-page dependencies.
        private SchematicPageDependencies {
            Objects.requireNonNull(directoryResolver, "directoryResolver");
            Objects.requireNonNull(managementStrings, "managementStrings");
            Objects.requireNonNull(browserStrings, "browserStrings");
            Objects.requireNonNull(interactions, "interactions");
        }
    }

    /// Immutable installed-Mod page construction dependencies.
    ///
    /// @param strings localized content text
    /// @param statusStrings localized lifecycle text
    /// @param actionStrings localized action text
    /// @param interactions installed-Mod dialogs and desktop interactions
    @NotNullByDefault
    private record ModPageDependencies(
            ModCatalogStrings strings,
            ModCatalogStatusStrings statusStrings,
            ModCatalogActionStrings actionStrings,
            ModCatalogInteractions interactions) {
        /// Validates installed-Mod page dependencies.
        private ModPageDependencies {
            Objects.requireNonNull(strings, "strings");
            Objects.requireNonNull(statusStrings, "statusStrings");
            Objects.requireNonNull(actionStrings, "actionStrings");
            Objects.requireNonNull(interactions, "interactions");
        }
    }

    /// Immutable resource-pack page construction dependencies.
    ///
    /// @param strings localized content text
    /// @param statusStrings localized lifecycle text
    /// @param actionStrings localized action text
    /// @param interactions resource-pack dialogs and desktop interactions
    @NotNullByDefault
    private record ResourcePackPageDependencies(
            ResourcePackCatalogStrings strings,
            ResourcePackCatalogStatusStrings statusStrings,
            ResourcePackCatalogActionStrings actionStrings,
            ResourcePackCatalogInteractions interactions) {
        /// Validates resource-pack page dependencies.
        private ResourcePackPageDependencies {
            Objects.requireNonNull(strings, "strings");
            Objects.requireNonNull(statusStrings, "statusStrings");
            Objects.requireNonNull(actionStrings, "actionStrings");
            Objects.requireNonNull(interactions, "interactions");
        }
    }

    /// Immutable lifecycle, progress, animation, world, and maintenance construction dependencies.
    ///
    /// @param returnCommand command opening the instance list after destructive lifecycle work
    /// @param taskProgressStrings localized long-running task labels
    /// @param animator optional shared motion-aware animator
    /// @param progressAnimationDuration non-negative task animation duration
    /// @param worldQuickPlayActions world launch and script commands
    /// @param maintenanceLaunchActions optional maintenance launch and script commands
    @NotNullByDefault
    private record OperationPageDependencies(
            Runnable returnCommand,
            TaskProgressStrings taskProgressStrings,
            @Nullable SwingAnimator animator,
            Duration progressAnimationDuration,
            WorldQuickPlayActions worldQuickPlayActions,
            @Nullable InstanceMaintenanceLaunchActions maintenanceLaunchActions) {
        /// Validates required operation-page dependencies.
        private OperationPageDependencies {
            Objects.requireNonNull(returnCommand, "returnCommand");
            Objects.requireNonNull(taskProgressStrings, "taskProgressStrings");
            Objects.requireNonNull(progressAnimationDuration, "progressAnimationDuration");
            Objects.requireNonNull(worldQuickPlayActions, "worldQuickPlayActions");
            if (progressAnimationDuration.isNegative()) {
                throw new IllegalArgumentException("progressAnimationDuration must not be negative");
            }
        }
    }

    /// Returns the overview after construction has installed it for shared summary commands.
    ///
    /// @param reference construction-safe overview reference
    /// @return initialized overview
    private static InstanceOverviewPanel requireOverview(
            AtomicReference<@Nullable InstanceOverviewPanel> reference) {
        @Nullable InstanceOverviewPanel current = Objects.requireNonNull(reference, "reference").get();
        if (current == null) {
            throw new IllegalStateException("Instance overview is not initialized");
        }
        return current;
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

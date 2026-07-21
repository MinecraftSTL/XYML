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
package space.minecraftstl.xyml.ui.swing.application;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.Metadata;
import space.minecraftstl.xyml.ui.swing.page.accounts.AccountsStrings;
import space.minecraftstl.xyml.ui.swing.page.downloads.GameInstallStrings;
import space.minecraftstl.xyml.ui.swing.page.downloads.GameVersionCatalogStatusStrings;
import space.minecraftstl.xyml.ui.swing.page.downloads.GameVersionCatalogStrings;
import space.minecraftstl.xyml.ui.swing.page.home.HomeStatusStrings;
import space.minecraftstl.xyml.ui.swing.page.home.HomeStrings;
import space.minecraftstl.xyml.ui.swing.page.instances.InstancesStrings;
import space.minecraftstl.xyml.ui.swing.page.instances.RepositoryInstancesStatusStrings;
import space.minecraftstl.xyml.ui.swing.page.instances.management.SchematicInstanceManagementStrings;
import space.minecraftstl.xyml.ui.swing.page.mods.ModCatalogActionStrings;
import space.minecraftstl.xyml.ui.swing.page.mods.ModCatalogStatusStrings;
import space.minecraftstl.xyml.ui.swing.page.mods.ModCatalogStrings;
import space.minecraftstl.xyml.ui.swing.page.resourcepacks.ResourcePackCatalogActionStrings;
import space.minecraftstl.xyml.ui.swing.page.resourcepacks.ResourcePackCatalogStatusStrings;
import space.minecraftstl.xyml.ui.swing.page.resourcepacks.ResourcePackCatalogStrings;
import space.minecraftstl.xyml.ui.swing.page.schematics.SchematicBrowserActionStrings;
import space.minecraftstl.xyml.ui.swing.page.schematics.SchematicBrowserStrings;
import space.minecraftstl.xyml.ui.swing.page.schematics.SchematicMetadataStrings;
import space.minecraftstl.xyml.ui.swing.page.settings.AppearanceBackgroundStrings;
import space.minecraftstl.xyml.ui.swing.page.settings.AppearanceSettingsStrings;
import space.minecraftstl.xyml.ui.swing.shell.ShellPageId;
import space.minecraftstl.xyml.ui.swing.shell.ShellPagePresentation;
import space.minecraftstl.xyml.ui.swing.shell.ShellPagePresentations;
import space.minecraftstl.xyml.ui.swing.task.TaskProgressStrings;

import java.awt.event.KeyEvent;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Objects;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Creates complete production Swing text from the launcher's active resource-bundle locale.
///
/// Every timing policy remains startup-owned and is passed through without substitution. Shared
/// launcher keys are reused where their semantics match; Swing-specific states and tooltips use
/// dedicated keys that fall back through the normal resource-bundle chain.
@NotNullByDefault
public final class SwingApplicationPresentationFactory {
    /// Prevents construction of this resource-backed factory.
    private SwingApplicationPresentationFactory() {
    }

    /// Creates production presentation using the current product title.
    ///
    /// @param pageTransitionDuration explicit non-negative shell transition duration
    /// @param taskProgressAnimationDuration explicit non-negative task animation duration
    /// @return complete localized presentation
    public static SwingApplicationPresentation create(
            Duration pageTransitionDuration,
            Duration taskProgressAnimationDuration) {
        return create(Metadata.TITLE, pageTransitionDuration, taskProgressAnimationDuration);
    }

    /// Creates production presentation using an explicit window title.
    ///
    /// @param windowTitle non-blank native window title
    /// @param pageTransitionDuration explicit non-negative shell transition duration
    /// @param taskProgressAnimationDuration explicit non-negative task animation duration
    /// @return complete localized presentation
    public static SwingApplicationPresentation create(
            String windowTitle,
            Duration pageTransitionDuration,
            Duration taskProgressAnimationDuration) {
        Objects.requireNonNull(windowTitle, "windowTitle");
        Duration validatedPageTransition = requireNonNegative(
                pageTransitionDuration,
                "pageTransitionDuration");
        Duration validatedTaskAnimation = requireNonNegative(
                taskProgressAnimationDuration,
                "taskProgressAnimationDuration");
        return new SwingApplicationPresentation(
                windowTitle,
                createShellPages(),
                createHomeStrings(),
                createHomeStatusStrings(),
                createInstancesStrings(),
                createInstancesStatusStrings(),
                createManagementStrings(),
                createSchematicBrowserStrings(),
                createModCatalogStrings(),
                createModCatalogStatusStrings(),
                createModCatalogActionStrings(),
                createResourcePackStrings(),
                createResourcePackStatusStrings(),
                createResourcePackActionStrings(),
                createGameVersionStrings(),
                createGameVersionStatusStrings(),
                createGameInstallStrings(),
                createAccountsStrings(),
                createAppearanceStrings(),
                validatedPageTransition,
                createTaskProgressStrings(),
                validatedTaskAnimation);
    }

    /// Creates localized labels with stable platform mnemonic key codes for every destination.
    ///
    /// @return complete shell navigation presentation
    private static ShellPagePresentations createShellPages() {
        EnumMap<ShellPageId, ShellPagePresentation> pages = new EnumMap<>(ShellPageId.class);
        pages.put(ShellPageId.INSTANCES, new ShellPagePresentation(i18n("instance.manage"), KeyEvent.VK_I));
        pages.put(ShellPageId.DOWNLOADS, new ShellPagePresentation(i18n("download"), KeyEvent.VK_D));
        pages.put(ShellPageId.ACCOUNTS, new ShellPagePresentation(i18n("account"), KeyEvent.VK_A));
        pages.put(ShellPageId.SETTINGS, new ShellPagePresentation(i18n("settings"), KeyEvent.VK_S));
        return new ShellPagePresentations(pages);
    }

    /// Creates localized home-page controls.
    ///
    /// @return home-page text
    private static HomeStrings createHomeStrings() {
        return new HomeStrings(
                i18n("swing.shell.home"),
                i18n("account"),
                i18n("account.missing"),
                i18n("instance"),
                i18n("instance.empty"),
                i18n("install"),
                i18n("instance.launch_script"),
                i18n("instance.launch"),
                i18n("swing.home.launching"),
                i18n("swing.home.back_to_selections"));
    }

    /// Creates localized home readiness states.
    ///
    /// @return home status text
    private static HomeStatusStrings createHomeStatusStrings() {
        return new HomeStatusStrings(
                i18n("swing.home.status.ready"),
                i18n("account.choose"),
                i18n("instance.empty"),
                i18n("instance.launch_script"));
    }

    /// Creates localized installed-instance controls.
    ///
    /// @return instance-page text
    private static InstancesStrings createInstancesStrings() {
        return new InstancesStrings(
                i18n("instance.manage"),
                i18n("search"),
                i18n("button.refresh"),
                i18n("swing.common.refreshing"),
                i18n("install"),
                i18n("instance.manage.manage"),
                i18n("instance.empty"),
                i18n("swing.instances.search.no_results"));
    }

    /// Creates localized installed-instance repository states.
    ///
    /// @return instance model status text
    private static RepositoryInstancesStatusStrings createInstancesStatusStrings() {
        return new RepositoryInstancesStatusStrings(
                i18n("swing.instances.status.loading"),
                i18n("swing.instances.status.ready"),
                i18n("swing.common.refreshing"),
                i18n("swing.instances.status.failed"),
                i18n("message.unknown"));
    }

    /// Creates localized outer instance-management controls and states.
    ///
    /// @return instance-management text
    private static SchematicInstanceManagementStrings createManagementStrings() {
        return new SchematicInstanceManagementStrings(
                i18n("instance.manage"),
                i18n("swing.management.return_tooltip"),
                i18n("swing.management.loading"),
                i18n("message.failed"),
                i18n("button.retry"));
    }

    /// Creates localized schematic-browser controls, states, and metadata labels.
    ///
    /// @return schematic-browser text
    private static SchematicBrowserStrings createSchematicBrowserStrings() {
        return new SchematicBrowserStrings(
                i18n("schematics.manage"),
                i18n("swing.schematics.return"),
                i18n("swing.schematics.return_tooltip"),
                i18n("button.refresh"),
                i18n("swing.common.refreshing"),
                i18n("swing.schematics.refresh_tooltip"),
                i18n("swing.schematics.open"),
                i18n("swing.schematics.open_tooltip"),
                i18n("swing.schematics.status.idle"),
                i18n("swing.schematics.status.loading"),
                i18n("swing.schematics.status.empty"),
                i18n("message.failed"),
                i18n("button.retry"),
                i18n("swing.schematics.details"),
                i18n("swing.schematics.no_selection"),
                i18n("swing.schematics.directory"),
                i18n("swing.schematics.unreadable"),
                i18n("swing.schematics.directory_prefix"),
                createSchematicMetadataStrings(),
                createSchematicBrowserActionStrings());
    }

    /// Creates localized schematic file-operation controls, prompts, and states.
    ///
    /// @return schematic file-operation text
    private static SchematicBrowserActionStrings createSchematicBrowserActionStrings() {
        return new SchematicBrowserActionStrings(
                i18n("schematics.add"),
                i18n("swing.schematics.import_tooltip"),
                i18n("schematics.add.title"),
                i18n("extension.schematic"),
                i18n("schematics.create_directory"),
                i18n("swing.schematics.create_directory_tooltip"),
                i18n("schematics.create_directory.prompt"),
                i18n("button.delete"),
                i18n("swing.schematics.delete_tooltip"),
                i18n("swing.schematics.delete_confirm"),
                i18n("button.reveal_dir"),
                i18n("reveal.in_file_manager"),
                i18n("swing.schematics.status.writing"),
                i18n("swing.schematics.status.write_failed"),
                i18n("message.failed"),
                i18n("swing.schematics.reveal_failed"));
    }

    /// Creates localized schematic metadata labels and exact formatter patterns.
    ///
    /// @return schematic metadata text
    private static SchematicMetadataStrings createSchematicMetadataStrings() {
        return new SchematicMetadataStrings(
                i18n("swing.schematics.metadata.path"),
                i18n("schematics.info.name"),
                i18n("schematics.info.schematic_author"),
                i18n("schematics.info.description"),
                i18n("schematics.info.time_created"),
                i18n("schematics.info.time_modified"),
                i18n("schematics.info.region_count"),
                i18n("schematics.info.total_volume"),
                i18n("schematics.info.total_blocks"),
                i18n("schematics.info.enclosing_size"),
                i18n("schematics.info.version"),
                i18n("swing.schematics.metadata.data_version"),
                i18n("swing.schematics.metadata.preview"),
                i18n("message.unknown"),
                i18n("swing.schematics.metadata.enclosing_size_format"),
                i18n("swing.schematics.metadata.preview_dimensions_format"),
                i18n("swing.schematics.metadata.preview_pixel_count_format"),
                i18n("swing.schematics.metadata.preview_unavailable"));
    }

    /// Creates localized installed-Mod content text.
    ///
    /// @return installed-Mod catalog text
    private static ModCatalogStrings createModCatalogStrings() {
        return new ModCatalogStrings(
                i18n("mods.manage"),
                i18n("search"),
                i18n("swing.mods.filter"),
                i18n("swing.mods.filter.all"),
                i18n("swing.mods.filter.enabled"),
                i18n("swing.mods.filter.disabled"),
                i18n("swing.mods.no_selection"),
                i18n("swing.mods.id"),
                i18n("swing.mods.version"),
                i18n("mods.game.version"),
                i18n("swing.mods.loader"),
                i18n("swing.mods.authors"),
                i18n("file"),
                i18n("swing.mods.description"),
                i18n("swing.mods.enabled"));
    }

    /// Creates localized installed-Mod lifecycle text.
    ///
    /// @return installed-Mod status text
    private static ModCatalogStatusStrings createModCatalogStatusStrings() {
        return new ModCatalogStatusStrings(
                i18n("swing.mods.status.loading"),
                i18n("swing.mods.status.empty"),
                i18n("swing.mods.status.ready"),
                i18n("swing.mods.status.failed"),
                i18n("swing.mods.status.importing"),
                i18n("swing.mods.status.enabling"),
                i18n("swing.mods.status.disabling"),
                i18n("swing.mods.status.deleting"),
                i18n("swing.mods.status.write_failed"));
    }

    /// Creates localized installed-Mod action text.
    ///
    /// @return installed-Mod command and confirmation text
    private static ModCatalogActionStrings createModCatalogActionStrings() {
        return new ModCatalogActionStrings(
                i18n("button.refresh"),
                i18n("swing.mods.refresh_tooltip"),
                i18n("mods.add"),
                i18n("swing.mods.import_tooltip"),
                i18n("swing.mods.open_directory"),
                i18n("swing.mods.open_directory_tooltip"),
                i18n("button.reveal_dir"),
                i18n("swing.mods.reveal_tooltip"),
                i18n("button.delete"),
                i18n("swing.mods.delete_tooltip"),
                i18n("mods.add.title"),
                i18n("extension.mod"),
                i18n("swing.mods.delete_confirm"),
                i18n("message.failed"));
    }

    /// Creates localized installed-resource-pack content text.
    ///
    /// @return resource-pack catalog text
    private static ResourcePackCatalogStrings createResourcePackStrings() {
        return new ResourcePackCatalogStrings(
                i18n("resourcepack.manage"),
                i18n("button.refresh"),
                i18n("swing.common.refreshing"),
                i18n("swing.resourcepacks.refresh_tooltip"),
                i18n("button.retry"),
                i18n("swing.resourcepacks.retry_tooltip"),
                i18n("swing.resourcepacks.status.idle"),
                i18n("swing.resourcepacks.status.loading"),
                i18n("swing.resourcepacks.status.empty"),
                i18n("message.failed"),
                i18n("swing.resourcepacks.status.unsupported"),
                i18n("swing.resourcepacks.details"),
                i18n("swing.resourcepacks.no_selection"),
                i18n("file"),
                i18n("swing.resourcepacks.path"),
                i18n("swing.resourcepacks.description"),
                i18n("swing.resourcepacks.compatibility"),
                i18n("swing.resourcepacks.enabled"),
                i18n("button.enable"),
                i18n("button.disable"),
                i18n("swing.resourcepacks.compatible"),
                i18n("resourcepack.warning.too_new"),
                i18n("resourcepack.warning.too_old"),
                i18n("resourcepack.warning.invalid"),
                i18n("resourcepack.warning.missing_pack_meta"),
                i18n("resourcepack.warning.missing_game_meta"));
    }

    /// Creates localized installed-resource-pack lifecycle text.
    ///
    /// @return resource-pack status text
    private static ResourcePackCatalogStatusStrings createResourcePackStatusStrings() {
        return new ResourcePackCatalogStatusStrings(
                i18n("swing.resourcepacks.status.idle"),
                i18n("swing.resourcepacks.status.loading"),
                i18n("swing.resourcepacks.status.ready"),
                i18n("swing.resourcepacks.status.empty"),
                i18n("swing.resourcepacks.status.unsupported"),
                i18n("swing.resourcepacks.status.failed"),
                i18n("message.unknown"),
                i18n("swing.resourcepacks.status.writing"),
                i18n("swing.resourcepacks.status.write_failed"));
    }

    /// Creates localized installed-resource-pack action text.
    ///
    /// @return resource-pack command and confirmation text
    private static ResourcePackCatalogActionStrings createResourcePackActionStrings() {
        return new ResourcePackCatalogActionStrings(
                i18n("resourcepack.add"),
                i18n("swing.resourcepacks.import_tooltip"),
                i18n("resourcepack.add.title"),
                i18n("extension.resourcepack"),
                i18n("button.enable"),
                i18n("swing.resourcepacks.enable_tooltip"),
                i18n("button.disable"),
                i18n("swing.resourcepacks.disable_tooltip"),
                i18n("message.warning"),
                i18n("swing.resourcepacks.enable_incompatible_confirm"),
                i18n("button.delete"),
                i18n("swing.resourcepacks.delete_tooltip"),
                i18n("swing.resourcepacks.delete_confirm"),
                i18n("button.reveal_dir"),
                i18n("reveal.in_file_manager"),
                i18n("folder.resourcepacks"),
                i18n("swing.resourcepacks.open_directory_tooltip"),
                i18n("message.failed"),
                i18n("swing.resourcepacks.reveal_failed"),
                i18n("swing.resourcepacks.open_directory_failed"));
    }

    /// Creates localized game-version catalog controls.
    ///
    /// @return game-version catalog text
    private static GameVersionCatalogStrings createGameVersionStrings() {
        return new GameVersionCatalogStrings(
                i18n("download.game"),
                i18n("search"),
                i18n("version.game.type"),
                i18n("version.game.all"),
                i18n("version.game.release"),
                i18n("version.game.snapshot"),
                i18n("version.game.april_fools"),
                i18n("version.game.old"),
                i18n("button.refresh"),
                i18n("swing.common.refreshing"));
    }

    /// Creates localized game-version catalog lifecycle states.
    ///
    /// @return game-version status text
    private static GameVersionCatalogStatusStrings createGameVersionStatusStrings() {
        return new GameVersionCatalogStatusStrings(
                i18n("swing.versions.status.idle"),
                i18n("message.downloading"),
                i18n("swing.versions.status.ready"),
                i18n("search.no_results_found"),
                i18n("download.failed.no_code"));
    }

    /// Creates localized vanilla-install controls, task text, and validation states.
    ///
    /// @return game-install text
    private static GameInstallStrings createGameInstallStrings() {
        return new GameInstallStrings(
                i18n("instance.name"),
                i18n("button.install"),
                i18n("swing.install.back_to_versions"),
                i18n("install.new_game.installation"),
                i18n("install.installing"),
                i18n("install.new_game.malformed"),
                i18n("install.new_game.already_exists"),
                i18n("swing.install.status.already_running"),
                i18n("install.failed"));
    }

    /// Creates localized account-page controls.
    ///
    /// @return account-page text
    private static AccountsStrings createAccountsStrings() {
        return new AccountsStrings(
                i18n("account"),
                i18n("account.create"),
                i18n("account.login.refresh"),
                i18n("account.copy_uuid"),
                i18n("button.delete"),
                i18n("button.remove.confirm"),
                i18n("message.error"),
                i18n("account.empty"));
    }

    /// Creates localized appearance-settings controls.
    ///
    /// @return appearance-settings text
    private static AppearanceSettingsStrings createAppearanceStrings() {
        return new AppearanceSettingsStrings(
                i18n("settings.launcher.appearance"),
                i18n("settings.launcher.brightness"),
                i18n("theme_pack.theme"),
                i18n("settings.launcher.brightness.auto"),
                i18n("settings.launcher.brightness.light"),
                i18n("settings.launcher.brightness.dark"),
                i18n("settings.launcher.corner_radius"),
                i18n("settings.launcher.animation"),
                createAppearanceBackgroundStrings());
    }

    /// Creates localized complete launcher-background controls.
    ///
    /// @return background settings text and enum labels
    private static AppearanceBackgroundStrings createAppearanceBackgroundStrings() {
        return new AppearanceBackgroundStrings(
                i18n("launcher.background"),
                i18n("launcher.background"),
                i18n("swing.appearance.background.override_source"),
                i18n("launcher.background.default"),
                i18n("launcher.background.builtin"),
                i18n("swing.appearance.background.local"),
                i18n("launcher.background.network"),
                i18n("launcher.background.paint"),
                i18n("launcher.background.theme_color"),
                i18n("launcher.background.builtin"),
                i18n("swing.appearance.background.local"),
                i18n("swing.appearance.background.browse"),
                i18n("swing.appearance.background.network_url"),
                i18n("launcher.background.paint"),
                i18n("swing.appearance.background.choose_color"),
                i18n("settings.launcher.background.settings.opacity"),
                i18n("swing.appearance.background.override_opacity"),
                i18n("launcher.background.network.cache"),
                i18n("launcher.background.network.cache.enabled"),
                i18n("launcher.background.network.cache.disabled"),
                i18n("settings.launcher.window_transparent"));
    }

    /// Creates localized generic task-progress controls and states.
    ///
    /// @return task-progress text
    private static TaskProgressStrings createTaskProgressStrings() {
        return new TaskProgressStrings(
                i18n("swing.task.status.waiting"),
                i18n("swing.task.status.running"),
                i18n("message.success"),
                i18n("message.failed"),
                i18n("message.cancelled"),
                i18n("swing.task.progress_name"),
                i18n("button.cancel"),
                i18n("swing.task.show_details"),
                i18n("swing.task.hide_details"));
    }

    /// Validates one caller-owned duration without replacing its identity.
    ///
    /// @param duration duration to validate
    /// @param name parameter name used in failures
    /// @return the same non-negative duration instance
    private static Duration requireNonNegative(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return duration;
    }
}

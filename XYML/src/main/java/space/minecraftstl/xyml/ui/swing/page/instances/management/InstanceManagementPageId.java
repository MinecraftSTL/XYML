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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Identifies every one-step destination retained by the instance-management workspace.
@NotNullByDefault
public enum InstanceManagementPageId {
    /// Current instance paths, icon, metadata, and common actions.
    OVERVIEW(
            InstanceManagementPageGroup.OVERVIEW,
            "swing.instance_overview.title",
            "assets/swing/icons/nav-home.svg"),

    /// Installed Mod management.
    MODS(
            InstanceManagementPageGroup.CONTENT,
            "mods.manage",
            "assets/swing/icons/format-list-bulleted.svg"),

    /// Installed resource-pack management.
    RESOURCE_PACKS(
            InstanceManagementPageGroup.CONTENT,
            "resourcepack.manage",
            "assets/swing/icons/image.svg"),

    /// Local world management.
    WORLDS(
            InstanceManagementPageGroup.CONTENT,
            "world.manage",
            "assets/swing/icons/folder-open.svg"),

    /// Data-pack management for the selected world.
    DATA_PACKS(
            InstanceManagementPageGroup.CONTENT,
            "datapack",
            "assets/swing/icons/file-import.svg"),

    /// Local schematic browsing and management.
    SCHEMATICS(
            InstanceManagementPageGroup.CONTENT,
            "schematics.manage",
            "assets/swing/icons/create-new-folder.svg"),

    /// Per-instance launch and isolation settings.
    GAME_SETTINGS(
            InstanceManagementPageGroup.CONFIGURATION,
            "instance.settings",
            "assets/swing/icons/nav-settings.svg"),

    /// Installed loader state and automatic loader installation.
    AUTOMATIC_INSTALL(
            InstanceManagementPageGroup.CONFIGURATION,
            "settings.tabs.installers",
            "assets/swing/icons/nav-downloads.svg"),

    /// Launch diagnostics, repair, and generated-file cleanup tools.
    MAINTENANCE_TOOLS(
            InstanceManagementPageGroup.MAINTENANCE,
            "swing.instance_navigation.page.maintenance_tools",
            "assets/swing/icons/rocket-launch.svg"),

    /// Local world-backup creation, restoration, and removal.
    BACKUPS(
            InstanceManagementPageGroup.MAINTENANCE,
            "world.backup",
            "assets/swing/icons/restore.svg"),

    /// Explicit installed-file update scanning and application.
    FILE_UPDATE_CHECK(
            InstanceManagementPageGroup.MAINTENANCE,
            "addon.check_update",
            "assets/swing/icons/refresh.svg"),

    /// Offline modpack archive export.
    MODPACK_EXPORT(
            InstanceManagementPageGroup.MAINTENANCE,
            "modpack.export",
            "assets/swing/icons/output.svg"),

    /// Rename, duplicate, and delete operations for the current instance.
    INSTANCE_OPERATIONS(
            InstanceManagementPageGroup.INSTANCE,
            "swing.instance_navigation.page.instance_operations",
            "assets/swing/icons/nav-instances.svg");

    /// Declaration-order snapshot shared by rendering and keyboard navigation.
    private static final @Unmodifiable List<InstanceManagementPageId> ORDERED_VALUES = List.of(values());

    /// Visual section containing this destination.
    private final InstanceManagementPageGroup group;

    /// Resource-bundle key for the visible button text.
    private final String localizationKey;

    /// Bundled theme-aware SVG resource associated with this destination.
    private final String iconResource;

    /// Creates one stable destination definition.
    ///
    /// @param group visual section containing the destination
    /// @param localizationKey resource-bundle key for its visible label
    /// @param iconResource classpath-relative bundled SVG resource
    InstanceManagementPageId(
            InstanceManagementPageGroup group,
            String localizationKey,
            String iconResource) {
        this.group = group;
        this.localizationKey = localizationKey;
        this.iconResource = iconResource;
    }

    /// Returns the declaration-order page list.
    ///
    /// @return immutable ordered destination definitions
    public static @Unmodifiable List<InstanceManagementPageId> orderedValues() {
        return ORDERED_VALUES;
    }

    /// Returns the visual section containing this destination.
    ///
    /// @return stable navigation group
    public InstanceManagementPageGroup group() {
        return group;
    }

    /// Returns the current-locale visible destination text.
    ///
    /// @return non-blank localized button label
    public String localizedLabel() {
        return i18n(localizationKey);
    }

    /// Returns the classpath-relative bundled SVG resource.
    ///
    /// @return stable icon resource path
    public String iconResource() {
        return iconResource;
    }
}

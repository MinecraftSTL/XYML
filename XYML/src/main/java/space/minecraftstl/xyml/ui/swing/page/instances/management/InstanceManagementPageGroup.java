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

/// Identifies the stable visual sections of the instance-management workspace navigation.
@NotNullByDefault
public enum InstanceManagementPageGroup {
    /// Ungrouped overview destination displayed before every titled section.
    OVERVIEW("swing.instance_overview.title", false),

    /// Installed content such as Mods, resource packs, worlds, data packs, and schematics.
    CONTENT("swing.instance_navigation.group.content", true),

    /// Instance launch configuration and automatic loader installation.
    CONFIGURATION("swing.instance_navigation.group.configuration", true),

    /// Repair, backup, update, and export workflows.
    MAINTENANCE("swing.instance_navigation.group.maintenance", true),

    /// Destructive or identity-changing operations on the managed instance.
    INSTANCE("swing.instance_navigation.group.instance", true);

    /// Declaration-order snapshot used by layout and keyboard traversal.
    private static final @Unmodifiable List<InstanceManagementPageGroup> ORDERED_VALUES = List.of(values());

    /// Resource-bundle key for the localized section name.
    private final String localizationKey;

    /// Whether the navigation renders a visible heading before this section.
    private final boolean headingVisible;

    /// Creates one stable navigation section definition.
    ///
    /// @param localizationKey resource-bundle key for the localized section name
    /// @param headingVisible whether the section has a visible heading
    InstanceManagementPageGroup(String localizationKey, boolean headingVisible) {
        this.localizationKey = localizationKey;
        this.headingVisible = headingVisible;
    }

    /// Returns the declaration-order section list.
    ///
    /// @return immutable ordered group definitions
    public static @Unmodifiable List<InstanceManagementPageGroup> orderedValues() {
        return ORDERED_VALUES;
    }

    /// Returns the pages assigned to this section in their stable navigation order.
    ///
    /// @return immutable ordered pages belonging to this group
    public @Unmodifiable List<InstanceManagementPageId> pages() {
        return InstanceManagementPageId.orderedValues().stream()
                .filter(page -> page.group() == this)
                .toList();
    }

    /// Returns the current-locale section name.
    ///
    /// @return non-blank localized section name
    public String localizedLabel() {
        return i18n(localizationKey);
    }

    /// Reports whether this section is preceded by a visible heading.
    ///
    /// @return true when the navigation should render the localized section name
    public boolean headingVisible() {
        return headingVisible;
    }
}

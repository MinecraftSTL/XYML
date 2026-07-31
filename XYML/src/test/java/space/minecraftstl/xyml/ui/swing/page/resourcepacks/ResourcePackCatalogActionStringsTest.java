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
package space.minecraftstl.xyml.ui.swing.page.resourcepacks;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Focused validation tests for resource-pack catalog action presentation.
@NotNullByDefault
public final class ResourcePackCatalogActionStringsTest {
    /// Valid localized text and compatible confirmation formats are retained exactly.
    @Test
    public void retainsValidPresentationExactly() {
        ResourcePackCatalogActionStrings strings = validStrings("Enable %s?", "Delete %s?");

        assertEquals("Enable %s?", strings.incompatibleEnableConfirmationFormat());
        assertEquals("Delete %s?", strings.deleteConfirmationFormat());
        validStrings("Enable %S?", "Delete %.10s?");
    }

    /// Missing and blank fields are rejected at construction.
    @Test
    public void rejectsMissingAndBlankPresentation() {
        assertThrows(NullPointerException.class, () -> new ResourcePackCatalogActionStrings(
                null,
                "Import tooltip",
                "Choose ZIP files",
                "ZIP files",
                "Enable",
                "Enable tooltip",
                "Disable",
                "Disable tooltip",
                "Warning",
                "Enable %s?",
                "Delete",
                "Delete tooltip",
                "Delete %s?",
                "Reveal",
                "Reveal tooltip",
                "Open directory",
                "Open directory tooltip",
                "Operation failed",
                "Reveal failed",
                "Open directory failed"));
        assertThrows(IllegalArgumentException.class, () -> new ResourcePackCatalogActionStrings(
                "Import",
                "Import tooltip",
                "Choose ZIP files",
                "ZIP files",
                "Enable",
                "Enable tooltip",
                "Disable",
                "Disable tooltip",
                "Warning",
                "Enable %s?",
                "Delete",
                "Delete tooltip",
                "Delete %s?",
                "Reveal",
                "Reveal tooltip",
                "Open directory",
                "Open directory tooltip",
                "Operation failed",
                "Reveal failed",
                " "));
    }

    /// Both confirmation templates must consume exactly one compatible target argument.
    @Test
    public void rejectsMissingOrIncompatibleConfirmationPlaceholder() {
        assertThrows(IllegalArgumentException.class, () -> validStrings("Enable target?", "Delete %s?"));
        assertThrows(IllegalArgumentException.class, () -> validStrings("Enable %d?", "Delete %s?"));
        assertThrows(IllegalArgumentException.class, () -> validStrings("Enable %s and %s?", "Delete %s?"));
        assertThrows(IllegalArgumentException.class, () -> validStrings("Enable %s?", "Delete target?"));
        assertThrows(IllegalArgumentException.class, () -> validStrings("Enable %s?", "Delete %d?"));
        assertThrows(IllegalArgumentException.class, () -> validStrings("Enable %s?", "Delete %1$s twice %1$s?"));
    }

    /// Creates valid presentation while varying both confirmation templates.
    ///
    /// @param incompatibleFormat incompatible-enable confirmation template
    /// @param deleteFormat permanent-delete confirmation template
    /// @return valid action presentation
    private static ResourcePackCatalogActionStrings validStrings(
            String incompatibleFormat,
            String deleteFormat) {
        return new ResourcePackCatalogActionStrings(
                "Import",
                "Import tooltip",
                "Choose ZIP files",
                "ZIP resource packs",
                "Enable",
                "Enable tooltip",
                "Disable",
                "Disable tooltip",
                "Incompatible resource pack",
                incompatibleFormat,
                "Delete",
                "Delete tooltip",
                deleteFormat,
                "Reveal",
                "Reveal tooltip",
                "Open directory",
                "Open directory tooltip",
                "Operation failed",
                "Reveal failed",
                "Open directory failed");
    }
}

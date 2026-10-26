// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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

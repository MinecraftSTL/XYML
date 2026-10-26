// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.schematics;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Focused validation tests for schematic browser action presentation.
@NotNullByDefault
public final class SchematicBrowserActionStringsTest {
    /// Valid localized text is retained exactly.
    @Test
    public void retainsValidPresentationExactly() {
        SchematicBrowserActionStrings strings = validStrings("Delete %s?");

        assertEquals("Delete %s?", strings.deleteConfirmationFormat());
        validStrings("Delete %S?");
        validStrings("Delete %.10s?");
    }

    /// Missing and blank fields are rejected at construction.
    @Test
    public void rejectsMissingAndBlankPresentation() {
        assertThrows(NullPointerException.class, () -> new SchematicBrowserActionStrings(
                null,
                "Import tooltip",
                "Choose files",
                "Litematic files",
                "Create directory",
                "Create tooltip",
                "Directory name",
                "Delete",
                "Delete tooltip",
                "Delete %s?",
                "Reveal",
                "Reveal tooltip",
                "Writing",
                "Write failed",
                "Operation failed",
                "Reveal failed"));
        assertThrows(IllegalArgumentException.class, () -> new SchematicBrowserActionStrings(
                "Import",
                "Import tooltip",
                "Choose files",
                "Litematic files",
                "Create directory",
                "Create tooltip",
                "Directory name",
                "Delete",
                "Delete tooltip",
                "Delete %s?",
                "Reveal",
                "Reveal tooltip",
                "Writing",
                "Write failed",
                "Operation failed",
                " "));
    }

    /// The delete template must consume exactly one compatible target argument.
    @Test
    public void rejectsMissingOrIncompatibleDeletePlaceholder() {
        assertThrows(IllegalArgumentException.class, () -> validStrings("Delete target?"));
        assertThrows(IllegalArgumentException.class, () ->
                validStrings("Delete __XYML_DELETE_TARGET__?"));
        assertThrows(IllegalArgumentException.class, () -> validStrings("Delete %d?"));
        assertThrows(IllegalArgumentException.class, () -> validStrings("Delete %s and %s?"));
        assertThrows(IllegalArgumentException.class, () -> validStrings("Delete %1$s and %1$s?"));
        assertThrows(IllegalArgumentException.class, () -> validStrings("Delete %#s?"));
    }

    /// Creates a valid presentation while varying the delete template.
    ///
    /// @param deleteConfirmationFormat delete confirmation template
    /// @return valid action presentation
    private static SchematicBrowserActionStrings validStrings(String deleteConfirmationFormat) {
        return new SchematicBrowserActionStrings(
                "Import",
                "Import tooltip",
                "Choose files",
                "Litematic files",
                "Create directory",
                "Create tooltip",
                "Directory name",
                "Delete",
                "Delete tooltip",
                deleteConfirmationFormat,
                "Reveal",
                "Reveal tooltip",
                "Writing",
                "Write failed",
                "Operation failed",
                "Reveal failed");
    }
}

// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.shaderpacks;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies shader-pack rows retain local descriptions without a package-icon field.
@NotNullByDefault
final class ShaderPackCatalogItemTest {
    /// Optional presentation metadata is immutable and available to the details view.
    @Test
    void retainsPresentationMetadata() {
        ShaderPackCatalogItem item = new ShaderPackCatalogItem(
                Path.of("shaderpacks", "example.zip"),
                "example.zip",
                "Example",
                true,
                Set.of(ShaderPackBackend.IRIS_OCULUS),
                "Local description");

        assertEquals("Local description", item.description());
        assertEquals("Example", item.displayText());
    }
}

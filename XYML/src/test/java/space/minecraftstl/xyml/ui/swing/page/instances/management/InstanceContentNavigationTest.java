// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.ui.swing.page.downloads.DownloadPageTarget;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies old category-only implementations remain functional with the optional search boundary.
@NotNullByDefault
final class InstanceContentNavigationTest {
    /// Falls back to Mods navigation for an existing lambda implementation.
    @Test
    void preservesFunctionalInterfaceFallback() {
        List<DownloadPageTarget> targets = new ArrayList<>();
        InstanceContentNavigation navigation = targets::add;
        navigation.openModSearch(" Litematica ");
        assertEquals(List.of(DownloadPageTarget.MODS), targets);
        assertThrows(IllegalArgumentException.class, () -> navigation.openModSearch(" \t"));
        assertThrows(NullPointerException.class, () -> navigation.openModSearch(null));
        assertEquals(1, targets.size());
        InstanceContentNavigation.disabled().openModSearch("Litematica");
    }
}

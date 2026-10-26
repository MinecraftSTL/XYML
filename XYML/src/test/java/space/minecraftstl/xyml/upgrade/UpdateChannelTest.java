// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.upgrade;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies canonical channel identifiers and their version depths.
@NotNullByDefault
final class UpdateChannelTest {
    /// Maps every supported identifier without historical aliases.
    @Test
    void resolvesCanonicalIdentifiers() {
        assertEquals(UpdateChannel.STABLE, UpdateChannel.fromName("stable"));
        assertEquals(UpdateChannel.BETA, UpdateChannel.fromName("beta"));
        assertEquals(UpdateChannel.ALPHA, UpdateChannel.fromName("alpha"));
        assertEquals(UpdateChannel.DEV, UpdateChannel.fromName("dev"));
        assertThrows(IllegalArgumentException.class, () -> UpdateChannel.fromName("nightly"));
        assertThrows(IllegalArgumentException.class, () -> UpdateChannel.fromName("DEVELOPMENT"));
    }

    /// Exposes the complete explicit update-source selection matrix.
    @Test
    void exposesSelectableUpdateSources() {
        assertEquals(List.of(UpdateChannel.STABLE, UpdateChannel.BETA),
                UpdateChannel.STABLE.selectableUpdateSources());
        assertEquals(List.of(UpdateChannel.STABLE, UpdateChannel.BETA),
                UpdateChannel.BETA.selectableUpdateSources());
        assertEquals(List.of(UpdateChannel.STABLE, UpdateChannel.BETA, UpdateChannel.ALPHA),
                UpdateChannel.ALPHA.selectableUpdateSources());
        assertEquals(List.of(UpdateChannel.STABLE, UpdateChannel.BETA, UpdateChannel.ALPHA, UpdateChannel.DEV),
                UpdateChannel.DEV.selectableUpdateSources());
    }

    /// Assigns exactly one additional decimal component at each less-stable channel.
    @Test
    void exposesRequiredVersionDepth() {
        assertEquals(3, UpdateChannel.STABLE.versionComponentCount());
        assertEquals(4, UpdateChannel.BETA.versionComponentCount());
        assertEquals(5, UpdateChannel.ALPHA.versionComponentCount());
        assertEquals(6, UpdateChannel.DEV.versionComponentCount());
    }
}

// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.game.GameInstanceID;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;

/// Verifies that legacy target resolvers cannot silently replace an explicit instance selection.
@NotNullByDefault
final class RemoteAddonInstallTargetResolverTest {
    /// Rejects explicit targets when an implementation exposes only launcher-global resolution.
    @org.junit.jupiter.api.Test
    void failsClosedForLegacyResolver() {
        RemoteAddonInstallTargetResolver legacyResolver = kind -> Optional.of(new RemoteAddonInstallTarget(
                kind,
                new GameInstanceID("global"),
                Path.of("target")));

        assertFalse(legacyResolver.isSelectionAvailable(
                RemoteAddonCatalogKind.MOD,
                new GameInstanceID("explicit")));
        assertFalse(legacyResolver.resolve(
                RemoteAddonCatalogKind.MOD,
                new GameInstanceID("explicit")).isPresent());
    }
}

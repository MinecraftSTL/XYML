// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.game.GameInstancePatch;
import space.minecraftstl.xyml.setting.GameInstanceIconType;
import space.minecraftstl.xyml.util.i18n.I18n;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests XYML manifest-derived instance detail and automatic icon selection.
@NotNullByDefault
public final class InstancePresentationTest {
    /// Stable instance ID used by synthetic resolved manifests.
    private static final GameInstanceID INSTANCE_ID = new GameInstanceID("presentation-test");

    /// Loader patches contribute localized versions while the preferred loader controls the icon.
    @Test
    public void resolvesLoaderDetailAndPriorityFromManifestPatches() {
        InstancePresentation presentation = InstancePresentation.resolve(
                resolvedManifest(
                        patch("forge", "47.2.0"),
                        patch("fabric", "0.15.11")),
                "1.20.1",
                "Unknown version");

        assertAll(
                () -> assertEquals(GameInstanceIconType.FABRIC, presentation.defaultIconType()),
                () -> assertTrue(presentation.detail().startsWith("1.20.1")),
                () -> assertTrue(presentation.detail().contains(
                        I18n.i18n("install.installer.fabric") + " 0.15.11")),
                () -> assertTrue(presentation.detail().contains(
                        I18n.i18n("install.installer.forge") + " 47.2.0")));
    }

    /// Every supported loader family maps to its XYML bundled icon.
    @Test
    public void mapsSupportedLoadersToBundledIcons() {
        assertAll(
                () -> assertLoaderIcon("fabric", GameInstanceIconType.FABRIC),
                () -> assertLoaderIcon("quilt", GameInstanceIconType.QUILT),
                () -> assertLoaderIcon("legacyfabric", GameInstanceIconType.LEGACY_FABRIC),
                () -> assertLoaderIcon("neoforge", GameInstanceIconType.NEO_FORGE),
                () -> assertLoaderIcon("forge", GameInstanceIconType.FORGE),
                () -> assertLoaderIcon("cleanroom", GameInstanceIconType.CLEANROOM),
                () -> assertLoaderIcon("liteloader", GameInstanceIconType.CHICKEN),
                () -> assertLoaderIcon("optifine", GameInstanceIconType.OPTIFINE));
    }

    /// Vanilla and unknown instances retain the established game-version icon fallbacks.
    @Test
    public void resolvesGameVersionFallbackIcons() {
        assertAll(
                () -> assertVersionIcon("20w14infinite", GameInstanceIconType.APRIL_FOOLS),
                () -> assertVersionIcon("13w24a", GameInstanceIconType.COMMAND),
                () -> assertVersionIcon("26.1-snapshot-1", GameInstanceIconType.COMMAND),
                () -> assertVersionIcon("b1.1-1", GameInstanceIconType.CRAFT_TABLE),
                () -> assertVersionIcon("1.21.1", GameInstanceIconType.GRASS));

        InstancePresentation unknown = InstancePresentation.resolve(
                resolvedManifest(),
                null,
                "Unknown version");
        assertAll(
                () -> assertEquals("Unknown version", unknown.detail()),
                () -> assertEquals(GameInstanceIconType.GRASS, unknown.defaultIconType()));
    }

    /// Asserts one loader patch's automatic icon.
    ///
    /// @param patchId loader patch identifier
    /// @param expected expected bundled icon type
    private static void assertLoaderIcon(String patchId, GameInstanceIconType expected) {
        InstancePresentation presentation = InstancePresentation.resolve(
                resolvedManifest(patch(patchId, "1.0.0")),
                "1.20.1",
                "Unknown version");
        assertEquals(expected, presentation.defaultIconType(), patchId);
    }

    /// Asserts one vanilla game version's automatic icon.
    ///
    /// @param gameVersion Minecraft version
    /// @param expected expected bundled icon type
    private static void assertVersionIcon(String gameVersion, GameInstanceIconType expected) {
        InstancePresentation presentation = InstancePresentation.resolve(
                resolvedManifest(),
                gameVersion,
                "Unknown version");
        assertEquals(expected, presentation.defaultIconType(), gameVersion);
    }

    /// Creates one synthetic loader patch.
    ///
    /// @param id patch identifier
    /// @param version patch version
    /// @return immutable loader patch
    private static GameInstancePatch patch(String id, String version) {
        return new GameInstancePatch(
                id,
                version,
                GameInstancePatch.PRIORITY_LOADER,
                null,
                null,
                null);
    }

    /// Creates resolved launch and standalone views from the supplied patches.
    ///
    /// @param patches synthetic patches
    /// @return resolved manifest views
    private static GameInstanceManifest.Resolved resolvedManifest(GameInstancePatch... patches) {
        GameInstanceManifest launchManifest = new GameInstanceManifest(INSTANCE_ID);
        GameInstanceManifest standaloneManifest = launchManifest.withPatches(
                List.copyOf(Arrays.asList(patches)));
        return new GameInstanceManifest.Resolved(
                standaloneManifest,
                launchManifest,
                standaloneManifest);
    }
}

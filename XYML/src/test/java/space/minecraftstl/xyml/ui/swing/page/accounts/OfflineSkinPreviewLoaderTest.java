// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.auth.offline.Skin;
import space.minecraftstl.xyml.auth.yggdrasil.TextureModel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies that offline preview decoding uses only bundled or selected local image bytes.
@NotNullByDefault
public final class OfflineSkinPreviewLoaderTest {
    /// Isolated local skin and cape fixture directory.
    @TempDir
    private Path temporaryDirectory;

    /// Bundled Alex and Steve textures decode without network services or UI initialization.
    @Test
    public void decodesBundledSkins() throws IOException {
        OfflineSkinPreview alex = OfflineSkinPreviewLoader.load(
                OfflineSkinService.createBundledSkin(Skin.Type.ALEX),
                "Player");
        OfflineSkinPreview steve = OfflineSkinPreviewLoader.load(
                OfflineSkinService.createBundledSkin(Skin.Type.STEVE),
                "Player");

        assertAll(
                () -> assertEquals(TextureModel.SLIM, alex.model()),
                () -> assertEquals(TextureModel.WIDE, steve.model()),
                () -> assertEquals(64, alex.skin().getWidth()),
                () -> assertEquals(64, steve.skin().getHeight()));
    }

    /// Local skin and cape files are both decoded into one preview payload.
    @Test
    public void decodesLocalSkinAndCape() throws IOException {
        Path skinFile = writePng("skin.png", 64, 64);
        Path capeFile = writePng("cape.png", 64, 32);
        Skin skin = OfflineSkinService.createLocalSkin(skinFile, capeFile, TextureModel.SLIM);

        OfflineSkinPreview preview = OfflineSkinPreviewLoader.load(skin, "Player");

        assertAll(
                () -> assertEquals(TextureModel.SLIM, preview.model()),
                () -> assertEquals(64, preview.skin().getWidth()),
                () -> assertNotNull(preview.cape()),
                () -> assertEquals(32, preview.cape().getHeight()));
    }

    /// Profile defaults are deterministic while provider sources are never fetched implicitly.
    @Test
    public void defaultsAreStableAndRemoteSourcesAreRejected() throws IOException {
        assertEquals(
                OfflineSkinPreviewLoader.defaultType("Player"),
                OfflineSkinPreviewLoader.defaultType("Player"));
        assertNotNull(OfflineSkinPreviewLoader.load(null, "Player").skin());
        Skin provider = OfflineSkinService.createProviderSkin(Skin.Type.LITTLE_SKIN, null);
        assertThrows(
                IllegalArgumentException.class,
                () -> OfflineSkinPreviewLoader.load(provider, "Player"));
    }

    /// An available profile UUID selects both the exact bundled texture and its wide or slim variant.
    @Test
    public void usesProfileUuidForExactDefaultSelection() throws IOException {
        OfflineSkinPreview slim = OfflineSkinPreviewLoader.load(
                null,
                "Ignored",
                "00000000-0000-0000-0000-000000000000");
        OfflineSkinPreview wide = OfflineSkinPreviewLoader.load(
                null,
                "Ignored",
                "00000000-0000-0000-0000-000000000009");

        assertAll(
                () -> assertEquals(TextureModel.SLIM, slim.model()),
                () -> assertEquals(TextureModel.WIDE, wide.model()),
                () -> assertEquals(64, slim.skin().getWidth()),
                () -> assertEquals(64, wide.skin().getWidth()));
    }

    /// Writes one transparent PNG fixture.
    ///
    /// @param name fixture filename
    /// @param width image width
    /// @param height image height
    /// @return written image path
    /// @throws IOException when fixture creation fails
    private Path writePng(String name, int width, int height) throws IOException {
        Path image = temporaryDirectory.resolve(name);
        BufferedImage pixels = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        ImageIO.write(pixels, "PNG", image.toFile());
        return image;
    }
}

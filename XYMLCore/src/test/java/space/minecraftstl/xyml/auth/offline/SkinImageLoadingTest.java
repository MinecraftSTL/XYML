// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.auth.offline;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.auth.yggdrasil.TextureModel;
import space.minecraftstl.xyml.task.TaskResource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies bundled and local offline skins load through JDK image APIs in a headless workflow.
@NotNullByDefault
final class SkinImageLoadingTest {
    /// Temporary directory for local skin and cape fixtures.
    @TempDir
    private Path temporaryDirectory;

    /// A bundled source resolves its expected model and 64-by-64 image from the Core classpath.
    @Test
    void loadsBundledSkinWithoutJavaFx() throws Exception {
        Skin skin = new Skin(Skin.Type.ALEX, null, null, null, null);

        Skin.LoadedSkin loaded = Objects.requireNonNull(skin.load("Player").run());

        assertEquals(TextureModel.SLIM, loaded.model());
        Texture texture = Objects.requireNonNull(loaded.skin());
        assertEquals(64, texture.image().getWidth());
        assertEquals(64, texture.image().getHeight());
        assertNull(loaded.cape());
    }

    /// Local skin and cape files are independently decoded and retain the configured arm model.
    @Test
    void loadsLocalSkinAndCapeWithImageIo() throws Exception {
        Path skinFile = temporaryDirectory.resolve("skin.png");
        Path capeFile = temporaryDirectory.resolve("cape.png");
        writeFixture(skinFile, 64, 64, 0xFF123456);
        writeFixture(capeFile, 64, 32, 0xFF654321);
        Skin skin = new Skin(
                Skin.Type.LOCAL_FILE,
                null,
                TextureModel.WIDE,
                skinFile.toString(),
                capeFile.toString());

        Skin.LoadedSkin loaded = Objects.requireNonNull(skin.load("Player").run());

        assertEquals(TextureModel.WIDE, loaded.model());
        assertEquals(0xFF123456, Objects.requireNonNull(loaded.skin()).image().getRGB(3, 4));
        assertEquals(0xFF654321, Objects.requireNonNull(loaded.cape()).image().getRGB(3, 4));
    }

    /// The default source continues to signal that the caller should select a UUID-based skin.
    @Test
    void leavesDefaultSkinSelectionToCaller() throws Exception {
        Skin skin = new Skin(Skin.Type.DEFAULT, null, null, null, null);
        assertNull(skin.load("Player").run());
    }

    /// Read-only skin sources use only the non-filesystem orchestration marker.
    @Test
    void readOnlySkinSourcesUseOrchestrationResource() {
        for (Skin.Type type : List.of(Skin.Type.DEFAULT, Skin.Type.ALEX, Skin.Type.LOCAL_FILE)) {
            Skin skin = new Skin(type, null, null, null, null);
            assertEquals(
                    List.of(TaskResource.Kind.ORCHESTRATION),
                    skin.load("Player").getResources().stream().map(TaskResource::getKind).toList());
        }
    }

    /// A remote skin graph keeps only its concrete network and cache children locked.
    @Test
    void remoteSkinCompositionUsesOrchestrationResource() {
        Skin skin = new Skin(
                Skin.Type.CUSTOM_SKIN_LOADER_API,
                "https://example.invalid/csl",
                null,
                null,
                null);

        assertEquals(
                List.of(TaskResource.Kind.ORCHESTRATION),
                skin.load("Player").getResources().stream().map(TaskResource::getKind).toList());
    }

    /// Writes one solid PNG fixture.
    ///
    /// @param path output path
    /// @param width image width
    /// @param height image height
    /// @param argb solid pixel value
    private static void writeFixture(Path path, int width, int height, int argb) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, argb);
            }
        }
        assertTrue(ImageIO.write(image, "PNG", path.toFile()));
    }
}

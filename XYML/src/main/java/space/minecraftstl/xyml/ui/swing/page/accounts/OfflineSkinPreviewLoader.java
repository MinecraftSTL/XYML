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
package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.auth.offline.Skin;
import space.minecraftstl.xyml.auth.yggdrasil.TextureModel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/// Performs the blocking, network-free image decoding required by the offline skin preview.
///
/// Callers must invoke [#load(Skin, String)] away from the Swing event dispatch thread. Remote skin providers are
/// intentionally excluded because opening or editing the dialog must not make an implicit network request.
@NotNullByDefault
final class OfflineSkinPreviewLoader {
    /// Player textures used by the launcher's UUID-derived default selection.
    private static final Skin.Type @Unmodifiable [] DEFAULT_SKINS = {
            Skin.Type.ALEX,
            Skin.Type.ARI,
            Skin.Type.EFE,
            Skin.Type.KAI,
            Skin.Type.MAKENA,
            Skin.Type.NOOR,
            Skin.Type.STEVE,
            Skin.Type.SUNNY,
            Skin.Type.ZURI
    };

    /// Prevents utility instantiation.
    private OfflineSkinPreviewLoader() {
    }

    /// Decodes a local, bundled, or UUID-like default preview from launcher-owned resources.
    ///
    /// @param skin persisted skin configuration, or null for the profile-derived default
    /// @param profileName offline profile name used to choose the default model
    /// @return decoded preview
    /// @throws IOException when a configured local or bundled image cannot be decoded
    /// @throws IllegalArgumentException when called for a remote source
    static OfflineSkinPreview load(@Nullable Skin skin, String profileName) throws IOException {
        return load(skin, profileName, null);
    }

    /// Decodes a local, bundled, or exact UUID-derived default preview from launcher-owned resources.
    ///
    /// @param skin persisted skin configuration, or null for the profile-derived default
    /// @param profileName offline profile name used when a profile UUID is unavailable
    /// @param profileId Minecraft profile UUID text, or null for a legacy presentation source
    /// @return decoded preview
    /// @throws IOException when a configured local or bundled image cannot be decoded
    /// @throws IllegalArgumentException when called for a remote source
    static OfflineSkinPreview load(
            @Nullable Skin skin,
            String profileName,
            @Nullable String profileId) throws IOException {
        Objects.requireNonNull(profileName, "profileName");
        Skin.Type type = skin == null ? Skin.Type.DEFAULT : skin.type();
        if (type == Skin.Type.DEFAULT) {
            DefaultSkinSelection selection = defaultSkin(profileName, profileId);
            return loadBundled(selection.type(), selection.model());
        }
        if (OfflineSkinService.isBundledSkin(type)) {
            return loadBundled(type);
        }
        if (type != Skin.Type.LOCAL_FILE || skin == null) {
            throw new IllegalArgumentException("Remote skin sources are not previewed implicitly: " + type);
        }

        @Nullable String skinPath = skin.localSkinPath();
        if (skinPath == null) {
            throw new IOException("Local skin path is missing");
        }
        Path normalizedSkin = OfflineSkinService.validatePng(Path.of(skinPath));
        @Nullable BufferedImage skinImage = ImageIO.read(normalizedSkin.toFile());
        if (skinImage == null) {
            throw new IOException("Local skin image cannot be decoded: " + normalizedSkin);
        }

        @Nullable BufferedImage capeImage = null;
        @Nullable String capePath = skin.localCapePath();
        if (capePath != null && !capePath.isBlank()) {
            Path normalizedCape = OfflineSkinService.validatePng(Path.of(capePath));
            capeImage = ImageIO.read(normalizedCape.toFile());
            if (capeImage == null) {
                throw new IOException("Local cape image cannot be decoded: " + normalizedCape);
            }
        }
        return new OfflineSkinPreview(skin.textureModel(), skinImage, capeImage);
    }

    /// Chooses a stable launcher-bundled default from the standard offline profile UUID.
    ///
    /// @param profileName offline profile name
    /// @return one of the nine launcher-bundled player textures
    static Skin.Type defaultType(String profileName) {
        return defaultSkin(profileName, null).type();
    }

    /// Decodes one launcher-bundled skin resource.
    ///
    /// @param type bundled player source
    /// @return decoded bundled preview
    /// @throws IOException when the packaged resource is missing or undecodable
    private static OfflineSkinPreview loadBundled(Skin.Type type) throws IOException {
        TextureModel model = type == Skin.Type.ALEX ? TextureModel.SLIM : TextureModel.WIDE;
        return loadBundled(type, model);
    }

    /// Decodes one launcher-bundled skin resource with an exact default-selection model.
    ///
    /// @param type bundled player source
    /// @param model selected arm model
    /// @return decoded bundled preview
    /// @throws IOException when the packaged resource is missing or undecodable
    private static OfflineSkinPreview loadBundled(
            Skin.Type type,
            TextureModel model) throws IOException {
        String resource = "/assets/img/skin/"
                + (model == TextureModel.SLIM ? "slim/" : "wide/")
                + type.name().toLowerCase(Locale.ROOT)
                + ".png";
        try (InputStream input = OfflineSkinPreviewLoader.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing bundled skin preview: " + resource);
            }
            @Nullable BufferedImage image = ImageIO.read(input);
            if (image == null) {
                throw new IOException("Invalid bundled skin preview: " + resource);
            }
            return new OfflineSkinPreview(model, image, null);
        }
    }

    /// Reproduces the launcher's 18-way UUID-derived bundled skin and model selection.
    ///
    /// @param profileName offline profile name fallback
    /// @param profileId Minecraft profile UUID text, or null
    /// @return exact bundled type and arm model
    private static DefaultSkinSelection defaultSkin(
            String profileName,
            @Nullable String profileId) {
        UUID profileUuid = parseProfileUuid(profileName, profileId);
        int index = Math.floorMod(profileUuid.hashCode(), DEFAULT_SKINS.length * 2);
        TextureModel model = index < DEFAULT_SKINS.length ? TextureModel.SLIM : TextureModel.WIDE;
        Skin.Type type = DEFAULT_SKINS[index % DEFAULT_SKINS.length];
        return new DefaultSkinSelection(type, model);
    }

    /// Parses a profile UUID or derives the standard offline UUID from the profile name.
    ///
    /// @param profileName offline profile name
    /// @param profileId Minecraft profile UUID text, or null
    /// @return stable profile UUID
    private static UUID parseProfileUuid(String profileName, @Nullable String profileId) {
        if (profileId != null && !profileId.isBlank()) {
            try {
                return UUID.fromString(profileId);
            } catch (IllegalArgumentException ignored) {
                // Malformed legacy metadata falls through to the standard offline UUID.
            }
        }
        return UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + Objects.requireNonNull(profileName, "profileName"))
                        .getBytes(StandardCharsets.UTF_8));
    }

    /// One exact bundled default texture and arm model.
    ///
    /// @param type bundled player type
    /// @param model arm model
    @NotNullByDefault
    private record DefaultSkinSelection(Skin.Type type, TextureModel model) {
        /// Validates one exact default selection.
        private DefaultSkinSelection {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(model, "model");
        }
    }
}

/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml.auth.offline;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.auth.yggdrasil.TextureModel;
import space.minecraftstl.xyml.task.FetchTask;
import space.minecraftstl.xyml.task.GetTask;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.Lang;
import space.minecraftstl.xyml.util.StringUtils;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.util.io.FileUtils;
import space.minecraftstl.xyml.util.io.NetworkUtils;
import space.minecraftstl.xyml.util.io.UrlResponseInfo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/// Persisted offline-account skin configuration and toolkit-neutral loading workflow.
///
/// @param type configured skin source
/// @param cslApi custom-skin-loader endpoint, or null when not applicable
/// @param textureModel configured arm model, or null when the source determines it
/// @param localSkinPath local skin path, or null when not configured
/// @param localCapePath local cape path, or null when not configured
@NotNullByDefault
public record Skin(
        Type type,
        @Nullable String cslApi,
        @Nullable TextureModel textureModel,
        @Nullable String localSkinPath,
        @Nullable String localCapePath) {

    /// Supported persisted skin sources.
    @NotNullByDefault
    public enum Type {
        /// UUID-derived launcher default skin.
        DEFAULT,

        /// Bundled Alex skin.
        ALEX,

        /// Bundled Ari skin.
        ARI,

        /// Bundled Efe skin.
        EFE,

        /// Bundled Kai skin.
        KAI,

        /// Bundled Makena skin.
        MAKENA,

        /// Bundled Noor skin.
        NOOR,

        /// Bundled Steve skin.
        STEVE,

        /// Bundled Sunny skin.
        SUNNY,

        /// Bundled Zuri skin.
        ZURI,

        /// Skin and optional cape loaded from local files.
        LOCAL_FILE,

        /// LittleSkin custom-skin-loader service.
        LITTLE_SKIN,

        /// User-provided custom-skin-loader endpoint.
        CUSTOM_SKIN_LOADER_API,

        /// Yggdrasil-compatible skin service reserved by the persisted format.
        YGGDRASIL_API;

        /// Parses a persisted lowercase source identifier.
        ///
        /// @param type persisted identifier
        /// @return matching source, or null when the identifier is unknown
        public static @Nullable Type fromStorage(String type) {
            return switch (type) {
                case "default" -> DEFAULT;
                case "alex" -> ALEX;
                case "ari" -> ARI;
                case "efe" -> EFE;
                case "kai" -> KAI;
                case "makena" -> MAKENA;
                case "noor" -> NOOR;
                case "steve" -> STEVE;
                case "sunny" -> SUNNY;
                case "zuri" -> ZURI;
                case "local_file" -> LOCAL_FILE;
                case "little_skin" -> LITTLE_SKIN;
                case "custom_skin_loader_api" -> CUSTOM_SKIN_LOADER_API;
                case "yggdrasil_api" -> YGGDRASIL_API;
                default -> null;
            };
        }
    }

    /// Returns the effective arm model used when the configured source does not provide one.
    ///
    /// @return configured model, or the wide model by default
    @Override
    public TextureModel textureModel() {
        return textureModel == null ? TextureModel.WIDE : textureModel;
    }

    /// Loads the configured skin and cape without initializing a graphical toolkit.
    ///
    /// @param username profile name used by remote custom-skin-loader services
    /// @return asynchronous loaded skin, or null when the source has no custom texture
    public Task<@Nullable LoadedSkin> load(String username) {
        switch (type) {
            case DEFAULT:
                return Task.supplyAsync(() -> null);
            case ALEX:
            case ARI:
            case EFE:
            case KAI:
            case MAKENA:
            case NOOR:
            case STEVE:
            case SUNNY:
            case ZURI:
                TextureModel model = textureModel != null
                        ? textureModel
                        : type == Type.ALEX ? TextureModel.SLIM : TextureModel.WIDE;
                String resource = (model == TextureModel.SLIM
                        ? "/assets/img/skin/slim/"
                        : "/assets/img/skin/wide/") + type.name().toLowerCase(Locale.ROOT) + ".png";

                return Task.supplyAsync(() -> new LoadedSkin(
                        model,
                        loadBuiltinTexture(resource),
                        null));
            case LOCAL_FILE:
                return Task.supplyAsync(() -> {
                    @Nullable Texture skin = null;
                    @Nullable Texture cape = null;
                    Optional<Path> skinPath = FileUtils.tryGetPath(localSkinPath);
                    Optional<Path> capePath = FileUtils.tryGetPath(localCapePath);
                    if (skinPath.isPresent()) {
                        skin = Texture.loadTexture(Files.newInputStream(skinPath.get()));
                    }
                    if (capePath.isPresent()) {
                        cape = Texture.loadTexture(Files.newInputStream(capePath.get()));
                    }
                    return new LoadedSkin(textureModel(), skin, cape);
                });
            case LITTLE_SKIN:
            case CUSTOM_SKIN_LOADER_API:
                String realCslApi = type == Type.LITTLE_SKIN
                        ? "https://littleskin.cn/csl"
                        : NetworkUtils.addHttpsIfMissing(
                                StringUtils.removeSuffix(Lang.requireNonNullElse(cslApi, ""), "/"));
                return Task.composeAsync(() -> new GetTask(String.format("%s/%s.json", realCslApi, username)))
                        .thenComposeAsync(json -> {
                            @Nullable SkinJson result = JsonUtils.GSON.fromJson(json, SkinJson.class);

                            if (result == null || !result.hasSkin()) {
                                return Task.supplyAsync(() -> null);
                            }

                            @Nullable String skinHash = result.getHash();
                            @Nullable String capeHash = result.getCapeHash();
                            return Task.allOf(
                                    Task.supplyAsync(result::getModel),
                                    skinHash == null
                                            ? Task.supplyAsync(() -> null)
                                            : new FetchBytesTask(String.format(
                                                    "%s/textures/%s", realCslApi, skinHash)),
                                    capeHash == null
                                            ? Task.supplyAsync(() -> null)
                                            : new FetchBytesTask(String.format(
                                                    "%s/textures/%s", realCslApi, capeHash)));
                        }).thenApplyAsync(result -> {
                            if (result == null) {
                                return null;
                            }

                            @Nullable Texture skin = result.get(1) == null
                                    ? null
                                    : Texture.loadTexture((InputStream) result.get(1));
                            @Nullable Texture cape = result.get(2) == null
                                    ? null
                                    : Texture.loadTexture((InputStream) result.get(2));
                            return new LoadedSkin((@Nullable TextureModel) result.get(0), skin, cape);
                        });
            default:
                throw new UnsupportedOperationException("Unsupported skin type: " + type);
        }
    }

    /// Loads one bundled skin resource through the JDK image decoder.
    ///
    /// @param resource absolute classpath resource
    /// @return decoded canonical texture
    /// @throws IOException when the resource is missing or invalid
    private static Texture loadBuiltinTexture(String resource) throws IOException {
        @Nullable InputStream input = Skin.class.getResourceAsStream(resource);
        if (input == null) {
            throw new IOException("Missing bundled skin resource: " + resource);
        }
        @Nullable Texture texture = Texture.loadTexture(input);
        if (texture == null) {
            throw new IOException("Missing bundled skin texture: " + resource);
        }
        return texture;
    }

    /// Writes this configuration to account metadata.
    ///
    /// @param storage destination metadata object
    public void writeStorage(JsonObject storage) {
        storage.addProperty("type", type.name().toLowerCase(Locale.ROOT));
        storage.addProperty("cslApi", cslApi);
        storage.addProperty("textureModel", textureModel().modelName);
        storage.addProperty("localSkinPath", localSkinPath);
        storage.addProperty("localCapePath", localCapePath);
    }

    /// Reconstructs a skin configuration from account metadata.
    ///
    /// Unknown source identifiers fall back to [Type#DEFAULT].
    ///
    /// @param storage source metadata, or null when no configuration was persisted
    /// @return parsed configuration, or null for absent metadata
    public static @Nullable Skin fromStorage(@Nullable JsonObject storage) {
        if (storage == null) {
            return null;
        }

        @Nullable String typeText = JsonUtils.getString(storage, "type");
        @Nullable Type parsedType = typeText != null ? Type.fromStorage(typeText) : Type.DEFAULT;
        Type type = parsedType == null ? Type.DEFAULT : parsedType;
        @Nullable String cslApi = JsonUtils.getString(storage, "cslApi");
        String textureModel = JsonUtils.getString(storage, "textureModel", "default");
        @Nullable String localSkinPath = JsonUtils.getString(storage, "localSkinPath");
        @Nullable String localCapePath = JsonUtils.getString(storage, "localCapePath");

        return new Skin(
                type,
                cslApi,
                "slim".equals(textureModel) ? TextureModel.SLIM : TextureModel.WIDE,
                localSkinPath,
                localCapePath);
    }

    /// Downloads one remote texture into memory while retaining repository ETag caching.
    @NotNullByDefault
    private static final class FetchBytesTask extends FetchTask<InputStream> {
        /// Creates a fetch task for one absolute texture URI.
        ///
        /// @param uri absolute texture URI
        private FetchBytesTask(String uri) {
            super(List.of(NetworkUtils.toURI(uri)));
        }

        /// Opens a cached response as the task result.
        ///
        /// @param cachedFile cached texture path
        /// @throws IOException when the cached file cannot be opened
        @Override
        protected void useCachedResult(Path cachedFile) throws IOException {
            setResult(Files.newInputStream(cachedFile));
        }

        /// Enables ETag validation for remote texture responses.
        ///
        /// @return ETag validation mode
        @Override
        protected EnumCheckETag shouldCheckETag() {
            return EnumCheckETag.CHECK_E_TAG;
        }

        /// Creates an in-memory response sink that can also populate the repository cache.
        ///
        /// @param response response metadata, or null before a response is available
        /// @param checkETag whether successful bytes should be cached
        /// @param bmclapiHash optional mirror hash, unused for texture responses
        /// @return response sink
        @Override
        protected Context getContext(
                @Nullable UrlResponseInfo response,
                boolean checkETag,
                @Nullable String bmclapiHash) {
            return new Context() {
                /// Accumulates the current response body.
                private final ByteArrayOutputStream output = new ByteArrayOutputStream();

                /// Clears bytes retained from a previous response attempt.
                @Override
                public void reset() {
                    output.reset();
                }

                /// Appends one response chunk.
                ///
                /// @param buffer source bytes
                /// @param offset source offset
                /// @param length byte count
                @Override
                public void write(byte[] buffer, int offset, int length) {
                    output.write(buffer, offset, length);
                }

                /// Publishes successful bytes and optionally stores them in the repository cache.
                ///
                /// @throws IOException when repository caching fails
                @Override
                public void close() throws IOException {
                    if (!isSuccess()) {
                        return;
                    }

                    byte[] bytes = output.toByteArray();
                    setResult(new ByteArrayInputStream(bytes));
                    if (checkETag) {
                        repository.cacheBytes(response, bytes);
                    }
                }
            };
        }
    }

    /// Result of loading an offline skin configuration.
    ///
    /// @param model detected or configured arm model, or null when unknown
    /// @param skin decoded skin texture, or null when absent
    /// @param cape decoded cape texture, or null when absent
    @NotNullByDefault
    public record LoadedSkin(
            @Nullable TextureModel model,
            @Nullable Texture skin,
            @Nullable Texture cape) {
    }

    /// Custom-skin-loader profile response.
    ///
    /// @param username profile name, or null when the profile is absent
    /// @param skin legacy wide-skin hash, or null
    /// @param cape legacy cape hash, or null
    /// @param elytra legacy elytra hash, or null
    /// @param textures structured texture hashes, or null
    @NotNullByDefault
    private record SkinJson(
            @Nullable String username,
            @Nullable String skin,
            @Nullable String cape,
            @Nullable String elytra,
            @SerializedName(value = "textures", alternate = {"skins"}) @Nullable TextureJson textures) {
        /// Reports whether the response describes an existing profile.
        ///
        /// @return whether a nonblank profile name is present
        private boolean hasSkin() {
            return StringUtils.isNotBlank(username);
        }

        /// Detects the arm model from structured texture fields.
        ///
        /// @return slim or wide model, or null when no skin hash exists
        private @Nullable TextureModel getModel() {
            if (textures != null && textures.slim != null) {
                return TextureModel.SLIM;
            } else if (textures != null && textures.defaultSkin != null) {
                return TextureModel.WIDE;
            } else {
                return null;
            }
        }

        /// Returns the structured slim-skin hash.
        ///
        /// @return slim hash, or null
        private @Nullable String getAlexModelHash() {
            return textures == null ? null : textures.slim;
        }

        /// Returns the structured or legacy wide-skin hash.
        ///
        /// @return wide hash, or null
        private @Nullable String getSteveModelHash() {
            if (textures != null && textures.defaultSkin != null) {
                return textures.defaultSkin;
            }
            return skin;
        }

        /// Chooses the skin hash corresponding to the detected model.
        ///
        /// @return selected skin hash, or null when no model can be detected
        private @Nullable String getHash() {
            @Nullable TextureModel model = getModel();
            if (model == TextureModel.SLIM) {
                return getAlexModelHash();
            } else if (model == TextureModel.WIDE) {
                return getSteveModelHash();
            } else {
                return null;
            }
        }

        /// Returns the structured or legacy cape hash.
        ///
        /// @return cape hash, or null
        private @Nullable String getCapeHash() {
            if (textures != null && textures.cape != null) {
                return textures.cape;
            }
            return cape;
        }

        /// Structured custom-skin-loader texture fields.
        ///
        /// @param defaultSkin wide-skin hash, or null
        /// @param slim slim-skin hash, or null
        /// @param cape cape hash, or null
        /// @param elytra elytra hash, or null
        @NotNullByDefault
        private record TextureJson(
                @SerializedName("default") @Nullable String defaultSkin,
                @Nullable String slim,
                @Nullable String cape,
                @Nullable String elytra) {
        }
    }
}

/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
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

import org.glavo.uuid.UUIDs;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.auth.yggdrasil.GameProfile;
import space.minecraftstl.xyml.auth.yggdrasil.TextureModel;
import space.minecraftstl.xyml.util.KeyUtils;
import space.minecraftstl.xyml.util.Lang;
import space.minecraftstl.xyml.util.Pair;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.util.io.HttpServer;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static space.minecraftstl.xyml.util.Lang.mapOf;
import static space.minecraftstl.xyml.util.Pair.pair;
import static space.minecraftstl.xyml.util.gson.JsonUtils.listTypeOf;

/// Minimal loopback Yggdrasil service used to expose offline-account skins to the game.
///
/// Texture responses are encoded with the JDK ImageIO implementation and require no JavaFX
/// classes or native graphical environment.
@NotNullByDefault
public class YggdrasilServer extends HttpServer {
    /// Registered characters indexed by profile UUID.
    private final Map<UUID, Character> charactersByUuid = new HashMap<>();

    /// Registered characters indexed by exact profile name.
    private final Map<String, Character> charactersByName = new HashMap<>();

    /// Signing key used by the embedded session service.
    private static final KeyPair KEY_PAIR = KeyUtils.generateKey();

    /// Creates an embedded server and registers its Yggdrasil-compatible routes.
    ///
    /// @param port listening port, or zero to choose an available port
    public YggdrasilServer(int port) {
        super(port);

        addRoute(Method.GET, Pattern.compile("^/$"), this::root);
        addRoute(Method.GET, Pattern.compile("/status"), this::status);
        addRoute(Method.POST, Pattern.compile("/api/profiles/minecraft"), this::profiles);
        addRoute(Method.GET, Pattern.compile("/sessionserver/session/minecraft/hasJoined"), this::hasJoined);
        addRoute(Method.POST, Pattern.compile("/sessionserver/session/minecraft/join"), this::joinServer);
        addRoute(Method.GET,
                Pattern.compile("/sessionserver/session/minecraft/profile/(?<uuid>[a-f0-9]{32})"),
                this::profile);
        addRoute(Method.GET, Pattern.compile("/textures/(?<hash>[a-f0-9]{64})"), this::texture);
    }

    /// Returns authlib-injector discovery metadata.
    ///
    /// @param request HTTP request
    /// @return JSON discovery response
    private Response root(Request request) {
        return ok(mapOf(
                pair("signaturePublickey", KeyUtils.toPEMPublicKey(getSignaturePublicKey())),
                pair("skinDomains", Arrays.asList(
                        "127.0.0.1",
                        "localhost")),
                pair("meta", mapOf(
                        pair("serverName", "XYML"),
                        pair("implementationName", "XYML"),
                        pair("implementationVersion", "1.0"),
                        pair("feature.non_email_login", true)))));
    }

    /// Returns live registration counters expected by authlib-injector diagnostics.
    ///
    /// @param request HTTP request
    /// @return JSON status response
    private Response status(Request request) {
        return ok(mapOf(
                pair("user.count", charactersByUuid.size()),
                pair("token.count", 0),
                pair("pendingAuthentication.count", 0)));
    }

    /// Resolves a batch of profile names.
    ///
    /// @param request JSON name-list request
    /// @return JSON array containing registered profiles
    /// @throws IOException when the request body cannot be read
    private Response profiles(Request request) throws IOException {
        List<String> names = JsonUtils.fromNonNullJsonFully(
                request.getSession().getInputStream(), listTypeOf(String.class));
        return ok(names.stream()
                .distinct()
                .map(this::findCharacterByName)
                .flatMap(Lang::toStream)
                .map(Character::toSimpleResponse)
                .collect(Collectors.toList()));
    }

    /// Resolves a joined profile by its username query parameter.
    ///
    /// @param request HTTP request
    /// @return complete profile, no content, or a bad-request response
    private Response hasJoined(Request request) {
        if (!request.getQuery().containsKey("username")) {
            return badRequest();
        }

        Optional<Character> character = findCharacterByName(request.getQuery().get("username"));

        // Work around JDK-8138667 by avoiding Optional.map in this response path.
        //noinspection OptionalIsPresent
        if (character.isPresent()) {
            return ok(character.get().toCompleteResponse(getRootUrl()));
        } else {
            return HttpServer.noContent();
        }
    }

    /// Accepts the stateless offline join request.
    ///
    /// @param request HTTP request
    /// @return empty success response
    private Response joinServer(Request request) {
        return noContent();
    }

    /// Resolves a complete profile by compact UUID path parameter.
    ///
    /// @param request HTTP request
    /// @return complete profile or no-content response
    private Response profile(Request request) {
        String uuid = request.getPathVariables().group("uuid");
        Optional<Character> character = findCharacterByUuid(UUIDs.parse(uuid));

        // Work around JDK-8138667 by avoiding Optional.map in this response path.
        //noinspection OptionalIsPresent
        if (character.isPresent()) {
            return ok(character.get().toCompleteResponse(getRootUrl()));
        } else {
            return HttpServer.noContent();
        }
    }

    /// Encodes and returns one cached texture as PNG.
    ///
    /// @param request HTTP request
    /// @return PNG response or not-found response
    /// @throws IOException when ImageIO cannot encode the cached image
    private Response texture(Request request) throws IOException {
        String hash = request.getPathVariables().group("hash");
        @Nullable Texture texture = Texture.getTexture(hash);
        if (texture == null) {
            return notFound();
        }

        byte[] data = encodePng(texture);
        Response response = newFixedLengthResponse(
                Response.Status.OK,
                "image/png",
                new ByteArrayInputStream(data),
                data.length);
        response.addHeader("Etag", String.format("\"%s\"", hash));
        response.addHeader("Cache-Control", "max-age=2592000, public");
        return response;
    }

    /// Encodes one texture in lossless PNG format.
    ///
    /// @param texture decoded texture
    /// @return encoded PNG bytes
    /// @throws IOException when no PNG writer is installed or encoding fails
    private static byte[] encodePng(Texture texture) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(texture.image(), "PNG", output)) {
            throw new IOException("No PNG ImageIO writer is available");
        }
        return output.toByteArray();
    }

    /// Looks up one character by UUID.
    ///
    /// @param uuid profile UUID
    /// @return optional registered character
    private Optional<Character> findCharacterByUuid(UUID uuid) {
        return Optional.ofNullable(charactersByUuid.get(uuid));
    }

    /// Looks up one character by exact profile name.
    ///
    /// @param name profile name
    /// @return optional registered character
    private Optional<Character> findCharacterByName(String name) {
        return Optional.ofNullable(charactersByName.get(name));
    }

    /// Registers or replaces a character in both lookup indexes.
    ///
    /// @param character character to publish
    public void addCharacter(Character character) {
        charactersByUuid.put(character.getUUID(), character);
        charactersByName.put(character.getName(), character);
    }

    /// One offline profile and its optional custom textures.
    @NotNullByDefault
    public static final class Character {
        /// Stable profile UUID.
        private final UUID uuid;

        /// Exact profile name.
        private final String name;

        /// Loaded skin and cape, or null when the default skin should be used.
        private final @Nullable Skin.LoadedSkin skin;

        /// Creates a profile exposed by the embedded service.
        ///
        /// @param uuid stable profile UUID
        /// @param name exact profile name
        /// @param skin optional loaded skin and cape
        public Character(UUID uuid, String name, @Nullable Skin.LoadedSkin skin) {
            this.uuid = uuid;
            this.name = name;
            this.skin = skin;
        }

        /// Returns the profile UUID.
        ///
        /// @return profile UUID
        public UUID getUUID() {
            return uuid;
        }

        /// Returns the exact profile name.
        ///
        /// @return profile name
        public String getName() {
            return name;
        }

        /// Creates the batch-lookup profile representation.
        ///
        /// @return simple profile
        public GameProfile toSimpleResponse() {
            return new GameProfile(uuid, name);
        }

        /// Creates a signed session profile with local texture URLs.
        ///
        /// @param rootUrl embedded server root URL
        /// @return complete profile response
        public Object toCompleteResponse(String rootUrl) {
            Map<String, Object> realTextures = new HashMap<>();
            if (skin != null && skin.skin() != null) {
                if (skin.model() == TextureModel.SLIM) {
                    realTextures.put("SKIN", mapOf(
                            pair("url", rootUrl + "/textures/" + skin.skin().hash()),
                            pair("metadata", mapOf(pair("model", "slim")))));
                } else {
                    realTextures.put("SKIN", mapOf(
                            pair("url", rootUrl + "/textures/" + skin.skin().hash())));
                }
            }
            if (skin != null && skin.cape() != null) {
                realTextures.put("CAPE", mapOf(
                        pair("url", rootUrl + "/textures/" + skin.cape().hash())));
            }

            Map<String, Object> textureResponse = mapOf(
                    pair("timestamp", System.currentTimeMillis()),
                    pair("profileId", UUIDs.toCompactString(uuid)),
                    pair("profileName", name),
                    pair("textures", realTextures));

            return mapOf(
                    pair("id", UUIDs.toCompactString(uuid)),
                    pair("name", name),
                    pair("properties", properties(true,
                            pair("textures", new String(
                                    Base64.getEncoder().encode(
                                            JsonUtils.GSON.toJson(textureResponse).getBytes(UTF_8)),
                                    UTF_8)))));
        }
    }

    /// Returns the public key used to sign session properties.
    ///
    /// @return signature public key
    public static PublicKey getSignaturePublicKey() {
        return KEY_PAIR.getPublic();
    }

    /// Signs one UTF-8 property value with the embedded server key.
    ///
    /// @param data property value
    /// @return Base64-encoded signature
    private static String sign(String data) {
        try {
            Signature signature = Signature.getInstance("SHA1withRSA");
            signature.initSign(KEY_PAIR.getPrivate(), new SecureRandom());
            signature.update(data.getBytes(UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to sign embedded Yggdrasil response", e);
        }
    }

    /// Creates Yggdrasil property objects, optionally adding signatures.
    ///
    /// @param sign whether each property value must be signed
    /// @param entries property name/value pairs
    /// @return immutable list of serialized property maps
    @SafeVarargs
    public static @Unmodifiable List<?> properties(boolean sign, Pair<String, String>... entries) {
        return Stream.of(entries)
                .map(entry -> {
                    LinkedHashMap<String, String> property = new LinkedHashMap<>();
                    property.put("name", entry.getKey());
                    property.put("value", entry.getValue());
                    if (sign) {
                        property.put("signature", sign(entry.getValue()));
                    }
                    return property;
                })
                .toList();
    }
}

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
package space.minecraftstl.xyml.auth.yggdrasil;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.glavo.uuid.UUIDs;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;
import space.minecraftstl.xyml.auth.AuthenticationException;
import space.minecraftstl.xyml.auth.ServerDisconnectException;
import space.minecraftstl.xyml.auth.ServerResponseMalformedException;
import space.minecraftstl.xyml.util.StringUtils;
import space.minecraftstl.xyml.util.gson.ValidationTypeAdapterFactory;
import space.minecraftstl.xyml.util.io.FileUtils;
import space.minecraftstl.xyml.util.io.HttpMultipartRequest;
import space.minecraftstl.xyml.util.io.NetworkUtils;
import space.minecraftstl.xyml.observable.cache.ObservableOptionalCache;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.unmodifiableList;
import static space.minecraftstl.xyml.util.Lang.mapOf;
import static space.minecraftstl.xyml.util.Lang.threadPool;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;
import static space.minecraftstl.xyml.util.Pair.pair;

/// Implements authentication, session management, profile lookup, and skin upload for a Yggdrasil provider.
@NotNullByDefault
public class YggdrasilService {

    /// Shared executor for asynchronous profile-property cache fetches.
    private static final ThreadPoolExecutor POOL = threadPool("YggdrasilProfileProperties", true, 2, 10, TimeUnit.SECONDS);

    /// Provider supplying this service's endpoint URLs.
    private final YggdrasilProvider provider;

    /// Caches complete profile data by player UUID.
    private final ObservableOptionalCache<UUID, CompleteGameProfile, AuthenticationException> profileRepository;

    /// Creates a service for one Yggdrasil endpoint provider.
    ///
    /// @param provider endpoint provider
    public YggdrasilService(YggdrasilProvider provider) {
        this.provider = provider;
        this.profileRepository = new ObservableOptionalCache<>(
                uuid -> {
                    LOG.info("Fetching properties of " + uuid + " from " + provider);
                    return getCompleteGameProfile(uuid);
                },
                (uuid, e) -> LOG.warning("Failed to fetch properties of " + uuid + " from " + provider, e),
                POOL);
    }

    /// Returns the asynchronously populated complete-profile cache.
    ///
    /// @return profile cache owned by this service
    public ObservableOptionalCache<UUID, CompleteGameProfile, AuthenticationException> getProfileRepository() {
        return profileRepository;
    }

    /// Authenticates credentials and requests the user's profile list.
    ///
    /// @param username account login name
    /// @param password account password
    /// @param clientToken launcher-generated client token
    /// @return authenticated Yggdrasil session
    /// @throws AuthenticationException if authentication fails or the response is malformed
    public YggdrasilSession authenticate(String username, String password, String clientToken) throws AuthenticationException {
        Objects.requireNonNull(username);
        Objects.requireNonNull(password);
        Objects.requireNonNull(clientToken);

        Map<String, Object> request = new HashMap<>();
        request.put("agent", mapOf(
                pair("name", "Minecraft"),
                pair("version", 1)
        ));
        request.put("username", username);
        request.put("password", password);
        request.put("clientToken", clientToken);
        request.put("requestUser", true);

        return handleAuthenticationResponse(request(provider.getAuthenticationURL(), request), clientToken);
    }

    /// Builds a mutable request object containing Yggdrasil session credentials.
    ///
    /// @param accessToken session access token
    /// @param clientToken optional client token accepted by validation and invalidation endpoints
    /// @return mutable request map
    private static Map<String, @Nullable Object> createRequestWithCredentials(
            String accessToken,
            @Nullable String clientToken) {
        Map<String, @Nullable Object> request = new HashMap<>();
        request.put("accessToken", accessToken);
        request.put("clientToken", clientToken);
        return request;
    }

    /// Refreshes a Yggdrasil access token and optionally selects a profile.
    ///
    /// @param accessToken current session access token
    /// @param clientToken launcher-generated client token
    /// @param characterToSelect profile to select, or `null` to retain server defaults
    /// @return refreshed session
    /// @throws AuthenticationException if refresh fails or the requested profile is not selected
    public YggdrasilSession refresh(
            String accessToken,
            String clientToken,
            @Nullable GameProfile characterToSelect) throws AuthenticationException {
        Objects.requireNonNull(accessToken);
        Objects.requireNonNull(clientToken);

        Map<String, @Nullable Object> request = createRequestWithCredentials(accessToken, clientToken);
        request.put("requestUser", true);

        if (characterToSelect != null) {
            request.put("selectedProfile", mapOf(
                    pair("id", UUIDs.toCompactString(characterToSelect.getId())),
                    pair("name", characterToSelect.getName())));
        }

        YggdrasilSession response = handleAuthenticationResponse(request(provider.getRefreshmentURL(), request), clientToken);

        if (characterToSelect != null) {
            if (response.getSelectedProfile() == null ||
                    !response.getSelectedProfile().getId().equals(characterToSelect.getId())) {
                throw new ServerResponseMalformedException("Failed to select character");
            }
        }

        return response;
    }

    /// Validates an access token without requiring a client token.
    ///
    /// @param accessToken session access token
    /// @return `true` when the token remains valid
    /// @throws AuthenticationException if validation fails for a reason other than an invalid token
    public boolean validate(String accessToken) throws AuthenticationException {
        return validate(accessToken, null);
    }

    /// Validates an access token and optional client-token binding.
    ///
    /// @param accessToken session access token
    /// @param clientToken client token, or `null` when the server should validate only the access token
    /// @return `true` when the token remains valid
    /// @throws AuthenticationException if validation fails for a reason other than an invalid token
    public boolean validate(String accessToken, @Nullable String clientToken) throws AuthenticationException {
        Objects.requireNonNull(accessToken);

        try {
            requireEmpty(request(provider.getValidationURL(), createRequestWithCredentials(accessToken, clientToken)));
            return true;
        } catch (RemoteAuthenticationException e) {
            if ("ForbiddenOperationException".equals(e.getRemoteName())) {
                return false;
            }
            throw e;
        }
    }

    /// Invalidates an access token without requiring a client token.
    ///
    /// @param accessToken session access token
    /// @throws AuthenticationException if invalidation fails
    public void invalidate(String accessToken) throws AuthenticationException {
        invalidate(accessToken, null);
    }

    /// Invalidates an access token and optional client-token binding.
    ///
    /// @param accessToken session access token
    /// @param clientToken client token, or `null` when not supplied
    /// @throws AuthenticationException if invalidation fails
    public void invalidate(String accessToken, @Nullable String clientToken) throws AuthenticationException {
        Objects.requireNonNull(accessToken);

        requireEmpty(request(provider.getInvalidationURL(), createRequestWithCredentials(accessToken, clientToken)));
    }

    /// Uploads a skin through the provider's Yggdrasil skin endpoint.
    ///
    /// @param uuid profile UUID
    /// @param accessToken session access token
    /// @param isSlim whether to select the slim player model
    /// @param file skin image to upload
    /// @throws AuthenticationException if the upload fails or returns an error payload
    /// @throws UnsupportedOperationException if the provider does not support skin uploads
    public void uploadSkin(UUID uuid, String accessToken, boolean isSlim, Path file) throws AuthenticationException, UnsupportedOperationException {
        try {
            HttpURLConnection con = NetworkUtils.createHttpConnection(provider.getSkinUploadURL(uuid));
            con.setRequestMethod("PUT");
            con.setRequestProperty("Authorization", "Bearer " + accessToken);
            con.setDoOutput(true);
            try (HttpMultipartRequest request = new HttpMultipartRequest(con)) {
                request.param("model", isSlim ? "slim" : "");
                try (InputStream fis = Files.newInputStream(file)) {
                    request.file("file", FileUtils.getName(file), "image/" + FileUtils.getExtension(file), fis);
                }
            }
            requireEmpty(NetworkUtils.readFullyAsString(con));
        } catch (IOException e) {
            throw new AuthenticationException(e);
        }
    }

    /// Fetches a complete game profile, including properties omitted by authentication responses.
    ///
    /// @param uuid profile UUID
    /// @return complete profile, or an empty optional when the provider returns JSON `null`
    /// @throws AuthenticationException if the request fails or returns malformed JSON
    public Optional<CompleteGameProfile> getCompleteGameProfile(UUID uuid) throws AuthenticationException {
        Objects.requireNonNull(uuid);

        return Optional.ofNullable(fromJson(request(provider.getProfilePropertiesURL(uuid), null), CompleteGameProfile.class));
    }

    /// Decodes the signed `textures` property from a complete profile.
    ///
    /// @param profile complete profile carrying base64-encoded texture data
    /// @return decoded texture map, or an empty optional when no texture property or payload map exists
    /// @throws ServerResponseMalformedException if the property is not valid base64 or JSON
    public static Optional<Map<TextureType, Texture>> getTextures(CompleteGameProfile profile) throws ServerResponseMalformedException {
        Objects.requireNonNull(profile);

        @Nullable String encodedTextures = profile.getProperties().get("textures");

        if (encodedTextures != null) {
            byte[] decodedBinary;
            try {
                decodedBinary = Base64.getDecoder().decode(encodedTextures);
            } catch (IllegalArgumentException e) {
                throw new ServerResponseMalformedException(e);
            }
            @Nullable TextureResponse texturePayload = fromJson(new String(decodedBinary, UTF_8), TextureResponse.class);
            return Optional.ofNullable(texturePayload.textures);
        } else {
            return Optional.empty();
        }
    }

    /// Converts an authentication response into a session and verifies the client token.
    ///
    /// @param responseText raw authentication or refresh response
    /// @param clientToken client token sent with the request
    /// @return authenticated session
    /// @throws AuthenticationException if the response reports an error or changes the client token
    private static YggdrasilSession handleAuthenticationResponse(String responseText, String clientToken) throws AuthenticationException {
        @Nullable AuthenticationResponse response = fromJson(responseText, AuthenticationResponse.class);
        handleErrorMessage(response);

        if (!clientToken.equals(response.clientToken))
            throw new AuthenticationException("Client token changed from " + clientToken + " to " + response.clientToken);

        @Nullable @UnmodifiableView List<@Nullable GameProfile> availableProfiles = response.availableProfiles == null
                ? null
                : unmodifiableList(response.availableProfiles);
        return new YggdrasilSession(
                response.clientToken,
                response.accessToken,
                response.selectedProfile,
                availableProfiles,
                response.user == null ? null : response.user.properties());
    }

    /// Accepts a blank success response or converts a nonblank error response into an exception.
    ///
    /// @param response raw response body
    /// @throws AuthenticationException when the body contains an error payload
    private static void requireEmpty(String response) throws AuthenticationException {
        if (StringUtils.isBlank(response))
            return;

        handleErrorMessage(fromJson(response, ErrorResponse.class));
    }

    /// Converts a Yggdrasil error payload into a remote authentication exception.
    ///
    /// @param response parsed response, which may be `null` for JSON `null`
    /// @throws AuthenticationException when the response contains an error name
    private static void handleErrorMessage(@Nullable ErrorResponse response) throws AuthenticationException {
        if (!StringUtils.isBlank(response.error)) {
            throw new RemoteAuthenticationException(response.error, response.errorMessage, response.cause);
        }
    }

    /// Performs a GET for a `null` payload or a JSON POST for a non-null payload.
    ///
    /// @param uri endpoint URI
    /// @param payload optional request payload
    /// @return response body
    /// @throws AuthenticationException if the request cannot be completed
    private static String request(URI uri, @Nullable Object payload) throws AuthenticationException {
        try {
            if (payload == null)
                return NetworkUtils.doGet(uri);
            else
                return NetworkUtils.doPost(uri, payload instanceof String ? (String) payload : GSON.toJson(payload), "application/json");
        } catch (IOException e) {
            throw new ServerDisconnectException(e);
        }
    }

    /// Parses a response body with the validation-enabled Gson instance.
    ///
    /// @param <T> response type
    /// @param text JSON response body
    /// @param typeOfT response class
    /// @return parsed object, or `null` when the response is JSON `null`
    /// @throws ServerResponseMalformedException if the response cannot be parsed
    private static <T> @Nullable T fromJson(String text, Class<T> typeOfT) throws ServerResponseMalformedException {
        try {
            return GSON.fromJson(text, typeOfT);
        } catch (JsonParseException e) {
            throw new ServerResponseMalformedException(text, e);
        }
    }

    /// Models the decoded payload of a Yggdrasil `textures` property.
    @NotNullByDefault
    private final static class TextureResponse {
        /// Optional texture entries from the decoded payload.
        public @Nullable Map<@Nullable TextureType, @Nullable Texture> textures;
    }

    /// Models a Yggdrasil authentication or refresh response.
    @NotNullByDefault
    private final static class AuthenticationResponse extends ErrorResponse {
        /// Optional refreshed access token.
        public @Nullable String accessToken;

        /// Optional echoed client token.
        public @Nullable String clientToken;

        /// Optional selected profile.
        public @Nullable GameProfile selectedProfile;

        /// Optional profiles available to the account.
        public @Nullable List<@Nullable GameProfile> availableProfiles;

        /// Optional Yggdrasil user data.
        public @Nullable User user;
    }

    /// Models the error fields shared by Yggdrasil responses.
    @NotNullByDefault
    private static class ErrorResponse {
        /// Optional remote error name.
        public @Nullable String error;

        /// Optional human-readable error message.
        public @Nullable String errorMessage;

        /// Optional remote error cause.
        public @Nullable String cause;
    }

    /// Gson instance that validates response types supported by the shared validation adapter.
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapterFactory(ValidationTypeAdapterFactory.INSTANCE)
            .create();

    /// Store page used when a Yggdrasil-compatible flow directs the user to purchase Minecraft.
    public static final String PURCHASE_URL = "https://www.xbox.com/games/store/minecraft-java-bedrock-edition-for-pc/9nxp44l49shj";
}

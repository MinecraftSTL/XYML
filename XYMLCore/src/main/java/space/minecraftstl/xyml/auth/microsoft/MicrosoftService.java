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
package space.minecraftstl.xyml.auth.microsoft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import org.glavo.uuid.UUIDs;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.auth.AuthenticationException;
import space.minecraftstl.xyml.auth.OAuth;
import space.minecraftstl.xyml.auth.ServerDisconnectException;
import space.minecraftstl.xyml.auth.ServerResponseMalformedException;
import space.minecraftstl.xyml.auth.yggdrasil.CompleteGameProfile;
import space.minecraftstl.xyml.auth.yggdrasil.RemoteAuthenticationException;
import space.minecraftstl.xyml.auth.yggdrasil.Texture;
import space.minecraftstl.xyml.auth.yggdrasil.TextureType;
import space.minecraftstl.xyml.util.StringUtils;
import space.minecraftstl.xyml.util.gson.*;
import space.minecraftstl.xyml.util.io.*;
import space.minecraftstl.xyml.observable.cache.ObservableOptionalCache;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.util.Objects.requireNonNull;
import static space.minecraftstl.xyml.util.Lang.mapOf;
import static space.minecraftstl.xyml.util.Lang.threadPool;
import static space.minecraftstl.xyml.util.Pair.pair;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Performs the Microsoft, Xbox Live, XSTS, and Minecraft Services authentication flow.
@NotNullByDefault
public class MicrosoftService {
    /// OAuth scopes required for Xbox Live authentication and token refresh.
    private static final String SCOPE = "XboxLive.signin offline_access";

    /// Shared executor for asynchronous profile-property cache fetches.
    private static final ThreadPoolExecutor POOL = threadPool("MicrosoftProfileProperties", true, 2, 10,
            TimeUnit.SECONDS);

    /// Receives browser or device-code callbacks from the OAuth flow.
    private final OAuth.Callback callback;

    /// Caches complete Mojang profile data by player UUID.
    private final ObservableOptionalCache<UUID, CompleteGameProfile, AuthenticationException> profileRepository;

    /// Creates a Microsoft authentication service.
    ///
    /// @param callback OAuth interaction callback
    public MicrosoftService(OAuth.Callback callback) {
        this.callback = requireNonNull(callback);
        this.profileRepository = new ObservableOptionalCache<>(uuid -> {
            LOG.info("Fetching properties of " + uuid);
            return getCompleteGameProfile(uuid);
        }, (uuid, e) -> LOG.warning("Failed to fetch properties of " + uuid, e), POOL);
    }

    /// Returns the asynchronously populated complete-profile cache.
    ///
    /// @return profile cache owned by this service
    public ObservableOptionalCache<UUID, CompleteGameProfile, AuthenticationException> getProfileRepository() {
        return profileRepository;
    }

    /// Authenticates a Microsoft account using the requested OAuth grant flow.
    ///
    /// @param flow authorization-code or device-code grant flow
    /// @return authenticated Minecraft session
    /// @throws AuthenticationException if OAuth or a downstream service rejects the request
    public MicrosoftSession authenticate(OAuth.GrantFlow flow) throws AuthenticationException {
        try {
            OAuth.Result result = OAuth.MICROSOFT.authenticate(flow, new OAuth.Options(SCOPE, callback));
            return authenticateViaLiveAccessToken(result.accessToken(), result.refreshToken());
        } catch (IOException e) {
            throw new ServerDisconnectException(e);
        } catch (JsonParseException e) {
            throw new ServerResponseMalformedException(e);
        }
    }

    /// Refreshes an existing Microsoft session using its Microsoft refresh token.
    ///
    /// @param oldSession session whose refresh token is used
    /// @return refreshed Minecraft session
    /// @throws AuthenticationException if the token cannot be refreshed or downstream authentication fails
    public MicrosoftSession refresh(MicrosoftSession oldSession) throws AuthenticationException {
        try {
            OAuth.Result result = OAuth.MICROSOFT.refresh(oldSession.refreshToken(), new OAuth.Options(SCOPE, callback));
            return authenticateViaLiveAccessToken(result.accessToken(), result.refreshToken());
        } catch (IOException e) {
            throw new ServerDisconnectException(e);
        } catch (JsonParseException e) {
            throw new ServerResponseMalformedException(e);
        }
    }

    /// Extracts and optionally verifies the Xbox user hash from an authorization response.
    ///
    /// @param response Xbox authorization response
    /// @param existingUhs user hash from an earlier stage, or `null` when no comparison is required
    /// @return response user hash; malformed maps may contain a `null` value
    /// @throws AuthenticationException if Xbox reports an error or the user hash is absent or inconsistent
    private @Nullable String getUhs(
            XBoxLiveAuthenticationResponse response,
            @Nullable String existingUhs) throws AuthenticationException {
        if (response.errorCode != 0) {
            throw new XboxAuthorizationException(response.errorCode, response.redirectUrl);
        }

        if (response.displayClaims == null || response.displayClaims.xui == null || response.displayClaims.xui.size() == 0 || !response.displayClaims.xui.get(0).containsKey("uhs")) {
            LOG.warning("Unrecognized xbox authorization response " + GSON.toJson(response));
            throw new NoXuiException();
        }

        @Nullable String uhs = (String) response.displayClaims.xui.get(0).get("uhs");
        if (existingUhs != null) {
            if (!Objects.equals(uhs, existingUhs)) {
                throw new ServerResponseMalformedException("uhs mismatched");
            }
        }
        return uhs;
    }

    /// Exchanges a Microsoft access token through Xbox Live, XSTS, and Minecraft Services.
    ///
    /// @param liveAccessToken Microsoft OAuth access token
    /// @param liveRefreshToken Microsoft OAuth refresh token persisted in the resulting session
    /// @return authenticated Minecraft session
    /// @throws IOException if a service cannot be reached
    /// @throws JsonParseException if a service returns malformed JSON
    /// @throws AuthenticationException if any authentication stage rejects the credentials
    private MicrosoftSession authenticateViaLiveAccessToken(String liveAccessToken, String liveRefreshToken)
            throws IOException, JsonParseException, AuthenticationException {
        @Nullable String uhs;
        XBoxLiveAuthenticationResponse xboxResponse, minecraftXstsResponse;
        try {
            // Authenticate with XBox Live
            xboxResponse = HttpRequest
                    .POST("https://user.auth.xboxlive.com/user/authenticate")
                    .json(mapOf(
                            pair("Properties",
                                    mapOf(pair("AuthMethod", "RPS"), pair("SiteName", "user.auth.xboxlive.com"),
                                            pair("RpsTicket", "d=" + liveAccessToken))),
                            pair("RelyingParty", "http://auth.xboxlive.com"), pair("TokenType", "JWT")))
                    .retry(5)
                    .accept("application/json")
                    .getJson(XBoxLiveAuthenticationResponse.class);

            uhs = getUhs(xboxResponse, null);

            @Unmodifiable List<@Nullable String> userTokens = Collections.singletonList(xboxResponse.token);
            minecraftXstsResponse = HttpRequest
                    .POST("https://xsts.auth.xboxlive.com/xsts/authorize")
                    .json(mapOf(
                            pair("Properties",
                                    mapOf(pair("SandboxId", "RETAIL"),
                                            pair("UserTokens", userTokens))),
                            pair("RelyingParty", "rp://api.minecraftservices.com/"), pair("TokenType", "JWT")))
                    .ignoreHttpErrorCode(401)
                    .retry(5)
                    .getJson(XBoxLiveAuthenticationResponse.class);
        } catch (ResponseCodeException e) {
            if (e.getResponseCode() == 400) {
                throw new XBox400Exception();
            }

            throw e;
        }

        getUhs(minecraftXstsResponse, uhs);

        // Authenticate with Minecraft
        MinecraftLoginWithXBoxResponse minecraftResponse = HttpRequest
                .POST("https://api.minecraftservices.com/authentication/login_with_xbox")
                .json(mapOf(pair("identityToken", "XBL3.0 x=" + uhs + ";" + minecraftXstsResponse.token)))
                .retry(5)
                .accept("application/json").getJson(MinecraftLoginWithXBoxResponse.class);

        long notAfter = minecraftResponse.expiresIn * 1000L + System.currentTimeMillis();

        // Check MC ownership, this is necessary, see GitHub#2979
        HttpURLConnection request = HttpRequest.GET("https://api.minecraftservices.com/entitlements/mcstore")
                .authorization("Bearer " + minecraftResponse.accessToken)
                .retry(5)
                .accept("application/json").createConnection();

        if (request.getResponseCode() != 200) {
            throw new ResponseCodeException("https://api.minecraftservices.com/entitlements/mcstore", request.getResponseCode());
        }

        // Get Minecraft Account UUID
        MinecraftProfileResponse profileResponse = getMinecraftProfile(minecraftResponse.tokenType, minecraftResponse.accessToken);
        handleErrorResponse(profileResponse);

        return new MicrosoftSession(minecraftResponse.tokenType, minecraftResponse.accessToken, notAfter, liveRefreshToken,
                new MicrosoftSession.User(minecraftResponse.username), new MicrosoftSession.GameProfile(profileResponse.id, profileResponse.name));
    }

    /// Requests the complete Minecraft profile for an authorization header.
    ///
    /// @param authorization complete HTTP Authorization header value
    /// @return profile response, or an empty optional when the service returns JSON `null`
    /// @throws AuthenticationException if the service cannot be reached or returns malformed JSON
    public Optional<MinecraftProfileResponse> getCompleteProfile(String authorization) throws AuthenticationException {
        try {
            return Optional.ofNullable(
                    HttpRequest.GET("https://api.minecraftservices.com/minecraft/profile")
                            .authorization(authorization).getJson(MinecraftProfileResponse.class));
        } catch (IOException e) {
            throw new ServerDisconnectException(e);
        } catch (JsonParseException e) {
            throw new ServerResponseMalformedException(e);
        }
    }

    /// Validates a Minecraft access token after checking its local expiration time.
    ///
    /// @param notAfter token expiration timestamp in epoch milliseconds
    /// @param tokenType authorization token type
    /// @param accessToken Minecraft Services access token
    /// @return `true` when the token remains accepted
    /// @throws AuthenticationException if validation cannot reach the service
    public boolean validate(long notAfter, String tokenType, String accessToken) throws AuthenticationException {
        requireNonNull(tokenType);
        requireNonNull(accessToken);

        if (System.currentTimeMillis() > notAfter) {
            return false;
        }

        try {
            getMinecraftProfile(tokenType, accessToken);
            return true;
        } catch (ResponseCodeException e) {
            return false;
        } catch (IOException e) {
            throw new ServerDisconnectException(e);
        }
    }

    /// Converts a Minecraft Services error payload into an authentication exception.
    ///
    /// @param response response to inspect
    /// @throws AuthenticationException when the response contains an error name
    private static void handleErrorResponse(MinecraftErrorResponse response) throws AuthenticationException {
        if (response.error != null) {
            throw new RemoteAuthenticationException(response.error, response.errorMessage, response.developerMessage);
        }
    }

    /// Extracts the first skin texture from a validated Minecraft profile response.
    ///
    /// @param profile validated profile response
    /// @return a present texture map, which may be empty when the profile has no skins
    public static Optional<Map<TextureType, Texture>> getTextures(MinecraftProfileResponse profile) {
        Objects.requireNonNull(profile);

        Map<TextureType, Texture> textures = new EnumMap<>(TextureType.class);

        if (!profile.skins.isEmpty()) {
            textures.put(TextureType.SKIN, new Texture(profile.skins.get(0).url, null));
        }
        // if (!profile.capes.isEmpty()) {
        // textures.put(TextureType.CAPE, new Texture(profile.capes.get(0).url, null);
        // }

        return Optional.of(textures);
    }

    /// Fetches Xbox profile settings for diagnostic or future profile integration.
    ///
    /// @param uhs Xbox user hash
    /// @param xstsToken Xbox Secure Token Service token
    /// @throws IOException if the profile request fails
    private static void getXBoxProfile(String uhs, String xstsToken) throws IOException {
        HttpRequest.GET("https://profile.xboxlive.com/users/me/profile/settings",
                        pair("settings", "GameDisplayName,AppDisplayName,AppDisplayPicRaw,GameDisplayPicRaw,"
                                + "PublicGamerpic,ShowUserAsAvatar,Gamerscore,Gamertag,ModernGamertag,ModernGamertagSuffix,"
                                + "UniqueModernGamertag,AccountTier,TenureLevel,XboxOneRep,"
                                + "PreferredColor,Location,Bio,Watermarks," + "RealName,RealNameOverride,IsQuarantined"))
                .accept("application/json")
                .authorization(String.format("XBL3.0 x=%s;%s", uhs, xstsToken))
                .header("x-xbl-contract-version", "3")
                .getString();
    }

    /// Fetches the Minecraft profile and distinguishes missing ownership from an uncreated profile.
    ///
    /// @param tokenType authorization token type
    /// @param accessToken Minecraft Services access token
    /// @return validated Minecraft profile
    /// @throws IOException if the profile or entitlement request fails
    /// @throws AuthenticationException if ownership or profile requirements are not met
    private static MinecraftProfileResponse getMinecraftProfile(String tokenType, String accessToken)
            throws IOException, AuthenticationException {
        HttpURLConnection conn = HttpRequest.GET("https://api.minecraftservices.com/minecraft/profile")
                .authorization(tokenType, accessToken)
                .createConnection();
        int responseCode = conn.getResponseCode();
        if (responseCode == HTTP_NOT_FOUND) {
            @Nullable MinecraftLicense license = HttpRequest.GET("https://api.minecraftservices.com/entitlements/license")
                    .authorization(tokenType, accessToken)
                    .getJson(MinecraftLicense.class);
            boolean hasMinecraftLicense = license != null && license.items() != null && license.items().stream()
                    .anyMatch(item -> "game_minecraft".equals(item.name()));
            if (!hasMinecraftLicense) {
                throw new MinecraftJavaEditionLicenseNotFoundException();
            } else {
                throw new MinecraftJavaEditionProfileNotFoundException();
            }
        } else if (responseCode != 200) {
            throw new ResponseCodeException("https://api.minecraftservices.com/minecraft/profile", responseCode);
        }

        String result = NetworkUtils.readFullyAsString(conn);
        return JsonUtils.fromNonNullJson(result, MinecraftProfileResponse.class);
    }

    /// Fetches signed Mojang session-server properties for one player.
    ///
    /// @param uuid player UUID
    /// @return complete profile, or an empty optional when the session server returns JSON `null`
    /// @throws AuthenticationException if the request cannot be completed
    public Optional<CompleteGameProfile> getCompleteGameProfile(UUID uuid) throws AuthenticationException {
        Objects.requireNonNull(uuid);

        return Optional.ofNullable(GSON.fromJson(request("https://sessionserver.mojang.com/session/minecraft/profile/" + UUIDs.toCompactString(uuid), null), CompleteGameProfile.class));
    }

    /// Uploads a skin through Minecraft Services.
    ///
    /// @param accessToken Minecraft Services access token
    /// @param isSlim whether to select the slim player model
    /// @param file skin image to upload
    /// @throws AuthenticationException if the upload fails or returns an error payload
    /// @throws UnsupportedOperationException if the current runtime cannot perform the upload
    public void uploadSkin(String accessToken, boolean isSlim, Path file) throws AuthenticationException, UnsupportedOperationException {
        try {
            HttpURLConnection con = NetworkUtils.createHttpConnection("https://api.minecraftservices.com/minecraft/profile/skins");
            con.setRequestMethod("POST");
            con.setRequestProperty("Authorization", "Bearer " + accessToken);
            con.setDoOutput(true);
            try (HttpMultipartRequest request = new HttpMultipartRequest(con)) {
                request.param("variant", isSlim ? "slim" : "classic");
                try (InputStream fis = Files.newInputStream(file)) {
                    request.file("file", FileUtils.getName(file), "image/" + FileUtils.getExtension(file), fis);
                }
            }

            String response = NetworkUtils.readFullyAsString(con);
            if (StringUtils.isBlank(response)) {
                if (con.getResponseCode() / 100 != 2)
                    throw new ResponseCodeException(con.getURL().toURI(), con.getResponseCode());
            } else {
                @Nullable MinecraftErrorResponse profileResponse = GSON.fromJson(response, MinecraftErrorResponse.class);
                if (StringUtils.isNotBlank(profileResponse.errorMessage) || con.getResponseCode() / 100 != 2)
                    throw new AuthenticationException("Failed to upload skin, response code: " + con.getResponseCode() + ", response: " + response);
            }
        } catch (IOException | JsonParseException | URISyntaxException e) {
            throw new AuthenticationException(e);
        }
    }

    /// Performs a GET for a `null` payload or a JSON POST for a non-null payload.
    ///
    /// @param url request URL
    /// @param payload optional request payload
    /// @return response body
    /// @throws AuthenticationException if the network request fails
    private static String request(String url, @Nullable Object payload) throws AuthenticationException {
        try {
            if (payload == null)
                return NetworkUtils.doGet(url);
            else
                return NetworkUtils.doPost(NetworkUtils.toURI(url), payload instanceof String ? (String) payload : GSON.toJson(payload), "application/json");
        } catch (IOException e) {
            throw new ServerDisconnectException(e);
        }
    }

    /// Reports an Xbox authorization error together with its numeric service code.
    @NotNullByDefault
    public static class XboxAuthorizationException extends AuthenticationException {
        /// Xbox service error code.
        private final long errorCode;

        /// Optional remediation URL returned by Xbox.
        private final @Nullable String redirect;

        /// Creates an Xbox authorization exception.
        ///
        /// @param errorCode Xbox service error code
        /// @param redirect optional remediation URL
        public XboxAuthorizationException(long errorCode, @Nullable String redirect) {
            this.errorCode = errorCode;
            this.redirect = redirect;
        }

        /// Returns the Xbox service error code.
        ///
        /// @return numeric Xbox error code
        public long getErrorCode() {
            return errorCode;
        }

        /// Returns the optional Xbox remediation URL.
        ///
        /// @return remediation URL, or `null` when Xbox did not supply one
        public @Nullable String getRedirect() {
            return redirect;
        }

        /// Error code indicating that the Xbox account is banned.
        public static final long BANNED = 2148916227L;

        /// Error code indicating that no Xbox account exists for the Microsoft account.
        public static final long MISSING_XBOX_ACCOUNT = 2148916233L;

        /// Error code indicating that Xbox is unavailable in the account country.
        public static final long COUNTRY_UNAVAILABLE = 2148916235L;

        /// Error code indicating that a child account must join a family.
        public static final long ADD_FAMILY = 2148916238L;
    }

    /// Reports the legacy HTTP 400 response emitted by Xbox authentication.
    @NotNullByDefault
    public final static class XBox400Exception extends AuthenticationException {
    }

    /// Reports that an owned Minecraft Java Edition account has no created profile.
    @NotNullByDefault
    public final static class MinecraftJavaEditionProfileNotFoundException extends AuthenticationException {
    }

    /// Reports that the Microsoft account does not own Minecraft Java Edition.
    @NotNullByDefault
    public final static class MinecraftJavaEditionLicenseNotFoundException extends AuthenticationException {
    }

    /// Reports that an Xbox response has no usable `xui` claim.
    @NotNullByDefault
    public final static class NoXuiException extends AuthenticationException {
    }

    /// Models the `DisplayClaims` object returned by Xbox authentication.
    @NotNullByDefault
    private final static class XBoxLiveAuthenticationResponseDisplayClaims {
        /// Xbox user identity claim maps, or `null` when omitted by the service.
        @Nullable List<@Nullable Map<@Nullable Object, @Nullable Object>> xui;
    }

    /// Models fields shared by Xbox success and error responses.
    @NotNullByDefault
    private static class MicrosoftErrorResponse {
        /// Numeric Xbox error code; zero denotes success.
        @SerializedName("XErr")
        long errorCode;

        /// Optional human-readable service message.
        @SerializedName("Message")
        @Nullable String message;

        /// Optional URL where the user can resolve the authorization problem.
        @SerializedName("Redirect")
        @Nullable String redirectUrl;
    }

    /// Models an Xbox Live or XSTS authentication response.
    ///
    /// Success responses carry issue time, expiry time, token, and user identity claims. Error responses inherit the
    /// Xbox error code, message, and remediation URL from [MicrosoftErrorResponse].
    @NotNullByDefault
    private final static class XBoxLiveAuthenticationResponse extends MicrosoftErrorResponse {
        /// Optional ISO-8601 token issue time returned by Xbox.
        @SerializedName("IssueInstant")
        @Nullable String issueInstant;

        /// Optional ISO-8601 token expiry time returned by Xbox.
        @SerializedName("NotAfter")
        @Nullable String notAfter;

        /// Optional Xbox or XSTS token.
        @SerializedName("Token")
        @Nullable String token;

        /// Optional Xbox user identity claims.
        @SerializedName("DisplayClaims")
        @Nullable XBoxLiveAuthenticationResponseDisplayClaims displayClaims;
    }

    /// Models the Minecraft Services `login_with_xbox` response.
    @NotNullByDefault
    private final static class MinecraftLoginWithXBoxResponse {
        /// Optional service-side user identifier.
        @SerializedName("username")
        @Nullable String username;

        /// Optional roles returned for the account.
        @SerializedName("roles")
        @Nullable List<@Nullable String> roles;

        /// Optional Minecraft Services access token.
        @SerializedName("access_token")
        @Nullable String accessToken;

        /// Optional authorization token type.
        @SerializedName("token_type")
        @Nullable String tokenType;

        /// Access-token lifetime in seconds.
        @SerializedName("expires_in")
        int expiresIn;
    }

    /// Models one legacy Minecraft Store entitlement entry.
    @NotNullByDefault
    private final static class MinecraftStoreResponseItem {
        /// Optional entitlement name.
        @SerializedName("name")
        @Nullable String name;

        /// Optional entitlement signature.
        @SerializedName("signature")
        @Nullable String signature;
    }

    /// Models the legacy Minecraft Store entitlement response.
    @NotNullByDefault
    private final static class MinecraftStoreResponse extends MinecraftErrorResponse {
        /// Optional entitlement entries.
        @SerializedName("items")
        @Nullable List<@Nullable MinecraftStoreResponseItem> items;

        /// Optional response signature.
        @SerializedName("signature")
        @Nullable String signature;

        /// Optional signing key ID.
        @SerializedName("keyId")
        @Nullable String keyId;
    }

    /// Models and validates one skin entry from a Minecraft profile response.
    @NotNullByDefault
    public final static class MinecraftProfileResponseSkin implements Validation {
        /// Skin entry ID, populated by validated responses.
        public @Nullable String id;

        /// Skin state, populated by validated responses.
        public @Nullable String state;

        /// Skin image URL, populated by validated responses.
        public @Nullable String url;

        /// Player-model variant (`CLASSIC` or `SLIM`), populated by validated responses.
        public @Nullable String variant;

        /// Optional skin alias.
        public @Nullable String alias;

        /// Verifies that all required skin fields are present.
        ///
        /// @throws JsonParseException if a required field is absent
        /// @throws TolerableValidationException if validation reports a recoverable problem
        @Override
        public void validate() throws JsonParseException, TolerableValidationException {
            Validation.requireNonNull(id, "id cannot be null");
            Validation.requireNonNull(state, "state cannot be null");
            Validation.requireNonNull(url, "url cannot be null");
            Validation.requireNonNull(variant, "variant cannot be null");
        }
    }

    /// Reserved model for cape entries in Minecraft profile responses.
    @NotNullByDefault
    public static class MinecraftProfileResponseCape {

    }

    /// Models the Minecraft entitlement-license response.
    ///
    /// @param items optional entitlement items
    /// @param signature optional response signature
    /// @param keyId optional signing key ID
    @JsonSerializable
    @NotNullByDefault
    public record MinecraftLicense(
            @SerializedName("items") @Nullable List<@Nullable MinecraftLicenseItem> items,
            @SerializedName("signature") @Nullable String signature,
            @SerializedName("keyId") @Nullable String keyId
    ) {
    }

    /// Models one entitlement item in a Minecraft license response.
    ///
    /// @param name optional entitlement name
    /// @param signature optional entitlement signature
    @JsonSerializable
    @NotNullByDefault
    public record MinecraftLicenseItem(
            @SerializedName("name") @Nullable String name,
            @SerializedName("signature") @Nullable String signature
    ) {
    }

    /// Models and validates the Minecraft Services profile response.
    @NotNullByDefault
    public static class MinecraftProfileResponse extends MinecraftErrorResponse implements Validation {
        /// Profile UUID, populated by validated responses.
        @SerializedName("id")
        @JsonAdapter(UnhyphenatedUUIDTypeAdapter.class)
        @Nullable UUID id;

        /// Profile name, populated by validated responses.
        @SerializedName("name")
        @Nullable String name;

        /// Skin entries, populated by validated responses.
        @SerializedName("skins")
        @Nullable List<@Nullable MinecraftProfileResponseSkin> skins;

        /// Cape entries, populated by validated responses.
        @SerializedName("capes")
        @Nullable List<@Nullable MinecraftProfileResponseCape> capes;

        /// Verifies that all required profile fields are present.
        ///
        /// @throws JsonParseException if a required field is absent
        /// @throws TolerableValidationException if validation reports a recoverable problem
        @Override
        public void validate() throws JsonParseException, TolerableValidationException {
            Validation.requireNonNull(id, "id cannot be null");
            Validation.requireNonNull(name, "name cannot be null");
            Validation.requireNonNull(skins, "skins cannot be null");
            Validation.requireNonNull(capes, "capes cannot be null");
        }
    }

    /// Models an error payload returned by Minecraft Services.
    @NotNullByDefault
    private static class MinecraftErrorResponse {
        /// Optional request path associated with the error.
        public @Nullable String path;

        /// Optional service error type.
        public @Nullable String errorType;

        /// Optional service error name.
        public @Nullable String error;

        /// Optional user-facing error message.
        public @Nullable String errorMessage;

        /// Optional developer-oriented error detail.
        public @Nullable String developerMessage;
    }

    /// Gson instance that validates response types implementing [Validation].
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapterFactory(ValidationTypeAdapterFactory.INSTANCE)
            .create();

}

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
package space.minecraftstl.xyml.auth.authlibinjector;

import static java.util.Collections.emptyMap;
import static space.minecraftstl.xyml.util.Lang.tryCast;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.glavo.url.WebURL;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.auth.yggdrasil.YggdrasilService;
import space.minecraftstl.xyml.observable.property.ObservableValue;
import space.minecraftstl.xyml.observable.property.SimpleLongProperty;
import space.minecraftstl.xyml.util.io.HttpRequest;
import space.minecraftstl.xyml.util.io.NetworkUtils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.annotations.JsonAdapter;

@JsonAdapter(AuthlibInjectorServer.Deserializer.class)
@NotNullByDefault
/// Discovered authlib-injector endpoint and its cached server metadata.
///
/// Metadata refreshes publish a monotonic toolkit-neutral change signal after a successful parse.
public class AuthlibInjectorServer {

    /// JSON parser used for authlib-injector metadata responses.
    private static final Gson GSON = new GsonBuilder().create();

    /// Resolves an authlib-injector endpoint and immediately fetches its metadata.
    ///
    /// The endpoint advertised through `x-authlib-injector-api-location` takes precedence over
    /// the supplied address. The resulting URL always ends with a slash.
    ///
    /// @param url user-supplied authlib-injector endpoint
    /// @return resolved server with freshly loaded metadata
    /// @throws IOException when endpoint resolution, transport, or metadata parsing fails
    public static AuthlibInjectorServer locateServer(String url) throws IOException {
        try {
            url = NetworkUtils.addHttpsIfMissing(url);

            WebURL webURL = WebURL.parseBrowserInput(url);
            url = webURL.toString();

            HttpURLConnection conn = NetworkUtils.createHttpConnection(webURL);
            conn = NetworkUtils.resolveConnection(conn);

            @Nullable String ali = conn.getHeaderField("x-authlib-injector-api-location");
            if (ali != null) {
                WebURL absoluteAli = WebURL.parse(ali, WebURL.of(conn.getURL()));
                if (!urlEqualsIgnoreSlash(webURL.toString(), absoluteAli.toString())) {
                    conn.disconnect();
                    url = absoluteAli.toString();
                    conn = NetworkUtils.resolveConnection(NetworkUtils.createHttpConnection(absoluteAli));
                }
            }

            if (!url.endsWith("/"))
                url += "/";

            try {
                AuthlibInjectorServer server = new AuthlibInjectorServer(url);
                server.refreshMetadata(NetworkUtils.readFullyAsString(conn));
                return server;
            } finally {
                conn.disconnect();
            }
        } catch (IllegalArgumentException e) {
            throw new IOException(e);
        }
    }

    /// Compares two endpoint URLs while ignoring one optional trailing slash.
    ///
    /// @param a first endpoint URL
    /// @param b second endpoint URL
    /// @return whether both URLs identify the same normalized text address
    private static boolean urlEqualsIgnoreSlash(String a, String b) {
        if (!a.endsWith("/"))
            a += "/";
        if (!b.endsWith("/"))
            b += "/";
        return a.equals(b);
    }

    /// Normalized authlib-injector endpoint, including its trailing slash.
    private final String url;

    /// Last successfully parsed raw metadata response, or null before metadata is loaded.
    @Nullable
    private transient String metadataResponse;

    /// Epoch-millisecond timestamp assigned to the cached metadata response.
    private transient long metadataTimestamp;

    /// Server display name from metadata, or null when the server does not provide one.
    @Nullable
    private transient String name;

    /// Immutable metadata-provided server links keyed by link purpose.
    private transient @Unmodifiable Map<String, String> links = emptyMap();

    /// Whether the server accepts account names that are not email addresses.
    private transient boolean nonEmailLogin;

    /// Whether the cached response was fetched during the current object lifetime.
    private transient boolean metadataRefreshed;

    /// Yggdrasil protocol service bound to this authlib-injector endpoint.
    private final transient YggdrasilService yggdrasilService;

    /// Monotonic metadata-change signal.
    private final transient SimpleLongProperty metadataRevision =
            new SimpleLongProperty(this, "metadataRevision", 0L);

    /// Serializes metadata revision publication order.
    private final transient Object metadataRevisionLock = new Object();

    /// Creates an unresolved server descriptor for a normalized endpoint.
    ///
    /// This constructor does not fetch metadata; callers may restore a cache or refresh explicitly.
    ///
    /// @param url endpoint used by metadata and Yggdrasil requests
    public AuthlibInjectorServer(String url) {
        this.url = url;
        this.yggdrasilService = new YggdrasilService(new AuthlibInjectorProvider(url));
    }

    /// Returns the configured authlib-injector endpoint.
    ///
    /// @return endpoint URL
    public String getUrl() {
        return url;
    }

    /// Returns the Yggdrasil service configured for this endpoint.
    ///
    /// @return endpoint-specific Yggdrasil service
    public YggdrasilService getYggdrasilService() {
        return yggdrasilService;
    }

    /// Returns the cached raw metadata response when one has been parsed.
    ///
    /// @return optional raw metadata response
    public Optional<String> getMetadataResponse() {
        return Optional.ofNullable(metadataResponse);
    }

    /// Returns the timestamp associated with the cached metadata response.
    ///
    /// @return epoch milliseconds, or zero before metadata is loaded
    public long getMetadataTimestamp() {
        return metadataTimestamp;
    }

    /// Returns the metadata display name, falling back to the endpoint URL.
    ///
    /// @return server display label
    public String getName() {
        return Optional.ofNullable(name)
                .orElse(url);
    }

    /// Returns immutable links advertised by the server metadata.
    ///
    /// @return immutable link map
    public @Unmodifiable Map<String, String> getLinks() {
        return links;
    }

    /// Reports whether the server supports non-email login identifiers.
    ///
    /// @return metadata feature flag
    public boolean isNonEmailLogin() {
        return nonEmailLogin;
    }

    /// Returns a current metadata response, refreshing it when no fresh response is available.
    ///
    /// @return raw metadata response
    /// @throws IOException when refreshing or parsing metadata fails
    public String fetchMetadataResponse() throws IOException {
        if (metadataResponse == null || !metadataRefreshed) {
            refreshMetadata();
        }
        return getMetadataResponse().get();
    }

    /// Fetches and publishes the current endpoint metadata.
    ///
    /// @throws IOException when transport or metadata parsing fails
    public void refreshMetadata() throws IOException {
        refreshMetadata(HttpRequest.GET(url).getString());
    }

    /// Parses freshly fetched metadata and publishes the resulting state change.
    ///
    /// @param text raw metadata response
    /// @throws IOException when the response is malformed
    private void refreshMetadata(String text) throws IOException {
        long timestamp = System.currentTimeMillis();
        try {
            setMetadataResponse(text, timestamp);
        } catch (JsonParseException e) {
            throw new IOException("Malformed response\n" + text, e);
        }

        metadataRefreshed = true;
        LOG.info("authlib-injector server metadata refreshed: " + url);
        publishMetadataChange();
    }

    /// Parses metadata and atomically replaces the cached fields without publishing a change.
    ///
    /// @param metadataResponse raw metadata response
    /// @param metadataTimestamp timestamp associated with the response
    /// @throws JsonParseException when the response is empty or malformed
    private void setMetadataResponse(String metadataResponse, long metadataTimestamp) throws JsonParseException {
        @Nullable JsonObject response = GSON.fromJson(metadataResponse, JsonObject.class);
        if (response == null) {
            throw new JsonParseException("Metadata response is empty");
        }

        synchronized (this) {
            this.metadataResponse = metadataResponse;
            this.metadataTimestamp = metadataTimestamp;

            Optional<JsonObject> metaObject = tryCast(response.get("meta"), JsonObject.class);

            this.name = metaObject.flatMap(meta -> tryCast(meta.get("serverName"), JsonPrimitive.class).map(JsonPrimitive::getAsString))
                    .orElse(null);
            this.links = metaObject.flatMap(meta -> tryCast(meta.get("links"), JsonObject.class))
                    .map(linksObject -> {
                        Map<String, String> converted = new LinkedHashMap<>();
                        linksObject.entrySet().forEach(
                                entry -> tryCast(entry.getValue(), JsonPrimitive.class).ifPresent(element -> {
                                    converted.put(entry.getKey(), element.getAsString());
                                }));
                        return Map.copyOf(converted);
                    })
                    .orElse(emptyMap());
            this.nonEmailLogin = metaObject.flatMap(meta -> tryCast(meta.get("feature.non_email_login"), JsonPrimitive.class))
                    .map(it -> it.getAsBoolean())
                    .orElse(false);
        }
    }

    /// Restores a cached metadata response without marking it as freshly fetched.
    ///
    /// @param metadataResponse cached raw metadata response
    /// @param metadataTimestamp timestamp associated with the cached response
    /// @throws JsonParseException when the cached response is empty or malformed
    public void restoreMetadataCache(String metadataResponse, long metadataTimestamp) throws JsonParseException {
        setMetadataResponse(metadataResponse, metadataTimestamp);
    }

    /// Marks restored or fetched metadata as stale so the next fetch request refreshes it.
    public void invalidateMetadataCache() {
        metadataRefreshed = false;
    }

    /// Computes endpoint-based identity hash code.
    ///
    /// @return endpoint URL hash code
    @Override
    public int hashCode() {
        return url.hashCode();
    }

    /// Compares servers by their normalized endpoint URLs.
    ///
    /// @param obj comparison target, or null
    /// @return whether the target is a server with the same endpoint URL
    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj == this)
            return true;
        if (!(obj instanceof AuthlibInjectorServer))
            return false;
        AuthlibInjectorServer another = (AuthlibInjectorServer) obj;
        return this.url.equals(another.url);
    }

    /// Formats the endpoint and optional metadata display name.
    ///
    /// @return endpoint-only or endpoint-and-name representation
    @Override
    public String toString() {
        return name == null ? url : url + " (" + name + ")";
    }

    /// Returns the monotonic metadata-change signal.
    ///
    /// @return toolkit-neutral metadata revision
    public ObservableValue<Long> changes() {
        return metadataRevision;
    }

    /// Publishes one metadata change synchronously on the mutating thread.
    private void publishMetadataChange() {
        synchronized (metadataRevisionLock) {
            metadataRevision.set(metadataRevision.get() + 1L);
        }
    }

    /// Gson adapter that reconstructs a server descriptor from its persisted endpoint URL.
    @NotNullByDefault
    public static class Deserializer implements JsonDeserializer<AuthlibInjectorServer> {
        /// Deserializes an unresolved authlib-injector server descriptor.
        ///
        /// @param json persisted server object
        /// @param type requested target type
        /// @param ctx Gson deserialization context
        /// @return server descriptor configured with the persisted URL
        /// @throws JsonParseException when the persisted object or URL property is invalid
        @Override
        public AuthlibInjectorServer deserialize(JsonElement json, Type type, JsonDeserializationContext ctx) throws JsonParseException {
            JsonObject jsonObj = json.getAsJsonObject();
            return new AuthlibInjectorServer(jsonObj.get("url").getAsString());
        }

    }
}

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
package space.minecraftstl.xyml.game;

import fi.iki.elonen.NanoHTTPD;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.auth.AuthenticationException;
import space.minecraftstl.xyml.auth.OAuth;
import space.minecraftstl.xyml.event.Event;
import space.minecraftstl.xyml.event.EventManager;
import space.minecraftstl.xyml.util.StringUtils;
import space.minecraftstl.xyml.util.io.IOUtils;
import space.minecraftstl.xyml.util.io.JarUtils;
import space.minecraftstl.xyml.util.io.NetworkUtils;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static space.minecraftstl.xyml.util.Lang.mapOf;
import static space.minecraftstl.xyml.util.Lang.thread;
import static space.minecraftstl.xyml.util.i18n.I18n.i18n;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Hosts the loopback OAuth authorization-code callback and publishes Microsoft login browser events.
@NotNullByDefault
public final class OAuthServer extends NanoHTTPD implements OAuth.Session {
    /// Loopback ports attempted in order when opening an authorization-code callback server.
    private static final @Unmodifiable List<Integer> CALLBACK_PORTS = List.of(
            29111, 29112, 29113, 29114, 29115);

    /// Toolkit-neutral light palette used by the short-lived browser callback page.
    private static final String CALLBACK_STYLE = """
            :root {
              --surface: #f8f9ff;
              --surface-container-high: #e9eaf2;
              --on-surface: #1b1b1f;
              --on-surface-variant: #46464f;
              --primary: #465d91;
            }
            """;

    /// Bound loopback port used to construct the redirect URI.
    private final int port;

    /// Authorization-code result completed by the callback request or server closure.
    private final CompletableFuture<String> future = new CompletableFuture<>();

    /// PKCE verifier paired with the challenge sent to Microsoft.
    private final String codeVerifier;

    /// Per-session OAuth state used to reject forged callbacks.
    private final String state;

    /// Most recent browser authorization URL, or `null` before the first browser request.
    public static @Nullable String lastlyOpenedURL;

    /// Optional OpenID Connect token returned alongside the authorization code.
    private @Nullable String idToken;

    /// Creates one unstarted callback server for a concrete loopback port.
    ///
    /// @param port loopback TCP port to bind
    private OAuthServer(int port) {
        super(port);

        this.port = port;

        var encoder = Base64.getUrlEncoder().withoutPadding();
        var random = new SecureRandom();

        {
            // https://datatracker.ietf.org/doc/html/rfc6749#section-4.1.1
            // https://datatracker.ietf.org/doc/html/rfc6749#section-10.12
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            this.state = encoder.encodeToString(bytes);
        }

        {
            // https://datatracker.ietf.org/doc/html/rfc7636#section-4.1
            byte[] bytes = new byte[64];
            random.nextBytes(bytes);
            this.codeVerifier = encoder.encodeToString(bytes);
        }
    }

    /// Returns the PKCE verifier generated for this session.
    ///
    /// @return URL-safe PKCE verifier
    @Override
    public String getCodeVerifier() {
        return codeVerifier;
    }

    /// Returns the anti-forgery state generated for this session.
    ///
    /// @return URL-safe OAuth state
    @Override
    public String getState() {
        return state;
    }

    /// Returns the loopback redirect URI accepted by this server.
    ///
    /// @return absolute authorization callback URI
    @Override
    public String getRedirectURI() {
        return String.format("http://localhost:%d/auth-response", port);
    }

    /// Waits for the callback to provide an authorization code.
    ///
    /// @return authorization code
    /// @throws InterruptedException when the waiting thread is interrupted
    /// @throws ExecutionException when callback validation or server closure fails
    @Override
    public String waitFor() throws InterruptedException, ExecutionException {
        return future.get();
    }

    /// Returns the optional ID token received with the callback.
    ///
    /// @return ID token, or `null` when Microsoft omitted it
    @Override
    public @Nullable String getIdToken() {
        return idToken;
    }

    /// Handles the authorization response and returns a localized close-this-page document.
    ///
    /// @param session incoming loopback HTTP session
    /// @return HTTP response for the callback request
    @Override
    public Response serve(IHTTPSession session) {
        if (!"/auth-response".equals(session.getUri())) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_HTML, "");
        }

        if (session.getMethod() == Method.POST) {
            Map<String, String> files = new HashMap<>();
            try {
                session.parseBody(files);
            } catch (IOException e) {
                LOG.warning("Failed to read post data", e);
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_HTML, "");
            } catch (ResponseException re) {
                return newFixedLengthResponse(re.getStatus(), MIME_PLAINTEXT, re.getMessage());
            }
        } else if (session.getMethod() == Method.GET) {
            // do nothing
        } else {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_HTML, "");
        }
        @Nullable String parameters = session.getQueryParameterString();

        Map<String, String> query = mapOf(NetworkUtils.parseQuery(parameters));

        @Nullable String code = query.get("code");
        if (code != null) {
            if (this.state.equals(query.get("state"))) {
                idToken = query.get("id_token");
                future.complete(code);
            } else if (query.containsKey("state")) {
                LOG.warning("Failed to authenticate: invalid state in parameters");
                future.completeExceptionally(new AuthenticationException("Failed to authenticate: invalid state"));
            } else {
                LOG.warning("Failed to authenticate: missing state in parameters");
                future.completeExceptionally(new AuthenticationException("Failed to authenticate: missing state"));
            }
        } else {
            LOG.warning("Failed to authenticate: missing authorization code in parameters");
            future.completeExceptionally(new AuthenticationException("Failed to authenticate: missing authorization code"));
        }

        String html;
        try {
            html = IOUtils.readFullyAsString(OAuthServer.class.getResourceAsStream("/assets/microsoft_auth.html"))
                    .replace("%style%", CALLBACK_STYLE)
                    .replace("%lang%", Locale.getDefault().toLanguageTag())
                    .replace("%success%", i18n("message.success"))
                    .replace("%ok%", i18n("button.ok"))
                    .replace("%close_page%", i18n("account.methods.microsoft.close_page"));
        } catch (IOException e) {
            LOG.error("Failed to load html", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_HTML, "");
        }
        thread(() -> {
            try {
                Thread.sleep(1000);
                stop();
            } catch (InterruptedException e) {
                LOG.error("Failed to sleep for 1 second");
            }
        });
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=UTF-8", html);
    }

    /// Fails an unfinished callback and stops the loopback server.
    @Override
    public void close() {
        if (!future.isDone())
            future.completeExceptionally(new AuthenticationException("OAuth server is closing"));
        stop();
    }

    /// Creates callback sessions and forwards device-code and browser events to launcher listeners.
    @NotNullByDefault
    public static class Factory implements OAuth.Callback {
        /// Event fired after Microsoft grants a user-facing device code.
        public final EventManager<GrantDeviceCodeEvent> onGrantDeviceCode = new EventManager<>();

        /// Event fired after device-code authentication completes.
        public final EventManager<LoginCompletedDeviceCodeEvent> onLoginCompletedDeviceCode = new EventManager<>();

        /// Browser request event for the authorization-code flow.
        public final EventManager<OpenBrowserEvent> onOpenBrowserAuthorizationCode = new EventManager<>();

        /// Browser request event for the device-code flow.
        public final EventManager<OpenBrowserEvent> onOpenBrowserDevice = new EventManager<>();

        /// Binds the first available configured loopback callback port.
        ///
        /// @return started OAuth callback session
        /// @throws IOException when every callback port fails to bind
        /// @throws AuthenticationException when Microsoft authentication is not configured
        @Override
        public OAuth.Session startServer() throws IOException, AuthenticationException {
            if (StringUtils.isBlank(getClientId())) {
                throw new MicrosoftAuthenticationNotSupportedException();
            }

            @Nullable IOException exception = null;
            for (int port : CALLBACK_PORTS) {
                try {
                    OAuthServer server = new OAuthServer(port);
                    server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
                    return server;
                } catch (IOException e) {
                    exception = e;
                }
            }
            throw Objects.requireNonNull(exception, "No callback port attempt produced a result");
        }

        /// Publishes a newly granted device code.
        ///
        /// @param userCode code displayed to the user
        /// @param verificationURI page where the user enters the code
        @Override
        public void grantDeviceCode(String userCode, String verificationURI) {
            onGrantDeviceCode.fireEvent(new GrantDeviceCodeEvent(this, userCode, verificationURI));
        }

        /// Publishes successful device-code authentication.
        @Override
        public void loginCompletedDeviceCode() {
            onLoginCompletedDeviceCode.fireEvent(new LoginCompletedDeviceCodeEvent(this));
        }

        /// Publishes a browser URL for the selected OAuth grant flow.
        ///
        /// @param grantFlow active OAuth grant flow
        /// @param url absolute authorization URL
        /// @throws IOException retained for the callback interface contract
        @Override
        public void openBrowser(OAuth.GrantFlow grantFlow, String url) throws IOException {
            lastlyOpenedURL = url;

            switch (grantFlow) {
                case AUTHORIZATION_CODE -> onOpenBrowserAuthorizationCode.fireEvent(new OpenBrowserEvent(this, url));
                case DEVICE -> onOpenBrowserDevice.fireEvent(new OpenBrowserEvent(this, url));
            }
        }

        /// Returns the configured Microsoft OAuth client ID.
        ///
        /// @return configured client ID, or an empty string when unavailable
        @Override
        public String getClientId() {
            return System.getProperty("xyml.microsoft.auth.id",
                    JarUtils.getAttribute("xyml.microsoft.auth.id", ""));
        }
    }

    /// Device-code grant event carrying the user code and verification page.
    @NotNullByDefault
    public static class GrantDeviceCodeEvent extends Event {
        /// Short code displayed to the user.
        private final String userCode;

        /// Browser page where the user enters the code.
        private final String verificationUri;

        /// Creates a device-code grant event.
        ///
        /// @param source event publisher
        /// @param userCode short user code
        /// @param verificationUri browser verification page
        public GrantDeviceCodeEvent(Object source, String userCode, String verificationUri) {
            super(source);
            this.userCode = userCode;
            this.verificationUri = verificationUri;
        }

        /// Returns the short code displayed to the user.
        ///
        /// @return device user code
        public String getUserCode() {
            return userCode;
        }

        /// Returns the browser page where the code must be entered.
        ///
        /// @return verification page URI text
        public String getVerificationUri() {
            return verificationUri;
        }
    }

    /// Event indicating that device-code login completed successfully.
    @NotNullByDefault
    public static class LoginCompletedDeviceCodeEvent extends Event {
        /// Creates a completed-login event.
        ///
        /// @param source event publisher
        public LoginCompletedDeviceCodeEvent(Object source) {
            super(source);
        }
    }

    /// Browser-open event carrying one absolute authorization URL.
    @NotNullByDefault
    public static class OpenBrowserEvent extends Event {
        /// Absolute authorization URL.
        private final String url;

        /// Creates a browser-open event.
        ///
        /// @param source event publisher
        /// @param url absolute authorization URL
        public OpenBrowserEvent(Object source, String url) {
            super(source);
            this.url = url;
        }

        /// Returns the authorization URL.
        ///
        /// @return absolute browser URL
        public String getUrl() {
            return url;
        }
    }

    /// Signals that the launcher has no usable Microsoft OAuth client ID.
    @NotNullByDefault
    public static class MicrosoftAuthenticationNotSupportedException extends AuthenticationException {
    }
}

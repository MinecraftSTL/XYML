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

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.glavo.uuid.UUIDs;
import space.minecraftstl.xyml.auth.Account;
import space.minecraftstl.xyml.auth.AccountID;
import space.minecraftstl.xyml.auth.AuthInfo;
import space.minecraftstl.xyml.auth.AuthenticationException;
import space.minecraftstl.xyml.auth.authlibinjector.AuthlibInjectorArtifactInfo;
import space.minecraftstl.xyml.auth.authlibinjector.AuthlibInjectorArtifactProvider;
import space.minecraftstl.xyml.auth.authlibinjector.AuthlibInjectorDownloadException;
import space.minecraftstl.xyml.game.Arguments;
import space.minecraftstl.xyml.game.LaunchOptions;
import space.minecraftstl.xyml.util.StringUtils;
import space.minecraftstl.xyml.util.ToStringBuilder;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static java.util.Objects.requireNonNull;

/// Offline Minecraft account with optional authlib-injector skin delivery.
///
/// The base login remains offline. A non-default skin additionally starts a loopback Yggdrasil
/// service for daemon launches and supplies authlib-injector as a Java agent.
@NotNullByDefault
public class OfflineAccount extends Account {

    /// Provider used to locate the bundled or cached authlib-injector artifact.
    private final AuthlibInjectorArtifactProvider downloader;

    /// Minecraft profile name exposed to the launched game.
    private final String profileName;

    /// Stable Minecraft profile identifier exposed to the launched game.
    private final UUID profileID;

    /// Optional custom skin configuration.
    @Nullable
    private Skin skin;

    /// Creates an offline account.
    ///
    /// @param accountID persistent launcher account identifier
    /// @param downloader authlib-injector artifact provider
    /// @param profileName nonblank Minecraft profile name
    /// @param profileID Minecraft profile identifier
    /// @param skin optional custom skin configuration
    /// @throws IllegalArgumentException when the profile name is blank
    protected OfflineAccount(
            AccountID accountID,
            AuthlibInjectorArtifactProvider downloader,
            String profileName,
            UUID profileID,
            @Nullable Skin skin) {
        super(accountID);
        this.downloader = requireNonNull(downloader);
        this.profileName = requireNonNull(profileName);
        this.profileID = requireNonNull(profileID);
        this.skin = skin;

        if (StringUtils.isBlank(profileName)) {
            throw new IllegalArgumentException("Profile name cannot be blank");
        }
    }

    /// Returns the authlib-injector artifact provider used by this account.
    ///
    /// @return artifact provider
    public AuthlibInjectorArtifactProvider getDownloader() {
        return downloader;
    }

    /// Returns the Minecraft profile identifier.
    ///
    /// @return profile UUID
    @Override
    public UUID getProfileID() {
        return profileID;
    }

    /// Returns the Minecraft profile name.
    ///
    /// @return profile name
    @Override
    public String getProfileName() {
        return profileName;
    }

    /// Returns the optional custom skin configuration.
    ///
    /// @return skin configuration, or null when the launcher default is used
    public @Nullable Skin getSkin() {
        return skin;
    }

    /// Replaces the skin configuration and invalidates cached account authentication state.
    ///
    /// @param skin new skin configuration, or null to use the launcher default
    public void setSkin(@Nullable Skin skin) {
        this.skin = skin;
        invalidate();
    }

    /// Determines whether the selected skin requires authlib-injector at launch.
    ///
    /// @param skin selected skin configuration, or null
    /// @return whether a non-default skin must be served to the game
    protected boolean loadAuthlibInjector(@Nullable Skin skin) {
        return skin != null && skin.type() != Skin.Type.DEFAULT;
    }

    /// Creates a random-token offline authentication result without skin delivery.
    ///
    /// @return offline authentication result
    /// @throws AuthenticationException retained for the account authentication contract
    public AuthInfo logInWithoutSkin() throws AuthenticationException {
        // The MSA user type avoids invalid-session failures on servers that reject Mojang sessions.
        return new AuthInfo(profileName, profileID, UUIDs.toCompactString(UUID.randomUUID()), AuthInfo.USER_TYPE_MSA, "{}");
    }

    /// Authenticates offline and prepares authlib-injector support for non-default skins.
    ///
    /// Artifact lookup runs asynchronously but this method waits for it before returning.
    ///
    /// @return authentication result, optionally enhanced with skin launch arguments
    /// @throws AuthenticationException when artifact lookup or result construction fails
    @Override
    public AuthInfo logIn() throws AuthenticationException {
        AuthInfo authInfo = logInWithoutSkin();

        if (loadAuthlibInjector(skin)) {
            CompletableFuture<AuthlibInjectorArtifactInfo> artifactTask = CompletableFuture.supplyAsync(() -> {
                try {
                    return downloader.getArtifactInfo();
                } catch (IOException e) {
                    throw new CompletionException(new AuthlibInjectorDownloadException(e));
                }
            });

            AuthlibInjectorArtifactInfo artifact;
            try {
                artifact = artifactTask.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AuthenticationException(e);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof AuthenticationException) {
                    throw (AuthenticationException) e.getCause();
                } else {
                    throw new AuthenticationException(e.getCause());
                }
            }

            try {
                return new OfflineAuthInfo(authInfo, artifact);
            } catch (Exception e) {
                throw new AuthenticationException(e);
            }
        } else {
            return authInfo;
        }
    }

    /// Authentication result that serves one account skin through a loopback Yggdrasil endpoint.
    @NotNullByDefault
    private class OfflineAuthInfo extends AuthInfo {
        /// Authlib-injector Java-agent artifact used by the launched game.
        private final AuthlibInjectorArtifactInfo artifact;

        /// Loopback skin service, or null before daemon launch arguments are requested.
        @Nullable
        private YggdrasilServer server;

        /// Wraps base offline authentication with authlib-injector launch support.
        ///
        /// @param authInfo base offline authentication result
        /// @param artifact authlib-injector Java-agent artifact
        public OfflineAuthInfo(AuthInfo authInfo, AuthlibInjectorArtifactInfo artifact) {
            super(authInfo.getUsername(), authInfo.getUUID(), authInfo.getAccessToken(), USER_TYPE_MSA, authInfo.getUserProperties());

            this.artifact = artifact;
        }

        /// Starts the loopback skin service and returns daemon-only Java-agent arguments.
        ///
        /// @param options effective launch options
        /// @return Java-agent arguments, or null for a non-daemon launch
        /// @throws IOException when the loopback service cannot start or initialize
        @Override
        public @Nullable Arguments getLaunchArguments(LaunchOptions options) throws IOException {
            if (!options.isDaemon()) return null;

            server = new YggdrasilServer(0);
            server.start();

            try {
                server.addCharacter(new YggdrasilServer.Character(profileID, profileName,
                        skin != null ? skin.load(profileName).run() : null));
            } catch (IOException e) {
                // ignore
            } catch (Exception e) {
                throw new IOException(e);
            }

            return new Arguments().addJVMArguments(
                    "-javaagent:" + artifact.location().toString() + "=" + "http://localhost:" + server.getListeningPort(),
                    "-Dauthlibinjector.side=client"
            );
        }

        /// Closes base authentication resources and stops the loopback skin service if started.
        ///
        /// @throws Exception when base cleanup or server shutdown fails
        @Override
        public void close() throws Exception {
            super.close();

            if (server != null)
                server.stop();
        }
    }

    /// Reuses normal offline authentication when the launcher enters offline play mode.
    ///
    /// @return offline authentication result
    /// @throws AuthenticationException when authentication setup fails
    @Override
    public AuthInfo playOffline() throws AuthenticationException {
        return logIn();
    }

    /// Persists profile identity and optional skin configuration into account metadata.
    ///
    /// @param metadata destination account metadata
    @Override
    public void writeMetadata(JsonObject metadata) {
        super.writeMetadata(metadata);
        metadata.addProperty("profileID", profileID.toString());
        metadata.addProperty("profileName", profileName);
        if (skin == null) {
            metadata.add("skin", JsonNull.INSTANCE);
        } else {
            JsonObject skinStorage = new JsonObject();
            skin.writeStorage(skinStorage);
            metadata.add("skin", skinStorage);
        }
    }

    /// Formats the persistent account and profile identity for diagnostics.
    ///
    /// @return diagnostic account representation
    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .append("accountID", getAccountID())
                .append("profileName", profileName)
                .append("profileID", profileID)
                .toString();
    }
}

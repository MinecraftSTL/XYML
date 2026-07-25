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

import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.auth.Account;
import space.minecraftstl.xyml.auth.AccountID;
import space.minecraftstl.xyml.auth.AccountFactory;
import space.minecraftstl.xyml.auth.AuthenticationException;
import space.minecraftstl.xyml.auth.CharacterSelector;
import space.minecraftstl.xyml.auth.yggdrasil.CompleteGameProfile;
import space.minecraftstl.xyml.auth.yggdrasil.GameProfile;
import space.minecraftstl.xyml.auth.yggdrasil.YggdrasilSession;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.observable.cache.ObservableOptionalCache;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/// Creates and restores accounts backed by an authlib-injector-compatible Yggdrasil server.
@NotNullByDefault
public class AuthlibInjectorAccountFactory extends AccountFactory<AuthlibInjectorAccount> {
    /// Supplies the authlib-injector artifact used when launching an account.
    private final AuthlibInjectorArtifactProvider downloader;

    /// Resolves a persisted API root to its configured server definition.
    private final Function<String, AuthlibInjectorServer> serverLookup;

    /// Creates an authlib-injector account factory.
    ///
    /// @param downloader artifact provider used by created accounts
    /// @param serverLookup function that looks up an [AuthlibInjectorServer] by URL
    public AuthlibInjectorAccountFactory(AuthlibInjectorArtifactProvider downloader, Function<String, AuthlibInjectorServer> serverLookup) {
        this.downloader = downloader;
        this.serverLookup = serverLookup;
    }

    /// Returns the credentials required for authlib-injector authentication.
    ///
    /// @return username-and-password login type
    @Override
    public AccountLoginType getLoginType() {
        return AccountLoginType.USERNAME_PASSWORD;
    }

    /// Creates an account for the server supplied as additional data.
    ///
    /// @param selector selects a profile when the credentials expose multiple profiles
    /// @param username account login name
    /// @param password account password
    /// @param progressCallback progress receiver retained for the common factory contract
    /// @param additionalData configured [AuthlibInjectorServer]
    /// @return newly created account
    /// @throws AuthenticationException if account creation cannot authenticate
    /// @throws ClassCastException if `additionalData` is not an [AuthlibInjectorServer]
    @Override
    public AuthlibInjectorAccount create(CharacterSelector selector, String username, String password, ProgressCallback progressCallback, Object additionalData) throws AuthenticationException {
        Objects.requireNonNull(selector);
        Objects.requireNonNull(username);
        Objects.requireNonNull(password);

        AuthlibInjectorServer server = (AuthlibInjectorServer) additionalData;

        return new AuthlibInjectorAccount(server, downloader, username, password, selector);
    }

    /// Restores an account from its public and private JSON objects.
    ///
    /// @param metadata persisted public account metadata
    /// @param privateData persisted credential data
    /// @return restored account
    /// @throws IllegalArgumentException if the persisted server API root is missing
    @Override
    public AuthlibInjectorAccount fromStorage(JsonObject metadata, JsonObject privateData) {
        Objects.requireNonNull(metadata);
        Objects.requireNonNull(privateData);

        @Nullable String apiRoot = JsonUtils.getString(metadata, "serverBaseURL");
        if (apiRoot == null) {
            throw new IllegalArgumentException("storage does not have API root.");
        }
        AuthlibInjectorServer server = serverLookup.apply(apiRoot);
        return fromStorage(metadata, privateData, downloader, server);
    }

    /// Restores an account using an already resolved server and artifact provider.
    ///
    /// @param metadata persisted public account metadata
    /// @param privateData persisted credential data
    /// @param downloader artifact provider used by the restored account
    /// @param server resolved authentication server
    /// @return restored account
    /// @throws IllegalArgumentException if the persisted login name is missing
    static AuthlibInjectorAccount fromStorage(
            JsonObject metadata,
            JsonObject privateData,
            AuthlibInjectorArtifactProvider downloader,
            AuthlibInjectorServer server) {
        AccountID accountID = Account.readAccountID(metadata);
        YggdrasilSession session = YggdrasilSession.fromStorage(metadata, privateData);

        @Nullable String loginName = JsonUtils.getString(metadata, "loginName");
        if (loginName == null) {
            throw new IllegalArgumentException("storage does not have loginName");
        }

        if (privateData.get("profileProperties") instanceof JsonObject profilePropertiesObject) {
            @Nullable Map<String, @Nullable String> properties = JsonUtils.GSON.fromJson(
                    profilePropertiesObject,
                    JsonUtils.mapTypeOf(String.class, String.class));
            GameProfile selected = session.getSelectedProfile();
            ObservableOptionalCache<UUID, CompleteGameProfile, AuthenticationException> profileRepository =
                    server.getYggdrasilService().getProfileRepository();
            profileRepository.put(selected.getId(), new CompleteGameProfile(selected, properties));
            profileRepository.invalidate(selected.getId());
        }

        return new AuthlibInjectorAccount(accountID, server, downloader, loginName, session);
    }
}

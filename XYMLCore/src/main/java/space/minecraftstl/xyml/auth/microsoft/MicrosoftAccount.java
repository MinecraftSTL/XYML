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
package space.minecraftstl.xyml.auth.microsoft;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.auth.*;
import space.minecraftstl.xyml.auth.yggdrasil.Texture;
import space.minecraftstl.xyml.auth.yggdrasil.TextureType;
import space.minecraftstl.xyml.auth.yggdrasil.YggdrasilService;
import space.minecraftstl.xyml.observable.property.MappedObservableValue;
import space.minecraftstl.xyml.observable.property.ObservableValue;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static java.util.Objects.requireNonNull;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Microsoft account backed by Minecraft services and toolkit-neutral profile observation.
@NotNullByDefault
public final class MicrosoftAccount extends OAuthAccount {

    /// Microsoft authentication service used by this account.
    protected final MicrosoftService service;

    /// Stable profile ID used to reject account changes during reauthentication.
    protected UUID profileID;

    /// Whether the current session has passed local or remote validation.
    private boolean authenticated = false;

    /// Current Microsoft and Minecraft Services session.
    private MicrosoftSession session;

    /// Restores a persisted Microsoft account.
    ///
    /// @param accountID stable account entry ID
    /// @param service Microsoft authentication service
    /// @param session persisted session
    protected MicrosoftAccount(AccountID accountID, MicrosoftService service, MicrosoftSession session) {
        super(accountID);
        this.service = requireNonNull(service);
        this.session = requireNonNull(session);
        this.profileID = requireNonNull(session.profile().id());
    }

    /// Creates and authenticates a new Microsoft account.
    ///
    /// @param service Microsoft authentication service
    /// @param flow OAuth grant flow to use
    /// @throws AuthenticationException if Microsoft or Minecraft authentication fails
    protected MicrosoftAccount(MicrosoftService service, OAuth.GrantFlow flow) throws AuthenticationException {
        super(AccountID.generate());
        this.service = requireNonNull(service);

        MicrosoftSession acquiredSession = service.authenticate(flow);
        if (acquiredSession.profile() == null) {
            session = service.refresh(acquiredSession);
        } else {
            session = acquiredSession;
        }

        profileID = session.profile().id();
        authenticated = true;
    }

    /// Returns the selected Minecraft profile name.
    ///
    /// @return selected profile name
    @Override
    public String getProfileName() {
        return session.profile().name();
    }

    /// Returns the selected Minecraft profile ID.
    ///
    /// @return selected profile ID
    @Override
    public UUID getProfileID() {
        return session.profile().id();
    }

    /// Logs in with the stored session, validating or refreshing it when necessary.
    ///
    /// @return authenticated launch information
    /// @throws AuthenticationException if session validation or refresh fails
    @Override
    public AuthInfo logIn() throws AuthenticationException {
        if (!authenticated || !session.hasProfileName() || System.currentTimeMillis() > session.notAfter()) {
            if (session.hasProfileName()
                    && service.validate(session.notAfter(), session.tokenType(), session.accessToken())) {
                authenticated = true;
            } else {
                MicrosoftSession acquiredSession = service.refresh(session);
                if (!Objects.equals(acquiredSession.profile().id(), session.profile().id())) {
                    throw new ServerResponseMalformedException("Selected profile changed");
                }
                if (!acquiredSession.hasProfileName()) {
                    throw new ServerResponseMalformedException("Profile name is missing");
                }

                session = acquiredSession;

                authenticated = true;
                invalidate();
            }
        }

        return session.toAuthInfo();
    }

    /// Reauthenticates interactively after the stored credentials expire.
    ///
    /// @return authenticated launch information
    /// @throws AuthenticationException if reauthentication fails or selects another profile
    @Override
    public AuthInfo logInWhenCredentialsExpired() throws AuthenticationException {
        MicrosoftSession acquiredSession = service.authenticate(OAuth.GrantFlow.DEVICE);
        if (!Objects.equals(profileID, acquiredSession.profile().id())) {
            throw new WrongAccountException(profileID, acquiredSession.profile().id());
        }

        if (acquiredSession.profile() == null) {
            session = service.refresh(acquiredSession);
        } else {
            session = acquiredSession;
        }

        authenticated = true;
        invalidate();
        return session.toAuthInfo();
    }

    /// Builds launch information from the cached session without contacting the service.
    ///
    /// @return cached launch information
    /// @throws AuthenticationException if the cached profile name is unavailable
    @Override
    public AuthInfo playOffline() throws AuthenticationException {
        if (!session.hasProfileName()) {
            throw new CredentialExpiredException("Profile name is missing");
        }

        return session.toAuthInfo();
    }

    /// Reports that Microsoft accounts support skin uploads.
    ///
    /// @return always `true`
    @Override
    public boolean canUploadSkin() {
        return true;
    }

    /// Uploads a skin through Minecraft Services.
    ///
    /// @param isSlim whether the skin uses the slim player model
    /// @param file skin image file
    /// @throws AuthenticationException if authentication or upload fails
    /// @throws UnsupportedOperationException if the runtime cannot perform the upload
    @Override
    public void uploadSkin(boolean isSlim, Path file) throws AuthenticationException, UnsupportedOperationException {
        service.uploadSkin(session.accessToken(), isSlim, file);
    }

    /// Writes public profile identity data into account metadata.
    ///
    /// @param metadata mutable metadata destination
    @Override
    public void writeMetadata(JsonObject metadata) {
        super.writeMetadata(metadata);
        metadata.addProperty("profileID", getProfileID().toString());
    }

    /// Writes the current private session data into account storage.
    ///
    /// @param privateData mutable private-data destination
    @Override
    public void writePrivateData(JsonObject privateData) {
        super.writePrivateData(privateData);
        session.writePrivateData(privateData);
    }

    /// Returns the Microsoft authentication service used by this account.
    ///
    /// @return Microsoft authentication service
    public MicrosoftService getService() {
        return service;
    }

    /// Returns observable skin and cape textures from the cached complete profile.
    ///
    /// Malformed texture payloads are logged and exposed as an empty optional.
    ///
    /// @return observable optional texture map
    @Override
    public ObservableValue<Optional<Map<TextureType, Texture>>> getTextures() {
        return MappedObservableValue.map(
                service.getProfileRepository().binding(getProfileID()),
                profile -> profile == null ? Optional.empty() : profile.flatMap(it -> {
                    try {
                        return YggdrasilService.getTextures(it);
                    } catch (ServerResponseMalformedException e) {
                        LOG.warning("Failed to parse texture payload", e);
                        return Optional.empty();
                    }
                }));
    }

    /// Invalidates authentication and cached complete-profile data.
    @Override
    public void clearCache() {
        authenticated = false;
        service.getProfileRepository().invalidate(profileID);
    }

    /// Returns a diagnostic account description without credentials.
    ///
    /// @return account ID, profile ID, and profile name
    @Override
    public String toString() {
        return "MicrosoftAccount[accountID=" + getAccountID() + ", profileID=" + profileID
                + ", name=" + getProfileName() + "]";
    }
}

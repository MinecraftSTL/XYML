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

import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNullByDefault;
import org.glavo.uuid.UUIDs;
import space.minecraftstl.xyml.auth.*;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.property.MappedObservableValue;
import space.minecraftstl.xyml.observable.property.ObservableValue;
import space.minecraftstl.xyml.util.gson.JsonUtils;

import java.nio.file.Path;
import java.util.*;

import static java.util.Objects.requireNonNull;
import static space.minecraftstl.xyml.util.logging.Logger.LOG;

/// Account authenticated by a Yggdrasil-compatible service.
@NotNullByDefault
public abstract class YggdrasilAccount extends ClassicAccount {

    /// Yggdrasil-compatible authentication service used by this account.
    protected final YggdrasilService service;

    /// Stable selected profile ID.
    protected final UUID profileID;

    /// Login name used to authenticate this account.
    protected final String loginName;

    /// Whether the current session has passed local or remote validation.
    private boolean authenticated = false;

    /// Current Yggdrasil session.
    private YggdrasilSession session;

    /// Restores a persisted Yggdrasil account.
    ///
    /// @param accountID stable account entry ID
    /// @param service Yggdrasil-compatible authentication service
    /// @param loginName persisted login name
    /// @param session persisted session
    protected YggdrasilAccount(AccountID accountID, YggdrasilService service, String loginName, YggdrasilSession session) {
        super(accountID);
        this.service = requireNonNull(service);
        this.loginName = requireNonNull(loginName);
        this.profileID = requireNonNull(session.getSelectedProfile().getId());
        this.session = requireNonNull(session);

        addProfilePropertiesListener();
    }

    /// Creates and authenticates a new Yggdrasil account.
    ///
    /// @param service Yggdrasil-compatible authentication service
    /// @param loginName login name
    /// @param password account password
    /// @param selector callback used when the service returns multiple profiles
    /// @throws AuthenticationException if authentication or character selection fails
    protected YggdrasilAccount(
            YggdrasilService service,
            String loginName,
            String password,
            CharacterSelector selector) throws AuthenticationException {
        super(AccountID.generate());
        this.service = requireNonNull(service);
        this.loginName = requireNonNull(loginName);

        YggdrasilSession acquiredSession = service.authenticate(loginName, password, randomClientToken());
        if (acquiredSession.getSelectedProfile() == null) {
            if (acquiredSession.getAvailableProfiles() == null || acquiredSession.getAvailableProfiles().isEmpty()) {
                throw new NoCharacterException();
            }

            GameProfile characterToSelect = selector.select(service, acquiredSession.getAvailableProfiles());

            session = service.refresh(
                    acquiredSession.getAccessToken(),
                    acquiredSession.getClientToken(),
                    characterToSelect);
            // response validity has been checked in refresh()
        } else {
            session = acquiredSession;
        }

        profileID = session.getSelectedProfile().getId();
        authenticated = true;

        addProfilePropertiesListener();
    }

    /// Quiet profile-cache value retained for this account's lifetime.
    private ObservableValue<Optional<CompleteGameProfile>> profilePropertiesBinding;

    /// Subscription that publishes cached profile changes through the account revision.
    private Subscription profilePropertiesSubscription;

    /// Attaches the account revision to cached profile-property changes.
    private void addProfilePropertiesListener() {
        profilePropertiesBinding = service.getProfileRepository().binding(profileID, true);
        profilePropertiesSubscription = profilePropertiesBinding.subscribe(change -> invalidate());
    }

    /// Returns the login name used by this account.
    ///
    /// @return login name
    @Override
    public String getLoginName() {
        return loginName;
    }

    /// Returns the selected game profile name.
    ///
    /// @return selected profile name
    @Override
    public String getProfileName() {
        return session.getSelectedProfile().getName();
    }

    /// Returns the selected game profile ID.
    ///
    /// @return selected profile ID
    @Override
    public UUID getProfileID() {
        return session.getSelectedProfile().getId();
    }

    /// Logs in with the stored session, validating or refreshing it when necessary.
    ///
    /// @return authenticated launch information
    /// @throws AuthenticationException if validation or refresh fails
    @Override
    public synchronized AuthInfo logIn() throws AuthenticationException {
        if (!authenticated || !session.hasProfileName()) {
            if (session.hasProfileName() && service.validate(session.getAccessToken(), session.getClientToken())) {
                authenticated = true;
            } else {
                YggdrasilSession acquiredSession;
                try {
                    acquiredSession = service.refresh(session.getAccessToken(), session.getClientToken(), null);
                } catch (RemoteAuthenticationException e) {
                    if ("ForbiddenOperationException".equals(e.getRemoteName())) {
                        throw new CredentialExpiredException(e);
                    } else {
                        throw e;
                    }
                }
                if (acquiredSession.getSelectedProfile() == null ||
                        !acquiredSession.getSelectedProfile().getId().equals(profileID)) {
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

    /// Reauthenticates with a password while preserving the selected profile.
    ///
    /// @param password account password
    /// @return authenticated launch information
    /// @throws AuthenticationException if authentication fails or the selected profile no longer exists
    @Override
    public synchronized AuthInfo logInWithPassword(String password) throws AuthenticationException {
        YggdrasilSession acquiredSession = service.authenticate(loginName, password, randomClientToken());

        if (acquiredSession.getSelectedProfile() == null) {
            if (acquiredSession.getAvailableProfiles() == null || acquiredSession.getAvailableProfiles().isEmpty()) {
                throw new CharacterDeletedException();
            }

            GameProfile characterToSelect = acquiredSession.getAvailableProfiles().stream()
                    .filter(charatcer -> charatcer.getId().equals(profileID))
                    .findFirst()
                    .orElseThrow(CharacterDeletedException::new);

            session = service.refresh(
                    acquiredSession.getAccessToken(),
                    acquiredSession.getClientToken(),
                    characterToSelect);

        } else {
            if (!acquiredSession.getSelectedProfile().getId().equals(profileID)) {
                throw new CharacterDeletedException();
            }
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

    /// Writes public login and profile identity data into account metadata.
    ///
    /// @param metadata mutable metadata destination
    @Override
    public void writeMetadata(JsonObject metadata) {
        super.writeMetadata(metadata);
        metadata.addProperty("loginName", loginName);
        metadata.addProperty("profileID", profileID.toString());
    }

    /// Writes the current session and cached profile properties into private account storage.
    ///
    /// @param privateData mutable private-data destination
    @Override
    public void writePrivateData(JsonObject privateData) {
        super.writePrivateData(privateData);
        session.writePrivateData(privateData);
        service.getProfileRepository().getImmediately(profileID).ifPresent(profile ->
                privateData.add("profileProperties", JsonUtils.GSON.toJsonTree(profile.getProperties())));
    }

    /// Returns the Yggdrasil-compatible authentication service used by this account.
    ///
    /// @return Yggdrasil-compatible authentication service
    public YggdrasilService getYggdrasilService() {
        return service;
    }

    /// Invalidates authentication and cached complete-profile data.
    @Override
    public void clearCache() {
        authenticated = false;
        service.getProfileRepository().invalidate(profileID);
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

    /// Reports that Yggdrasil accounts support skin uploads.
    ///
    /// @return always `true`
    @Override
    public boolean canUploadSkin() {
        return true;
    }

    /// Uploads a skin through the Yggdrasil-compatible service.
    ///
    /// @param isSlim whether the skin uses the slim player model
    /// @param file skin image file
    /// @throws AuthenticationException if authentication or upload fails
    /// @throws UnsupportedOperationException if the service does not support uploads
    @Override
    public void uploadSkin(boolean isSlim, Path file) throws AuthenticationException, UnsupportedOperationException {
        service.uploadSkin(profileID, session.getAccessToken(), isSlim, file);
    }

    /// Generates a compact random client token for a fresh authentication request.
    ///
    /// @return compact random UUID
    private static String randomClientToken() {
        return UUIDs.toCompactString(UUID.randomUUID());
    }

    /// Returns a diagnostic account description without credentials.
    ///
    /// @return account ID, profile ID, and login name
    @Override
    public String toString() {
        return "YggdrasilAccount[accountID=" + getAccountID() + ", profileID=" + profileID
                + ", loginName=" + loginName + "]";
    }
}

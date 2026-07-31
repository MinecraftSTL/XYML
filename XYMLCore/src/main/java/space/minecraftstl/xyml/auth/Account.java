/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021 huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml.auth;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.MustBeInvokedByOverriders;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.auth.yggdrasil.Texture;
import space.minecraftstl.xyml.auth.yggdrasil.TextureType;
import space.minecraftstl.xyml.observable.property.BooleanProperty;
import space.minecraftstl.xyml.observable.property.ObservableValue;
import space.minecraftstl.xyml.observable.property.SimpleBooleanProperty;
import space.minecraftstl.xyml.observable.property.SimpleLongProperty;
import space.minecraftstl.xyml.observable.property.SimpleObjectProperty;
import space.minecraftstl.xyml.util.ToStringBuilder;
import space.minecraftstl.xyml.util.gson.JsonUtils;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/// Base class for one persisted launcher account and its toolkit-neutral change signals.
///
/// Account changes are published synchronously on the mutating thread through [#changes()]. UI consumers must
/// dispatch presentation work to their own event thread.
///
/// @author huangyuhui
@NotNullByDefault
public abstract class Account {
    /// Serialized account ID property name.
    public static final String PROPERTY_ACCOUNT_ID = "accountID";

    /// Shared immutable empty texture value used by account types without remote textures.
    private static final ObservableValue<Optional<Map<TextureType, Texture>>> EMPTY_TEXTURES =
            new SimpleObjectProperty<>(Optional.empty());

    /// Stable ID of this persisted account entry.
    private final AccountID accountID;

    /// Whether credentials may be copied with a portable launcher installation.
    private final BooleanProperty portable = new SimpleBooleanProperty(this, "portable", false);

    /// Monotonic account-change signal.
    private final SimpleLongProperty changeRevision = new SimpleLongProperty(this, "changeRevision", 0L);

    /// Serializes revision assignment and publication order across authentication threads.
    private final Object changeLock = new Object();

    /// Creates an account.
    ///
    /// @param accountID stable account entry ID
    protected Account(AccountID accountID) {
        this.accountID = Objects.requireNonNull(accountID, "accountID");
    }

    /// Returns the stable account entry ID.
    ///
    /// @return account entry ID
    public AccountID getAccountID() {
        return accountID;
    }

    /// Returns the selected game profile name.
    ///
    /// @return profile name
    public abstract String getProfileName();

    /// Returns the selected game profile ID.
    ///
    /// @return profile ID
    public abstract UUID getProfileID();

    /// Logs in with stored credentials.
    ///
    /// @return authenticated launch information
    /// @throws AuthenticationException when stored credentials cannot authenticate
    public abstract AuthInfo logIn() throws AuthenticationException;

    /// Creates launch information without contacting an authentication service when supported.
    ///
    /// @return offline launch information
    /// @throws AuthenticationException when required cached profile data is unavailable
    public abstract AuthInfo playOffline() throws AuthenticationException;

    /// Reports whether this account provider accepts skin uploads.
    ///
    /// @return true when [#uploadSkin(boolean, Path)] is supported
    public boolean canUploadSkin() {
        return false;
    }

    /// Uploads a skin through this account provider.
    ///
    /// @param slim whether the skin uses the slim arm model
    /// @param file PNG skin file
    /// @throws AuthenticationException when authentication or upload fails
    /// @throws UnsupportedOperationException when this provider does not support uploads
    public void uploadSkin(boolean slim, Path file)
            throws AuthenticationException, UnsupportedOperationException {
        throw new UnsupportedOperationException("Unsupported Operation");
    }

    /// Writes public account metadata into the target JSON object.
    ///
    /// The target object is owned by the caller and is not retained.
    ///
    /// @param metadata mutable destination metadata
    @MustBeInvokedByOverriders
    public void writeMetadata(JsonObject metadata) {
        metadata.addProperty(PROPERTY_ACCOUNT_ID, accountID.toString());
    }

    /// Writes private credentials and cached account data into the target JSON object.
    ///
    /// The base implementation writes nothing. The target object is owned by the caller and is not retained.
    ///
    /// @param privateData mutable private-data destination
    @MustBeInvokedByOverriders
    public void writePrivateData(JsonObject privateData) {
    }

    /// Clears provider-specific cached authentication or profile data.
    public void clearCache() {
    }

    /// Returns the writable portable-account property.
    ///
    /// @return toolkit-neutral portable property
    public BooleanProperty portableProperty() {
        return portable;
    }

    /// Reports whether this account is portable.
    ///
    /// @return portable-account state
    public boolean isPortable() {
        return portable.get();
    }

    /// Changes whether this account is portable.
    ///
    /// @param value portable-account state
    public void setPortable(boolean value) {
        portable.set(value);
    }

    /// Returns the monotonic account-change signal.
    ///
    /// Subscribers are notified when mutable profile, credential, or offline-skin state changes. The initial value is
    /// zero and callers should read account fields directly after subscribing.
    ///
    /// @return toolkit-neutral change revision
    public final ObservableValue<Long> changes() {
        return changeRevision;
    }

    /// Publishes an account change in mutation order.
    protected final void invalidate() {
        synchronized (changeLock) {
            changeRevision.set(changeRevision.get() + 1L);
        }
    }

    /// Returns an observable optional texture map for this account.
    ///
    /// Account types without remote textures return a stable empty observable.
    ///
    /// @return observable optional texture map
    public ObservableValue<Optional<Map<TextureType, Texture>>> getTextures() {
        return EMPTY_TEXTURES;
    }

    /// Returns the stable account ID from serialized storage when valid.
    ///
    /// @param storage serialized account record
    /// @return stable account ID, or null when absent or malformed
    public static @Nullable AccountID getAccountID(JsonObject storage) {
        @Nullable String value = JsonUtils.getString(storage, PROPERTY_ACCOUNT_ID);
        if (value == null) {
            return null;
        }
        try {
            return AccountID.parse(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /// Reads a required account ID from serialized storage.
    ///
    /// @param storage serialized account record
    /// @return parsed account ID
    /// @throws IllegalArgumentException when the ID is absent or malformed
    public static AccountID readAccountID(JsonObject storage) {
        @Nullable String value = JsonUtils.getString(storage, PROPERTY_ACCOUNT_ID);
        if (value == null) {
            throw new IllegalArgumentException("accountID is missing");
        }
        return AccountID.parse(value);
    }

    /// Computes identity and portability equality state.
    ///
    /// @return account hash code
    @Override
    public final int hashCode() {
        return Objects.hash(portable.get(), accountID);
    }

    /// Compares stable account identity and portability state.
    ///
    /// @param obj candidate value
    /// @return true for an equivalent account
    @Override
    public final boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Account other)) {
            return false;
        }
        return isPortable() == other.isPortable() && accountID.equals(other.accountID);
    }

    /// Returns a diagnostic account representation without private credentials.
    ///
    /// @return diagnostic account text
    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .append("accountID", accountID)
                .append("profileName", getProfileName())
                .append("profileID", getProfileID())
                .append("portable", isPortable())
                .toString();
    }
}

/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.auth.Account;
import space.minecraftstl.xyml.auth.AccountID;
import space.minecraftstl.xyml.auth.AuthInfo;
import space.minecraftstl.xyml.auth.AuthenticationException;
import space.minecraftstl.xyml.auth.authlibinjector.AuthlibInjectorArtifactInfo;
import space.minecraftstl.xyml.auth.authlibinjector.AuthlibInjectorArtifactProvider;
import space.minecraftstl.xyml.auth.offline.OfflineAccount;
import space.minecraftstl.xyml.auth.offline.OfflineAccountFactory;
import space.minecraftstl.xyml.auth.offline.Skin;
import space.minecraftstl.xyml.auth.yggdrasil.Texture;
import space.minecraftstl.xyml.auth.yggdrasil.TextureModel;
import space.minecraftstl.xyml.auth.yggdrasil.TextureType;
import space.minecraftstl.xyml.observable.property.ObservableValue;
import space.minecraftstl.xyml.observable.property.SimpleObjectProperty;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

/// Verifies credential-free account-to-avatar projection for online and offline accounts.
@NotNullByDefault
public final class AccountAvatarSourceTest {
    /// Remote texture URLs are copied from the observable cache while diagnostic text stays redacted.
    @Test
    public void snapshotsOnlineTextureWithoutExposingSignedQuery() {
        String textureUrl = "https://textures.example.invalid/skin.png?signature=private-value";
        TestOnlineAccount account = new TestOnlineAccount(textureUrl);

        AccountAvatarSource source = LauncherAccountStore.avatarSource(account);

        assertEquals(AccountAvatarSource.remoteOrDefault(textureUrl), source);
        assertFalse(source.toString().contains("private-value"));
        assertFalse(AccountDescriptor.class.getRecordComponents()[4].getType().isAssignableFrom(Account.class));
    }

    /// Offline skin fields are copied from the account and remain detached from later account changes.
    @Test
    public void snapshotsOfflineSkinConfiguration() {
        OfflineAccount account = new OfflineAccountFactory(UnavailableArtifactProvider.INSTANCE)
                .create("Offline Player", UUID.fromString("00000000-0000-0000-0000-000000000021"));
        Skin initial = new Skin(
                Skin.Type.LOCAL_FILE,
                null,
                TextureModel.SLIM,
                "C:/skins/first.png",
                null);
        account.setSkin(initial);

        AccountAvatarSource snapshot = LauncherAccountStore.avatarSource(account);
        account.setSkin(new Skin(Skin.Type.STEVE, null, TextureModel.WIDE, null, null));

        assertEquals(AccountAvatarSource.offlineOrDefault(initial), snapshot);
        assertFalse(snapshot.toString().contains("C:/skins/first.png"));
    }

    /// Unsafe remote URI forms select the shared bundled fallback instead of entering presentation state.
    @Test
    public void rejectsCredentialBearingAndNonHttpTextureUris() {
        AccountAvatarSource fallback = AccountAvatarSource.bundledDefault();

        assertSame(fallback, AccountAvatarSource.remoteOrDefault("file:///tmp/skin.png"));
        assertSame(fallback, AccountAvatarSource.remoteOrDefault("https://user:password@example.invalid/skin.png"));
        assertSame(fallback, AccountAvatarSource.remoteOrDefault("not a URI"));
    }

    /// Minimal online account exposing only a public texture observable.
    @NotNullByDefault
    private static final class TestOnlineAccount extends Account {
        /// Stable test profile identifier.
        private static final UUID PROFILE_ID =
                UUID.fromString("00000000-0000-0000-0000-000000000020");

        /// Observable texture map used by the account projection.
        private final ObservableValue<Optional<@Unmodifiable Map<TextureType, Texture>>> textures;

        /// Creates one test account with a remote skin URL.
        ///
        /// @param textureUrl public remote skin URL
        private TestOnlineAccount(String textureUrl) {
            super(AccountID.generate());
            textures = new SimpleObjectProperty<>(Optional.of(Map.of(
                    TextureType.SKIN,
                    new Texture(textureUrl, Map.of()))));
        }

        /// Returns the stable test profile name.
        ///
        /// @return profile name
        @Override
        public String getProfileName() {
            return "Online Player";
        }

        /// Returns the stable test profile ID.
        ///
        /// @return profile ID
        @Override
        public UUID getProfileID() {
            return PROFILE_ID;
        }

        /// Rejects authentication because projection must not invoke it.
        ///
        /// @return never returns
        /// @throws AuthenticationException retained account contract
        @Override
        public AuthInfo logIn() throws AuthenticationException {
            throw new UnsupportedOperationException("Projection must not authenticate");
        }

        /// Rejects offline launch because projection must not invoke it.
        ///
        /// @return never returns
        /// @throws AuthenticationException retained account contract
        @Override
        public AuthInfo playOffline() throws AuthenticationException {
            throw new UnsupportedOperationException("Projection must not launch");
        }

        /// Returns the controlled texture observable.
        ///
        /// @return current remote skin map
        @Override
        public ObservableValue<Optional<@Unmodifiable Map<TextureType, Texture>>> getTextures() {
            return textures;
        }
    }

    /// Artifact provider that proves avatar projection does not initialize launch-time authlib support.
    @NotNullByDefault
    private enum UnavailableArtifactProvider implements AuthlibInjectorArtifactProvider {
        /// Shared provider instance.
        INSTANCE;

        /// Rejects blocking artifact access when unexpectedly called.
        ///
        /// @return never returns
        /// @throws IOException always, because no test artifact exists
        @Override
        public AuthlibInjectorArtifactInfo getArtifactInfo() throws IOException {
            throw new IOException("No test artifact");
        }

        /// Reports that no artifact has been cached.
        ///
        /// @return empty cached artifact
        @Override
        public Optional<AuthlibInjectorArtifactInfo> getArtifactInfoImmediately() {
            return Optional.empty();
        }
    }
}

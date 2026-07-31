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
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.auth.offline.Skin;
import space.minecraftstl.xyml.auth.yggdrasil.TextureModel;
import space.minecraftstl.xyml.util.StringUtils;

import java.net.URI;
import java.util.Objects;

/// Presentation-safe immutable source used to resolve one account avatar away from the EDT.
///
/// Implementations retain only a public texture URI or a copied offline-skin configuration. They never retain an
/// account, access token, refresh token, password, session, or mutable texture map.
@NotNullByDefault
public sealed interface AccountAvatarSource
        permits AccountAvatarSource.DefaultSource,
        AccountAvatarSource.RemoteSource,
        AccountAvatarSource.OfflineSource {
    /// Returns the shared launcher-bundled fallback source.
    ///
    /// @return UUID-derived bundled avatar source
    static AccountAvatarSource bundledDefault() {
        return DefaultSource.INSTANCE;
    }

    /// Creates a validated public HTTP texture source, falling back when the URI is missing or unsafe.
    ///
    /// User-info URI components are rejected so credentials cannot enter presentation state. Query strings are
    /// retained because a texture service may require a signed resource URI, but source diagnostics are redacted.
    ///
    /// @param textureUrl public texture URI, or null when no skin texture is available
    /// @return remote texture source or the launcher-bundled fallback
    static AccountAvatarSource remoteOrDefault(@Nullable String textureUrl) {
        if (StringUtils.isBlank(textureUrl)) {
            return bundledDefault();
        }
        try {
            URI uri = URI.create(Objects.requireNonNull(textureUrl, "textureUrl"));
            @Nullable String scheme = uri.getScheme();
            if (!uri.isAbsolute()
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                return bundledDefault();
            }
            return new RemoteSource(uri);
        } catch (IllegalArgumentException ignored) {
            return bundledDefault();
        }
    }

    /// Copies an offline skin configuration without retaining the mutable account that owns it.
    ///
    /// @param skin current offline skin configuration, or null for the UUID-derived bundled default
    /// @return copied offline source or the launcher-bundled fallback
    static AccountAvatarSource offlineOrDefault(@Nullable Skin skin) {
        if (skin == null || skin.type() == Skin.Type.DEFAULT) {
            return bundledDefault();
        }
        return new OfflineSource(
                skin.type(),
                skin.cslApi(),
                skin.textureModel(),
                skin.localSkinPath(),
                skin.localCapePath());
    }

    /// Singleton source selecting the profile's UUID-derived launcher-bundled skin.
    @NotNullByDefault
    final class DefaultSource implements AccountAvatarSource {
        /// Shared stateless fallback source.
        static final DefaultSource INSTANCE = new DefaultSource();

        /// Prevents additional fallback instances.
        private DefaultSource() {
        }

        /// Returns a credential-free diagnostic label.
        ///
        /// @return stable source label
        @Override
        public String toString() {
            return "AccountAvatarSource[bundled-default]";
        }
    }

    /// Public texture URI retained only inside the account presentation package.
    @NotNullByDefault
    final class RemoteSource implements AccountAvatarSource {
        /// Validated absolute HTTP or HTTPS texture URI.
        private final URI uri;

        /// Creates one validated URI source.
        ///
        /// @param uri absolute texture URI without user information
        RemoteSource(URI uri) {
            this.uri = Objects.requireNonNull(uri, "uri");
        }

        /// Returns the texture URI to the asynchronous account-avatar loader.
        ///
        /// @return validated texture URI
        URI uri() {
            return uri;
        }

        /// Compares texture source identity without exposing it in diagnostics.
        ///
        /// @param candidate candidate source
        /// @return whether both sources reference the same URI
        @Override
        public boolean equals(@Nullable Object candidate) {
            return candidate instanceof RemoteSource other && uri.equals(other.uri);
        }

        /// Returns the texture URI hash used by the shared icon cache.
        ///
        /// @return URI hash
        @Override
        public int hashCode() {
            return uri.hashCode();
        }

        /// Returns a redacted diagnostic label that cannot disclose signed query parameters.
        ///
        /// @return redacted source label
        @Override
        public String toString() {
            return "AccountAvatarSource[remote]";
        }
    }

    /// Copied offline-skin fields retained only inside the account presentation package.
    @NotNullByDefault
    final class OfflineSource implements AccountAvatarSource {
        /// Configured offline skin source kind.
        private final Skin.Type type;

        /// Custom-skin-loader endpoint, or null when not used.
        private final @Nullable String cslApi;

        /// Configured arm model.
        private final TextureModel textureModel;

        /// Local skin path, or null when not configured.
        private final @Nullable String localSkinPath;

        /// Local cape path, or null when not configured.
        private final @Nullable String localCapePath;

        /// Creates one detached offline-skin snapshot.
        ///
        /// @param type configured source kind
        /// @param cslApi custom-skin-loader endpoint, or null
        /// @param textureModel configured arm model
        /// @param localSkinPath local skin path, or null
        /// @param localCapePath local cape path, or null
        OfflineSource(
                Skin.Type type,
                @Nullable String cslApi,
                TextureModel textureModel,
                @Nullable String localSkinPath,
                @Nullable String localCapePath) {
            this.type = Objects.requireNonNull(type, "type");
            this.cslApi = cslApi;
            this.textureModel = Objects.requireNonNull(textureModel, "textureModel");
            this.localSkinPath = localSkinPath;
            this.localCapePath = localCapePath;
        }

        /// Reconstructs the immutable domain configuration on the I/O scheduler.
        ///
        /// @return detached skin configuration
        Skin toSkin() {
            return new Skin(type, cslApi, textureModel, localSkinPath, localCapePath);
        }

        /// Compares copied configuration fields used by account-list revision tracking and icon caching.
        ///
        /// @param candidate candidate source
        /// @return whether both configurations are equal
        @Override
        public boolean equals(@Nullable Object candidate) {
            if (!(candidate instanceof OfflineSource other)) {
                return false;
            }
            return type == other.type
                    && Objects.equals(cslApi, other.cslApi)
                    && textureModel == other.textureModel
                    && Objects.equals(localSkinPath, other.localSkinPath)
                    && Objects.equals(localCapePath, other.localCapePath);
        }

        /// Hashes copied configuration fields for the shared icon cache.
        ///
        /// @return configuration hash
        @Override
        public int hashCode() {
            return Objects.hash(type, cslApi, textureModel, localSkinPath, localCapePath);
        }

        /// Returns a diagnostic label without disclosing local paths or custom endpoint parameters.
        ///
        /// @return redacted source label
        @Override
        public String toString() {
            return "AccountAvatarSource[offline=" + type + "]";
        }
    }
}

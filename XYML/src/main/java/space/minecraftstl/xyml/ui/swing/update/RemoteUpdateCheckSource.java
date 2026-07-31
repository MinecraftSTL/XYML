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
package space.minecraftstl.xyml.ui.swing.update;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.Metadata;
import space.minecraftstl.xyml.upgrade.IntegrityChecker;
import space.minecraftstl.xyml.upgrade.RemoteVersion;
import space.minecraftstl.xyml.util.io.NetworkUtils;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/// Blocking production source for the established launcher update endpoint.
///
/// Integrity verification and network access stay behind [UpdateCheckSource], allowing the Swing service and its
/// tests to remain independent from global metadata and live I/O.
@NotNullByDefault
public final class RemoteUpdateCheckSource implements UpdateCheckSource {
    /// Base update endpoint before the request query is appended.
    private final String endpoint;

    /// Version sent to the update endpoint.
    private final String currentVersion;

    /// Whether current-artifact integrity must be verified before network access.
    private final boolean integrityCheckRequired;

    /// Blocking current-artifact integrity verifier.
    private final BooleanSupplier selfVerifier;

    /// Creates an injectable remote source.
    ///
    /// @param endpoint update endpoint
    /// @param currentVersion running launcher version
    /// @param integrityCheckRequired whether verification is mandatory
    /// @param selfVerifier blocking integrity verifier
    public RemoteUpdateCheckSource(
            String endpoint,
            String currentVersion,
            boolean integrityCheckRequired,
            BooleanSupplier selfVerifier) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.currentVersion = Objects.requireNonNull(currentVersion, "currentVersion");
        this.integrityCheckRequired = integrityCheckRequired;
        this.selfVerifier = Objects.requireNonNull(selfVerifier, "selfVerifier");
    }

    /// Creates the production source from current metadata and integrity settings.
    ///
    /// @return production remote source
    public static RemoteUpdateCheckSource production() {
        return new RemoteUpdateCheckSource(
                Metadata.XYML_UPDATE_URL,
                Metadata.VERSION,
                !IntegrityChecker.DISABLE_SELF_INTEGRITY_CHECK,
                IntegrityChecker::isSelfVerified);
    }

    /// Verifies the running artifact, builds the channel query, and loads the remote JSON response.
    ///
    /// @param request exact update request
    /// @return fetched remote version
    /// @throws IOException when verification or remote loading fails
    @Override
    public RemoteVersion fetch(UpdateCheckRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        if (integrityCheckRequired && !selfVerifier.getAsBoolean()) {
            throw new IOException("Self verification failed");
        }

        LinkedHashMap<String, String> query = new LinkedHashMap<>();
        query.put("version", currentVersion);
        query.put(
                "channel",
                request.preview()
                        ? request.channel().channelName + "-preview"
                        : request.channel().channelName);
        String requestUrl = NetworkUtils.withQuery(endpoint, query);
        return RemoteVersion.fetch(request.channel(), request.preview(), requestUrl);
    }
}

// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
            throw new space.minecraftstl.xyml.upgrade.SelfVerificationException();
        }

        return RemoteVersion.fetch(request.channel(), false, buildRequestUrl(request));
    }

    /// Resolves the channel placeholder and appends the deterministic update query.
    ///
    /// @param request exact update request
    /// @return complete update descriptor URL
    String buildRequestUrl(UpdateCheckRequest request) {
        Objects.requireNonNull(request, "request");
        String channelName = request.channel().channelName();
        String resolvedEndpoint = endpoint.replace("{channel}", channelName);
        LinkedHashMap<String, String> query = new LinkedHashMap<>();
        query.put("version", currentVersion);
        query.put("channel", channelName);
        return NetworkUtils.withQuery(resolvedEndpoint, query);
    }
}

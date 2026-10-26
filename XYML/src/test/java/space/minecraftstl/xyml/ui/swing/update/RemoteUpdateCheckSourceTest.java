// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.update;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.upgrade.UpdateChannel;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies channel-specific release metadata URLs without performing network access.
@NotNullByDefault
final class RemoteUpdateCheckSourceTest {
    /// Resolves the beta descriptor and sends the exact selected channel in its query.
    @Test
    void resolvesChannelDescriptorTemplate() {
        RemoteUpdateCheckSource source = new RemoteUpdateCheckSource(
                "https://example.test/releases/download/release-channels/xyml-update-{channel}.json",
                "1.0.0",
                false,
                () -> true);

        assertEquals(
                "https://example.test/releases/download/release-channels/xyml-update-beta.json"
                        + "?version=1.0.0&channel=beta",
                source.buildRequestUrl(new UpdateCheckRequest(UpdateChannel.BETA)));
    }
}

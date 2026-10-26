// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.addon.RemoteAddon;
import space.minecraftstl.xyml.game.GameInstanceID;

import java.util.Objects;

/// Immutable user-confirmed remote-modpack installation request.
///
/// Retaining the selected Core item and version means the installation path has everything needed
/// to download a real archive and launch the existing ModpackHelper workflow without browser state.
///
/// @param item loaded source project selected by the user
/// @param version installable project version selected by the user
/// @param instanceId exact destination instance identifier
@NotNullByDefault
public record RemoteModpackInstallRequest(
        RemoteModpackCatalogItem item,
        RemoteAddon.Version version,
        GameInstanceID instanceId) {
    /// Validates the selected Core values and destination identifier.
    public RemoteModpackInstallRequest {
        item = Objects.requireNonNull(item, "item");
        version = Objects.requireNonNull(version, "version");
        instanceId = Objects.requireNonNull(instanceId, "instanceId");
    }
}

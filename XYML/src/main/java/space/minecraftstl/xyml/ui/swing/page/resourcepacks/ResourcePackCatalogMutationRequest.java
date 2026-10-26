// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.resourcepacks;

import org.jetbrains.annotations.NotNullByDefault;

/// Immutable internal write request understood by resource-pack catalog access implementations.
@NotNullByDefault
sealed interface ResourcePackCatalogMutationRequest
        permits ResourcePackImportMutation, ResourcePackEnabledMutation, ResourcePackDeleteMutation, ResourcePackReorderMutation {
}

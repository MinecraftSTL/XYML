// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.resourcepacks;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.image.EncodedImage;
import space.minecraftstl.xyml.ui.swing.choice.LoadCancellation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/// Blocking local source contract required by the asynchronous resource-pack catalog model.
@NotNullByDefault
interface ResourcePackCatalogAccess {
    /// Loads only supported state and sorted candidate paths.
    ///
    /// @param cancellation cooperative index cancellation
    /// @return shallow supported or unsupported index
    /// @throws IOException when local index access fails
    ResourcePackCatalogIndex loadIndex(LoadCancellation cancellation) throws IOException;

    /// Resolves exactly the supplied paths in the supplied order.
    ///
    /// @param paths exact normalized viewport paths
    /// @param cancellation cooperative viewport cancellation
    /// @return one row per path in identical order
    /// @throws IOException when shared local state cannot be read
    @Unmodifiable List<ResourcePackCatalogItem> loadItems(
            @Unmodifiable List<Path> paths,
            LoadCancellation cancellation) throws IOException;

    /// Loads one resource-pack icon without constructing a Swing image.
    ///
    /// @param path normalized resource-pack path
    /// @return encoded pack icon, or null when pack.png is absent
    /// @throws IOException when the icon cannot be read or exceeds its safety bound
    default @Nullable EncodedImage loadIcon(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        return null;
    }

    /// Applies one write and rescans the exact shallow index before releasing source access.
    ///
    /// Implementations may create private, fully cleaned staging artifacts before `commitPoint`,
    /// but must call it immediately before the first potentially irreversible catalog change.
    /// A mutation failure is returned with the successfully refreshed index; an exception is
    /// reserved for cancellation or failure to obtain that mandatory index.
    ///
    /// @param mutation immutable requested write
    /// @param cancellation cooperative pre-commit cancellation
    /// @param commitPoint callback crossing the non-cancellable irreversible-change boundary
    /// @return refreshed exact index and optional mutation failure
    /// @throws IOException when the mandatory follow-up index cannot be obtained
    ResourcePackCatalogMutationAccessResult mutateAndLoadIndex(
            ResourcePackCatalogMutationRequest mutation,
            LoadCancellation cancellation,
            Runnable commitPoint) throws IOException;
}

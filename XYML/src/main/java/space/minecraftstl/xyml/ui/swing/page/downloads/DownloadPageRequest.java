// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.GameInstanceID;

import java.util.Objects;

/// Captures one programmatic download-center navigation, optional instance context, and optional Mods query.
///
/// A request never carries analyzed version or loader metadata: the catalog resolves those from the target
/// instance when it applies the request, so navigation stays free of blocking metadata reads.
///
/// @param target requested download category
/// @param targetInstanceId instance whose context should prefill the category, or `null` for a plain category switch
/// @param searchText trimmed non-blank Mods query, or null to preserve the current query without submitting it
@NotNullByDefault
public record DownloadPageRequest(
        DownloadPageTarget target,
        @Nullable GameInstanceID targetInstanceId,
        @Nullable String searchText) {
    /// Validates one download-center request and normalizes its optional Mods query.
    ///
    /// @throws IllegalArgumentException if a query is blank or targets a category other than Mods
    public DownloadPageRequest {
        Objects.requireNonNull(target, "target");
        if (searchText != null) {
            if (target != DownloadPageTarget.MODS) {
                throw new IllegalArgumentException("searchText requires the Mods category");
            }
            searchText = searchText.trim();
            if (searchText.isEmpty()) {
                throw new IllegalArgumentException("searchText must not be blank");
            }
        }
    }

    /// Creates a category request with optional instance context and no search submission.
    ///
    /// @param target requested download category
    /// @param targetInstanceId instance whose context should prefill the category, or null
    public DownloadPageRequest(DownloadPageTarget target, @Nullable GameInstanceID targetInstanceId) {
        this(target, targetInstanceId, null);
    }

    /// Creates a category-only request without an instance context.
    ///
    /// @param target requested download category
    /// @return request carrying no instance context
    public static DownloadPageRequest of(DownloadPageTarget target) {
        return new DownloadPageRequest(target, null);
    }
}

// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;
import space.minecraftstl.xyml.ui.swing.choice.ViewportChoiceDataSource;

/// Supplies lazy game-version catalog state and viewport data without exposing Swing or JavaFX.
///
/// Implementations expose an exact count and keep that count and indexed order stable within one
/// [GameVersionCatalogSnapshot#contentRevision()] value. Range loading only slices the current local
/// immutable filtered snapshot and therefore does not prescribe a fixed page size.
@NotNullByDefault
public interface GameVersionCatalogModel extends ViewportChoiceDataSource<GameVersionCatalogItem> {
    /// Returns the latest immutable catalog state.
    ///
    /// @return current catalog snapshot
    GameVersionCatalogSnapshot snapshot();

    /// Registers for future catalog invalidations on the publishing thread.
    ///
    /// Rapid concurrent transitions may be coalesced, so consumers must re-read [#snapshot()] when
    /// notified instead of assuming that every intermediate transition was delivered.
    ///
    /// @param listener snapshot transition listener
    /// @return independently cancellable listener registration
    Subscription subscribe(ValueChangeListener<GameVersionCatalogSnapshot> listener);

    /// Starts the initial source load only while the model is idle.
    void loadIfNeeded();

    /// Starts a new source generation, cancelling and superseding any active generation.
    void refresh();

    /// Replaces the case-insensitive version-ID query.
    ///
    /// @param query query text retained exactly for presentation
    void setQuery(String query);

    /// Replaces the game-version kind filter.
    ///
    /// @param filter replacement filter
    void setFilter(GameVersionFilter filter);

    /// Selects a version by stable catalog ID.
    ///
    /// A selected ID remains owned by the model while a query or filter hides it and regains an
    /// indexed selection if later filtering makes it visible again.
    ///
    /// @param versionId stable loaded version identifier
    void selectVersion(String versionId);
}

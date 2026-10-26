// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.home;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;

/// Supplies selected launcher account and instance presentation without exposing toolkit properties.
@NotNullByDefault
public interface HomeSelectionStore {
    /// Returns the latest immutable selection state.
    ///
    /// @return current account and instance selection
    HomeSelectionState snapshot();

    /// Registers for selection transitions on the publishing thread.
    ///
    /// @param listener selection transition listener
    /// @return independently cancellable listener registration
    Subscription subscribe(ValueChangeListener<HomeSelectionState> listener);
}

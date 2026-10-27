// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.ui.swing.dialog.DeletionFallbackDecision;
import space.minecraftstl.xyml.ui.swing.dialog.RetryableFailureInteraction;
import space.minecraftstl.xyml.util.io.DeletionMode;

import java.awt.Component;

/// Separates native Swing dialogs from lifecycle operations so the panel remains deterministic in tests.
///
/// All methods are invoked only from the Swing event-dispatch thread. Returning `null` from an input
/// request represents cancellation and never schedules a background mutation.
@NotNullByDefault
public interface InstanceLifecycleInteractions extends RetryableFailureInteraction {
    /// Requests a replacement instance name from the user.
    ///
    /// @param owner native dialog owner
    /// @param sourceId current instance identifier
    /// @return raw requested destination, or `null` after cancellation
    @Nullable String requestRename(Component owner, GameInstanceID sourceId);

    /// Requests a duplicate destination and whether worlds should be copied.
    ///
    /// @param owner native dialog owner
    /// @param sourceId current instance identifier
    /// @return confirmed duplicate request, or `null` after cancellation
    @Nullable InstanceLifecycleDuplicateRequest requestDuplicate(Component owner, GameInstanceID sourceId);

    /// Chooses recycle-bin-first deletion without a warning or warns before permanent deletion.
    ///
    /// @param owner native dialog owner
    /// @param sourceId current instance identifier
    /// @return selected deletion mode, or null when deletion was cancelled
    @Nullable DeletionMode chooseDeleteMode(Component owner, GameInstanceID sourceId);

    /// Shows the original deletion warning after a recycle-bin move failed.
    ///
    /// @param owner native dialog owner
    /// @param sourceId current instance identifier
    /// @return whether permanent deletion was approved
    boolean confirmPermanentFallback(Component owner, GameInstanceID sourceId);

    /// Chooses how to recover an instance that could not enter the recycle bin.
    ///
    /// @param owner native dialog owner
    /// @param sourceId current instance identifier
    /// @return selected fallback action, never null
    default DeletionFallbackDecision choosePermanentFallback(Component owner, GameInstanceID sourceId) {
        return confirmPermanentFallback(owner, sourceId)
                ? DeletionFallbackDecision.DELETE_PERMANENTLY
                : DeletionFallbackDecision.CANCEL;
    }

    /// Displays one terminal lifecycle failure.
    ///
    /// @param owner native dialog owner
    /// @param title visible failure title
    /// @param detail non-blank failure detail
    void showFailure(Component owner, String title, String detail);
}

// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.game.analyzer;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.task.Task;

import java.net.URI;
import java.util.List;

/// Configures one solver step without exposing Swing, JavaFX, or another presentation toolkit to Core.
@NotNullByDefault
public interface SolverConfigurator {
    /// Sets an optional image for a manual repair step.
    ///
    /// @param image toolkit-neutral image resource URI
    void setImage(URI image);

    /// Sets the localized and fallback text for a manual repair step.
    ///
    /// @param messageKey localization key
    /// @param messageArguments immutable localization arguments
    /// @param fallbackMessage presentation-independent English fallback
    void setDescription(
            String messageKey,
            @Unmodifiable List<Object> messageArguments,
            String fallbackMessage);

    /// Adds one manual choice and returns its stable selection identifier.
    ///
    /// Implementations reserve identifiers through `255` for standard commands and allocate custom identifiers above
    /// that range, matching the XYAT callback contract.
    ///
    /// @param messageKey localization key
    /// @param messageArguments immutable localization arguments
    /// @param fallbackMessage presentation-independent English fallback
    /// @return identifier later passed to [Solver#callbackSelection(SolverConfigurator, int)]
    int putButton(
            String messageKey,
            @Unmodifiable List<Object> messageArguments,
            String fallbackMessage);

    /// Binds one stopped task and makes the step resolve automatically when it completes.
    ///
    /// @param task stopped repair task owned by this step
    void bindTask(Task<?> task);

    /// Clears the current step and transfers to another solver or the next diagnosis.
    ///
    /// @param solver next solver, or null to continue with the next diagnosis
    void transferTo(@Nullable Solver solver);
}

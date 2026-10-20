/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package space.minecraftstl.xyml.game.analyzer;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Objects;

/// Immutable text-only repair proposal.
///
/// @param messageKey localization key used by presentation layers
/// @param messageArguments immutable localization arguments
/// @param fallbackMessage presentation-independent English repair text
/// @param repairAction immutable structured repair proposal
@NotNullByDefault
public record TextSolver(
        String messageKey,
        @Unmodifiable List<Object> messageArguments,
        String fallbackMessage,
        RepairActionDescriptor repairAction) implements Solver {
    /// Defensively copies arguments and validates all text fields.
    public TextSolver {
        Objects.requireNonNull(messageKey, "messageKey");
        messageArguments = List.copyOf(Objects.requireNonNull(messageArguments, "messageArguments"));
        Objects.requireNonNull(fallbackMessage, "fallbackMessage");
        Objects.requireNonNull(repairAction, "repairAction");
    }

    /// Creates a text-only proposal with the standard manual-guidance action.
    ///
    /// @param messageKey localization key used by presentation layers
    /// @param messageArguments immutable localization arguments
    /// @param fallbackMessage presentation-independent English repair text
    public TextSolver(
            String messageKey,
            @Unmodifiable List<Object> messageArguments,
            String fallbackMessage) {
        this(messageKey, messageArguments, fallbackMessage, RepairActionDescriptor.manualGuidance());
    }

    /// Creates a text-only proposal without localization arguments.
    ///
    /// @param messageKey localization key used by presentation layers
    /// @param fallbackMessage presentation-independent English repair text
    public TextSolver(String messageKey, String fallbackMessage) {
        this(messageKey, List.of(), fallbackMessage);
    }

    /// Configures one manual text repair step.
    ///
    /// @param configurator presentation-neutral step configurator
    @Override
    public void configure(SolverConfigurator configurator) {
        Objects.requireNonNull(configurator, "configurator")
                .setDescription(messageKey, messageArguments, fallbackMessage);
    }

    /// Advances to the next diagnosis after the text repair has been acknowledged.
    ///
    /// @param configurator presentation-neutral step configurator
    /// @param selectionId selected command identifier
    @Override
    public void callbackSelection(SolverConfigurator configurator, int selectionId) {
        Objects.requireNonNull(configurator, "configurator").transferTo(null);
    }
}

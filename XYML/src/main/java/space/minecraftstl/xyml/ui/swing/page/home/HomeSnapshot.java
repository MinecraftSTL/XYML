// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.home;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Immutable launcher-home state independent of a desktop toolkit.
///
/// Empty account or instance text represents a missing selection without a nullable UI contract.
///
/// @param accountName selected account display name, or empty when no account is selected
/// @param accountDetail short account provider or state text
/// @param instanceName selected instance display name, or empty when no instance is selected
/// @param instanceDetail short selected-instance version or loader text
/// @param statusText current launch readiness or operation status
/// @param launchEnabled whether the current selections can be launched
/// @param launching whether a launch command is currently active
/// @param launchCancellationRequested whether cancellation has already been requested for the active launch
/// @param selectionCommandsEnabled whether selection commands are available
@NotNullByDefault
public record HomeSnapshot(
        String accountName,
        String accountDetail,
        String instanceName,
        String instanceDetail,
        String statusText,
        boolean launchEnabled,
        boolean launching,
        boolean launchCancellationRequested,
        boolean selectionCommandsEnabled) {
    /// Creates a home snapshot using the historical default of no cancellation request.
    ///
    /// @param accountName selected account display name
    /// @param accountDetail account state text
    /// @param instanceName selected instance display name
    /// @param instanceDetail selected instance state text
    /// @param statusText current readiness text
    /// @param launchEnabled whether a new launch is accepted
    /// @param launching whether a launch is active
    /// @param selectionCommandsEnabled whether selection commands are available
    public HomeSnapshot(
            String accountName,
            String accountDetail,
            String instanceName,
            String instanceDetail,
            String statusText,
            boolean launchEnabled,
            boolean launching,
            boolean selectionCommandsEnabled) {
        this(
                accountName,
                accountDetail,
                instanceName,
                instanceDetail,
                statusText,
                launchEnabled,
                launching,
                false,
                selectionCommandsEnabled);
    }

    /// Validates one home snapshot.
    public HomeSnapshot {
        Objects.requireNonNull(accountName, "accountName");
        Objects.requireNonNull(accountDetail, "accountDetail");
        Objects.requireNonNull(instanceName, "instanceName");
        Objects.requireNonNull(instanceDetail, "instanceDetail");
        Objects.requireNonNull(statusText, "statusText");
        if (launching && launchEnabled) {
            throw new IllegalArgumentException("launching state cannot accept another launch command");
        }
    }
}

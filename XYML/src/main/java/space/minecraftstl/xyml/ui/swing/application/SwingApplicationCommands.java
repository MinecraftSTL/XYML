// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.application;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.ui.swing.page.accounts.AccountRefreshCommand;
import space.minecraftstl.xyml.ui.swing.page.home.HomeLaunchCommand;
import space.minecraftstl.xyml.ui.swing.page.home.HomeLaunchScriptExportCommand;

import java.util.Objects;

/// Supplies launcher workflows that the Swing composition invokes but does not own.
///
/// The startup layer decides how account creation and launch workflows are implemented. Instance
/// creation and management are owned by the Swing composition and are intentionally absent from
/// this workflow boundary.
///
/// @param addAccountCommand command that opens the supported add-account workflow
/// @param refreshAccountCommand command that refreshes one account without transferring authentication data to UI
/// @param launchCommand command that starts a session from captured stable selection identifiers
/// @param launchScriptExportCommand command that writes a local script from captured stable selection identifiers
@NotNullByDefault
public record SwingApplicationCommands(
        Runnable addAccountCommand,
        AccountRefreshCommand refreshAccountCommand,
        HomeLaunchCommand launchCommand,
        HomeLaunchScriptExportCommand launchScriptExportCommand) {
    /// Validates every startup-owned workflow boundary.
    public SwingApplicationCommands {
        Objects.requireNonNull(addAccountCommand, "addAccountCommand");
        Objects.requireNonNull(refreshAccountCommand, "refreshAccountCommand");
        Objects.requireNonNull(launchCommand, "launchCommand");
        Objects.requireNonNull(launchScriptExportCommand, "launchScriptExportCommand");
    }
}

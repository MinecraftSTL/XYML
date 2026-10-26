// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.home;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Localizable visible and accessible text for the launcher home page.
///
/// @param pageTitle page heading
/// @param accountLabel account-selection field label
/// @param missingAccountLabel value displayed when no account exists
/// @param instanceLabel instance-selection field label
/// @param missingInstanceLabel value displayed when no instance exists
/// @param addInstanceAction new-instance command label
/// @param exportLaunchScriptAction standalone launch-script command label
/// @param launchAction normal launch command label
/// @param launchingAction in-progress launch command label
/// @param cancelLaunchAction launch-cancellation command label
/// @param backToSelectionsAction command label for returning from task progress to launch selections
@NotNullByDefault
public record HomeStrings(
        String pageTitle,
        String accountLabel,
        String missingAccountLabel,
        String instanceLabel,
        String missingInstanceLabel,
        String addInstanceAction,
        String exportLaunchScriptAction,
        String launchAction,
        String launchingAction,
        String cancelLaunchAction,
        String backToSelectionsAction) {
    /// Validates localized home-page text.
    public HomeStrings {
        Objects.requireNonNull(pageTitle, "pageTitle");
        Objects.requireNonNull(accountLabel, "accountLabel");
        Objects.requireNonNull(missingAccountLabel, "missingAccountLabel");
        Objects.requireNonNull(instanceLabel, "instanceLabel");
        Objects.requireNonNull(missingInstanceLabel, "missingInstanceLabel");
        Objects.requireNonNull(addInstanceAction, "addInstanceAction");
        Objects.requireNonNull(exportLaunchScriptAction, "exportLaunchScriptAction");
        Objects.requireNonNull(launchAction, "launchAction");
        Objects.requireNonNull(launchingAction, "launchingAction");
        Objects.requireNonNull(cancelLaunchAction, "cancelLaunchAction");
        Objects.requireNonNull(backToSelectionsAction, "backToSelectionsAction");
    }
}

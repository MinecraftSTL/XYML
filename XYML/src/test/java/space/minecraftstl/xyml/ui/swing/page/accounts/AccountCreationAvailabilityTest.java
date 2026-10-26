// SPDX-FileCopyrightText: Copyright (C) 2025 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Verifies Microsoft configuration gating without creating windows or starting OAuth requests.
@NotNullByDefault
public final class AccountCreationAvailabilityTest {
    /// Missing Microsoft configuration disables only the Microsoft method.
    @Test
    public void missingClientIdDoesNotDisableOtherMethods() {
        assertFalse(SwingAccountCreationDialog.canStartAuthentication(AccountCreationMethod.MICROSOFT, false, false));
        assertTrue(SwingAccountCreationDialog.canStartAuthentication(AccountCreationMethod.OFFLINE, false, false));
        assertTrue(SwingAccountCreationDialog.canStartAuthentication(AccountCreationMethod.AUTHLIB_INJECTOR, false, false));
    }

    /// Active operation ownership prevents every method from starting another authentication.
    @Test
    public void busyDialogBlocksAllMethods() {
        for (AccountCreationMethod method : AccountCreationMethod.values()) {
            assertFalse(SwingAccountCreationDialog.canStartAuthentication(method, true, false));
            assertFalse(SwingAccountCreationDialog.canStartAuthentication(method, true, true));
        }
        assertTrue(SwingAccountCreationDialog.canStartAuthentication(AccountCreationMethod.MICROSOFT, false, true));
    }

    /// The unavailable flow exposes an explicit configuration error rather than the ordinary sign-in instructions.
    @Test
    public void missingConfigurationUsesLocalizedErrorHint() {
        assertEquals("<html>" + i18n("account.methods.microsoft.unavailable") + "</html>",
                SwingAccountCreationDialog.createMicrosoftConfigurationHint(false).getText());
        assertEquals("<html>" + i18n("account.methods.microsoft.hint") + "</html>",
                SwingAccountCreationDialog.createMicrosoftConfigurationHint(true).getText());
    }
}

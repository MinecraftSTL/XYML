/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2025 huangyuhui <huanghongxun2008@126.com> and contributors
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

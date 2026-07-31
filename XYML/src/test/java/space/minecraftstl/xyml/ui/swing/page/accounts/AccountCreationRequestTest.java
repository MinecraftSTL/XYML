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
package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests method-specific account request invariants without a graphical environment.
@NotNullByDefault
public final class AccountCreationRequestTest {
    /// Factory methods populate only fields belonging to their authentication method.
    @Test
    public void factoriesCreateMethodSpecificRequests() {
        AccountCreationRequest offline = AccountCreationRequest.offline("Alex", null, false);
        AccountCreationRequest microsoft = AccountCreationRequest.microsoft(
                MicrosoftAccountLoginMode.BROWSER,
                true);
        AccountCreationRequest authlib = AccountCreationRequest.authlibInjector(
                "https://example.test/",
                "user@example.test",
                "secret",
                false);

        assertAll(
                () -> assertEquals(AccountCreationMethod.OFFLINE, offline.method()),
                () -> assertEquals("Alex", offline.username()),
                () -> assertNull(offline.password()),
                () -> assertEquals(MicrosoftAccountLoginMode.BROWSER, microsoft.microsoftLoginMode()),
                () -> assertNull(microsoft.username()),
                () -> assertEquals(AccountCreationMethod.AUTHLIB_INJECTOR, authlib.method()),
                () -> assertEquals("https://example.test/", authlib.authlibServerUrl()),
                () -> assertEquals("secret", authlib.password()));
    }

    /// Blank required fields and irrelevant non-null fields are rejected immediately.
    @Test
    public void rejectsMalformedMethodPayloads() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> AccountCreationRequest.offline(" ", null, false)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> AccountCreationRequest.authlibInjector(
                                "https://example.test/",
                                "user",
                                "",
                                false)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new AccountCreationRequest(
                                AccountCreationMethod.MICROSOFT,
                                "unexpected",
                                null,
                                null,
                                null,
                                MicrosoftAccountLoginMode.BROWSER,
                                false)));
    }
}

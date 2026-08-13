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
package space.minecraftstl.xyml.auth.authlibinjector;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies toolkit-neutral authlib-injector integration URL parsing.
@NotNullByDefault
public final class AuthlibInjectorUrlTest {
    /// Decodes the server URL carried by a supported integration payload.
    @Test
    public void parsesYggdrasilServerUrl() {
        assertEquals(
                "https://example.com/api",
                AuthlibInjectorUrl.parse(
                                "authlib-injector:yggdrasil-server:https%3A%2F%2Fexample.com%2Fapi")
                        .orElseThrow());
    }

    /// Accepts the direct HTTPS endpoint carried by browser drag buttons.
    @Test
    public void parsesDirectBrowserEndpoint() {
        assertEquals(
                "https://home.minecraftstl.space:8880/api/yggdrasil",
                AuthlibInjectorUrl.parse(
                                "  https://home.minecraftstl.space:8880/api/yggdrasil  ")
                        .orElseThrow());
        assertEquals(
                "http://example.com/api/yggdrasil/",
                AuthlibInjectorUrl.parse("http://example.com/api/yggdrasil/").orElseThrow());
    }

    /// Rejects absent text and unsupported integration paths.
    @Test
    public void rejectsUnsupportedPayloads() {
        assertTrue(AuthlibInjectorUrl.parse(null).isEmpty());
        assertTrue(AuthlibInjectorUrl.parse("authlib-injector:unsupported:value").isEmpty());
        assertTrue(AuthlibInjectorUrl.parse("https://example.com/api").isEmpty());
        assertTrue(AuthlibInjectorUrl.parse("https://example.com/api/yggdrasil/other").isEmpty());
        assertTrue(AuthlibInjectorUrl.parse("https://user@example.com/api/yggdrasil").isEmpty());
        assertTrue(AuthlibInjectorUrl.parse("javascript:alert(1)").isEmpty());
    }
}

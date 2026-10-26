// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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

    /// Formats secure authentication endpoints without redundant scheme, default port, or conventional path.
    @Test
    public void formatsAuthenticationServerDisplayAddress() {
        assertEquals(
                "example.com",
                new AuthlibInjectorServer("https://example.com/api/yggdrasil/").getDisplayHostUrl());
        assertEquals(
                "example.com:8443/custom/path/",
                new AuthlibInjectorServer("https://example.com:8443/custom/path/").getDisplayHostUrl());
        assertEquals(
                "http://example.com/api/yggdrasil/",
                new AuthlibInjectorServer("http://example.com/api/yggdrasil/").getDisplayHostUrl());
    }

    /// Rejects absent text, direct endpoints, and unsupported integration paths.
    @Test
    public void rejectsUnsupportedPayloads() {
        assertTrue(AuthlibInjectorUrl.parse(null).isEmpty());
        assertTrue(AuthlibInjectorUrl.parse("authlib-injector:unsupported:value").isEmpty());
        assertTrue(AuthlibInjectorUrl.parse(
                "https://home.minecraftstl.space:8880/api/yggdrasil").isEmpty());
        assertTrue(AuthlibInjectorUrl.parse("javascript:alert(1)").isEmpty());
    }
}

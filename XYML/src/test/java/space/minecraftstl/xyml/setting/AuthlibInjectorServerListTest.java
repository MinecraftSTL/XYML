// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.setting;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import space.minecraftstl.xyml.auth.authlibinjector.AuthlibInjectorServer;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// Tests for detached authlib-injector server lists.
@NotNullByDefault
public final class AuthlibInjectorServerListTest {
    /// Tests that newly created server lists contain LittleSkin by default.
    @Test
    public void defaultListContainsLittleSkin() {
        AuthlibInjectorServerList list = AuthlibInjectorServerList.createDefault();

        assertEquals(1, list.getServers().size());
        assertEquals(AuthlibInjectorServerList.LITTLE_SKIN_URL, list.getServers().get(0).getUrl());
    }

    /// Tests that server list serialization does not include derived metadata.
    @Test
    public void serializesServerListWithoutMetadata() {
        AuthlibInjectorServer server = new AuthlibInjectorServer("https://example.com/api/yggdrasil/");
        server.restoreMetadataCache("""
                {"meta":{"serverName":"Example"}}
                """, 123L);
        AuthlibInjectorServerList list = new AuthlibInjectorServerList();
        list.getServers().add(server);

        JsonObject serialized = JsonParser.parseString(
                LauncherSettings.SETTINGS_GSON.toJson(list, AuthlibInjectorServerList.class)).getAsJsonObject();
        JsonObject serializedServer = serialized.getAsJsonArray("servers").get(0).getAsJsonObject();

        assertEquals("https://example.com/api/yggdrasil/", serializedServer.get("url").getAsString());
        assertFalse(serializedServer.has("name"));
        assertFalse(serializedServer.has("metadataResponse"));
        assertFalse(serializedServer.has("metadataTimestamp"));
    }

}

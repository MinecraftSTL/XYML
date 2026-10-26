// SPDX-FileCopyrightText: Copyright (C) 2020 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.auth.authlibinjector;

import com.google.gson.JsonObject;
import space.minecraftstl.xyml.auth.AccountFactory;
import space.minecraftstl.xyml.auth.AuthenticationException;
import space.minecraftstl.xyml.auth.CharacterSelector;

import java.util.Objects;

public class BoundAuthlibInjectorAccountFactory extends AccountFactory<AuthlibInjectorAccount> {
    private final AuthlibInjectorArtifactProvider downloader;
    private final AuthlibInjectorServer server;

    /**
     * @param server Authlib-Injector Server
     */
    public BoundAuthlibInjectorAccountFactory(AuthlibInjectorArtifactProvider downloader, AuthlibInjectorServer server) {
        this.downloader = downloader;
        this.server = server;
    }

    @Override
    public AccountLoginType getLoginType() {
        return AccountLoginType.USERNAME_PASSWORD;
    }

    public AuthlibInjectorServer getServer() {
        return server;
    }

    @Override
    public AuthlibInjectorAccount create(CharacterSelector selector, String username, String password, ProgressCallback progressCallback, Object additionalData) throws AuthenticationException {
        Objects.requireNonNull(selector);
        Objects.requireNonNull(username);
        Objects.requireNonNull(password);

        return new AuthlibInjectorAccount(server, downloader, username, password, selector);
    }

    @Override
    public AuthlibInjectorAccount fromStorage(JsonObject metadata, JsonObject privateData) {
        return AuthlibInjectorAccountFactory.fromStorage(metadata, privateData, downloader, server);
    }
}

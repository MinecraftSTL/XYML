// SPDX-FileCopyrightText: Copyright (C) 2020 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.auth.microsoft;

import com.google.gson.JsonObject;
import space.minecraftstl.xyml.auth.Account;
import space.minecraftstl.xyml.auth.AccountFactory;
import space.minecraftstl.xyml.auth.AccountID;
import space.minecraftstl.xyml.auth.AuthenticationException;
import space.minecraftstl.xyml.auth.CharacterSelector;
import space.minecraftstl.xyml.auth.OAuth;

import java.util.Objects;

public class MicrosoftAccountFactory extends AccountFactory<MicrosoftAccount> {

    private final MicrosoftService service;

    public MicrosoftAccountFactory(MicrosoftService service) {
        this.service = service;
    }

    @Override
    public AccountLoginType getLoginType() {
        return AccountLoginType.NONE;
    }

    @Override
    public MicrosoftAccount create(CharacterSelector selector, String username, String password, ProgressCallback progressCallback, Object additionalData) throws AuthenticationException {
        return new MicrosoftAccount(service, (OAuth.GrantFlow) additionalData);
    }

    @Override
    public MicrosoftAccount fromStorage(JsonObject metadata, JsonObject privateData) {
        Objects.requireNonNull(metadata);
        Objects.requireNonNull(privateData);
        AccountID accountID = Account.readAccountID(metadata);
        MicrosoftSession session = MicrosoftSession.fromStorage(metadata, privateData);
        return new MicrosoftAccount(accountID, service, session);
    }
}

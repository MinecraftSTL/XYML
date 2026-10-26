// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.auth.yggdrasil;

import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.auth.ServerResponseMalformedException;
import space.minecraftstl.xyml.auth.authlibinjector.AuthlibInjectorProvider;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests malformed response handling in the Yggdrasil authentication client.
@NotNullByDefault
public final class YggdrasilServiceTest {

    /// Reports an HTML error response without exposing Gson's object-shape exception.
    @Test
    public void rejectsHtmlErrorResponseBeforeObjectDeserialization() throws Exception {
        byte[] response = "<!doctype html><title>Forbidden</title>".getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/authserver/validate", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(403, response.length);
            try {
                exchange.getResponseBody().write(response);
            } finally {
                exchange.close();
            }
        });
        server.start();

        try {
            String apiRoot = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            YggdrasilService service = new YggdrasilService(new AuthlibInjectorProvider(apiRoot));

            ServerResponseMalformedException exception = assertThrows(
                    ServerResponseMalformedException.class,
                    () -> service.validate("access-token"));

            assertTrue(exception.getMessage().startsWith(
                    "Expected a JSON object response, but received: <!doctype html>"));
            assertNull(exception.getCause());
        } finally {
            server.stop(0);
        }
    }
}

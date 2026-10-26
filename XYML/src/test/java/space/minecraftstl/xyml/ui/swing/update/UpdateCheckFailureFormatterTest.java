// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.update;

import com.google.gson.JsonSyntaxException;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.upgrade.SelfVerificationException;

import java.io.IOException;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies distinct safe failure presentation and asynchronous wrapper handling.
@NotNullByDefault
public final class UpdateCheckFailureFormatterTest {
    /// Categories remain distinct without rendering sensitive exception messages.
    @Test
    void categorizesWithoutExceptionPayload() {
        String integrity = UpdateCheckFailureFormatter.format(new CompletionException(new SelfVerificationException()));
        String network = UpdateCheckFailureFormatter.format(new IOException("token=private-fixture"));
        String response = UpdateCheckFailureFormatter.format(new JsonSyntaxException("private-fixture"));
        assertAll(() -> assertNotEquals(integrity, network), () -> assertNotEquals(network, response),
                () -> assertFalse(network.contains("private-fixture")),
                () -> assertFalse(response.contains("private-fixture")));
    }
}

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

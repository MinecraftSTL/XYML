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
package space.minecraftstl.xyml.ai;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/// Verifies that the stdio server observes client disconnects.
@NotNullByDefault
final class XYMLMcpStdioLifecycleTest {

    /// Confirms that closing the client input unblocks the server's termination wait.
    @Test
    void awaitTerminationReturnsAfterClientClosesInput() throws Exception {
        try (PipedInputStream serverInput = new PipedInputStream(1024);
             PipedOutputStream clientInput = new PipedOutputStream(serverInput)) {
            XYMLMcpServer server = new XYMLMcpServer(
                    null, serverInput, new ByteArrayOutputStream());
            try {
                clientInput.close();
                assertTimeoutPreemptively(Duration.ofSeconds(2), server::awaitTermination);
            } finally {
                server.close();
            }
        }
    }
}

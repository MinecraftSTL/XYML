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
package space.minecraftstl.xyml.gradle.pack;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/// Verifies Windows system-proxy normalization used by release-branch fetches.
@NotNullByDefault
final class GitBranchGradleTaskTest {
    /// Accepts direct and protocol-specific Windows proxy values without exposing credentials in logs.
    @Test
    void normalizesWindowsProxyServer() {
        assertEquals("http://127.0.0.1:7867", GitBranchGradleTask.normalizeProxyServer("127.0.0.1:7867"));
        assertEquals(
                "http://secure.example:8443",
                GitBranchGradleTask.normalizeProxyServer("http=plain.example:8080;https=secure.example:8443"));
        assertEquals("socks5://127.0.0.1:1080", GitBranchGradleTask.normalizeProxyServer("socks5://127.0.0.1:1080"));
        assertNull(GitBranchGradleTask.normalizeProxyServer(""));
        assertNull(GitBranchGradleTask.normalizeProxyServer(null));
    }
}

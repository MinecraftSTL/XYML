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

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies local branch selection for isolated channel builds.
@NotNullByDefault
final class GitBranchGradleTaskTest {
    /// Resolves all channel builds from local branch refs.
    @Test
    void resolvesLocalBranchRefs() {
        assertEquals("refs/heads/main", GitBranchGradleTask.localBranchRef("main"));
        assertEquals("refs/heads/beta", GitBranchGradleTask.localBranchRef("beta"));
        assertEquals("refs/heads/alpha", GitBranchGradleTask.localBranchRef("alpha"));
        assertEquals("refs/heads/dev", GitBranchGradleTask.localBranchRef("dev"));
    }

    /// Enables system-proxy discovery only for the Windows Wrapper command.
    @Test
    void configuresNestedGradleCommands() {
        Path checkout = Path.of("checkout");
        List<String> arguments = List.of("clean", "build");

        assertEquals(
                List.of(
                        "cmd.exe",
                        "/d",
                        "/c",
                        checkout.resolve("gradlew.bat").toString(),
                        "-Djava.net.useSystemProxies=true",
                        "clean",
                        "build"),
                GitBranchGradleTask.nestedGradleCommand(checkout, true, arguments));
        assertEquals(
                List.of(checkout.resolve("gradlew").toString(), "clean", "build"),
                GitBranchGradleTask.nestedGradleCommand(checkout, false, arguments));
    }
}

// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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

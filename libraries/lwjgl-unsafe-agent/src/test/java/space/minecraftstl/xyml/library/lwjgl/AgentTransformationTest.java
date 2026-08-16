/*
 * Copyright 2026 MinecraftSTL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Added by MinecraftSTL in 2026 for process-level agent verification.
package space.minecraftstl.xyml.library.lwjgl;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Starts a separate Java 25 process and verifies that the packaged javaagent transforms its target class.
@NotNullByDefault
public final class AgentTransformationTest {
    /// Verifies the manifest entry point, module export, bytecode rewrite, and resulting native-memory access.
    ///
    /// @throws Exception when the child process cannot be started or observed
    @Test
    void packagedAgentTransformsMemoryUtilInChildProcess() throws Exception {
        String agentJar = Objects.requireNonNull(System.getProperty("xyml.lwjglUnsafeAgent.jar"));
        String testClasspath = Objects.requireNonNull(
                System.getProperty("xyml.lwjglUnsafeAgent.testClasspath"));
        Process process = new ProcessBuilder(
                javaExecutable().toString(),
                "--enable-native-access=ALL-UNNAMED",
                "-javaagent:" + agentJar,
                "-classpath",
                testClasspath,
                Probe.class.getName())
                .redirectErrorStream(true)
                .start();

        boolean completed = process.waitFor(30, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly().waitFor();
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(completed, () -> "Agent probe timed out:\n" + output);
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("Successfully transformed MemoryUtil"), output);
        assertTrue(output.contains("AGENT_PROBE_OK"), output);
    }

    /// Locates the Java executable of the Java 25 test runtime selected by the project toolchain.
    ///
    /// @return Java executable path
    private static Path javaExecutable() {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
    }

    /// Child-process entry point that exercises transformed native-memory accessors.
    @NotNullByDefault
    public static final class Probe {
        /// Prevents construction of the static probe class.
        private Probe() {
        }

        /// Writes and reads a native integer through the transformed fixture.
        ///
        /// @param ignoredArguments unused process arguments
        public static void main(String[] ignoredArguments) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment segment = arena.allocate(ValueLayout.JAVA_INT);
                MemoryUtil.memPutInt(segment.address(), 0x58594D4C);
                int value = MemoryUtil.memGetInt(segment.address());
                if (value != 0x58594D4C) {
                    throw new AssertionError("Unexpected native-memory value: " + Integer.toHexString(value));
                }
            }
            System.out.println("AGENT_PROBE_OK");
        }
    }
}

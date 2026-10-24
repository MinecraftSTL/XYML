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
package space.minecraftstl.xyml.upgrade;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Ensures the presentation-origin bypass does not disable actual artifact signature checks.
@NotNullByDefault
public final class IntegrityCheckerBuildStatusTest {
    /// Classification remains a single unconditional label without filling the actual verification cache.
    @Test
    public void classificationDoesNotPopulateArtifactVerificationCache() {
        @Nullable Boolean cached = IntegrityChecker.getSelfVerified();
        assertEquals(IntegrityChecker.BuildStatus.OFFICIAL, IntegrityChecker.verifyBuildSignature());
        assertEquals(cached, IntegrityChecker.getSelfVerified());
    }

    /// Actual verification rejects unsigned JARs even while the presentation classification is accepted.
    ///
    /// @param directory isolated temporary directory supplied by JUnit
    /// @throws IOException if the test JAR cannot be written
    @Test
    public void unsignedJarStillFailsVerification(@TempDir Path directory) throws IOException {
        Path artifact = directory.resolve("unsigned.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(artifact))) {
            jar.putNextEntry(new JarEntry("payload.txt"));
            jar.write(new byte[] {1, 2, 3});
            jar.closeEntry();
        }
        assertEquals("Signature is missing", assertThrows(IOException.class,
                () -> IntegrityChecker.verifyJar(artifact)).getMessage());
        assertEquals(IntegrityChecker.BuildStatus.OFFICIAL, IntegrityChecker.verifyBuildSignature());
    }

    /// Actual verification rejects a forged signature without changing the presentation classification.
    ///
    /// @param directory isolated temporary directory supplied by JUnit
    /// @throws IOException if the test JAR cannot be written
    @Test
    public void forgedJarSignatureStillFailsVerification(@TempDir Path directory) throws IOException {
        Path artifact = directory.resolve("forged.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(artifact))) {
            jar.putNextEntry(new JarEntry("META-INF/xyml_signature"));
            jar.write(new byte[] {1, 2, 3});
            jar.closeEntry();
        }
        assertThrows(IOException.class, () -> IntegrityChecker.verifyJar(artifact));
        assertEquals(IntegrityChecker.BuildStatus.OFFICIAL, IntegrityChecker.verifyBuildSignature());
    }
}

// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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

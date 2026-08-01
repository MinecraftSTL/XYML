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
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the headless argument, naming, command, and result policies used by [UpdateApplier].
@NotNullByDefault
class UpdateApplierTest {
    /// Recognizes only the exact two-element apply instruction.
    @Test
    void recognizesExactApplyInstruction() {
        assertEquals(
                Path.of("target.jar"),
                UpdateApplier.findApplyTarget(new String[]{"--apply-to", "target.jar"})
                        .orElseThrow());

        assertTrue(UpdateApplier.findApplyTarget(new String[]{}).isEmpty());
        assertTrue(UpdateApplier.findApplyTarget(new String[]{"--apply-to"}).isEmpty());
        assertTrue(UpdateApplier.findApplyTarget(
                new String[]{"--apply-to", "target.jar", "unexpected"}).isEmpty());
        assertTrue(UpdateApplier.findApplyTarget(
                new String[]{"--different", "target.jar"}).isEmpty());
    }

    /// Updates matching XYML filenames while preserving their prefix style and suffix.
    @Test
    void derivesVersionedTargetName() {
        assertEquals(
                Path.of("downloads", "XYML-4.2.0.jar"),
                UpdateApplier.tryRename(
                                Path.of("downloads", "XYML-3.5.0.jar"),
                                "4.2.0")
                        .orElseThrow());
        assertEquals(
                Path.of("xyml.4.2.0.exe"),
                UpdateApplier.tryRename(Path.of("xyml.3.5.0.exe"), "4.2.0")
                        .orElseThrow());
    }

    /// Leaves current-version and unrelated artifact names untouched.
    @Test
    void skipsUnnecessaryOrUnsupportedRename() {
        assertTrue(UpdateApplier.tryRename(Path.of("XYML-4.2.0.jar"), "4.2.0").isEmpty());
        assertTrue(UpdateApplier.tryRename(Path.of("HMCL-3.5.0.jar"), "4.2.0").isEmpty());
        assertTrue(UpdateApplier.tryRename(Path.of("XYML-latest.jar"), "4.2.0").isEmpty());
    }

    /// Preserves only established VM argument families and appends immutable application arguments.
    @Test
    void buildsCompatibleJavaCommand() {
        Path javaBinary = Path.of("runtime", "bin", "java");
        Path launcher = Path.of("downloads", "launcher.jar");
        @Unmodifiable List<String> command = UpdateApplier.buildJavaCommand(
                javaBinary,
                List.of(
                        "-Xmx2G",
                        "-Dxyml.test=true",
                        "--add-opens=java.base/java.lang=ALL-UNNAMED",
                        "-ea"),
                launcher,
                List.of("--apply-to", "current.jar"));

        assertEquals(
                List.of(
                        javaBinary.toString(),
                        "-Xmx2G",
                        "-Dxyml.test=true",
                        "-jar",
                        launcher.toAbsolutePath().toString(),
                        "--apply-to",
                        "current.jar"),
                command);
        assertThrows(UnsupportedOperationException.class, () -> command.add("unexpected"));
    }

    /// Masks inherited proxy and service credentials only in the command line rendered for logs.
    @Test
    void masksSensitiveJavaCommandProperties() {
        assertEquals(
                "java -Dhttp.proxyPassword=s***** -Dxyml.microsoft.auth.id=c******** "
                        + "-Dxyml.curseforge.apikey= -Dxyml.test=visible -jar XYML.jar",
                UpdateApplier.maskCommandLine(List.of(
                        "java",
                        "-Dhttp.proxyPassword=secret",
                        "-Dxyml.microsoft.auth.id=client-id",
                        "-Dxyml.curseforge.apikey=",
                        "-Dxyml.test=visible",
                        "-jar",
                        "XYML.jar")));
    }

    /// Normalizes only non-blank native launcher paths published by jpackage.
    @Test
    void resolvesPackagedApplicationPath() {
        assertEquals(
                Path.of("native", "XYML").toAbsolutePath().normalize(),
                UpdateApplier.packagedApplicationPath(Path.of("native", "XYML").toString())
                        .orElseThrow());
        assertTrue(UpdateApplier.packagedApplicationPath(null).isEmpty());
        assertTrue(UpdateApplier.packagedApplicationPath("  ").isEmpty());
    }

    /// Exposes consistent semantic startup outcomes without carrying presentation types.
    @Test
    void modelsStartupOutcomes() {
        UpdateStartupResult continuing = UpdateStartupResult.continueLaunch();
        assertFalse(continuing.shouldExit());
        assertNull(continuing.notice());
        assertNull(continuing.failure());
        assertSame(continuing, UpdateStartupResult.continueLaunch());

        UpdateStartupResult notice = UpdateStartupResult.exitWithNotice(
                UpdateStartupResult.Notice.MANUAL_REBOOT_REQUIRED);
        assertTrue(notice.shouldExit());
        assertEquals(UpdateStartupResult.Notice.MANUAL_REBOOT_REQUIRED, notice.notice());
        assertNull(notice.failure());

        IOException failure = new IOException("apply failed");
        UpdateStartupResult failed = UpdateStartupResult.failed(failure);
        assertTrue(failed.shouldExit());
        assertEquals(UpdateStartupResult.Notice.APPLY_FAILED, failed.notice());
        assertSame(failure, failed.failure());
        assertThrows(
                IllegalArgumentException.class,
                () -> UpdateStartupResult.exitWithNotice(
                        UpdateStartupResult.Notice.APPLY_FAILED));
    }
}

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
package space.minecraftstl.xyml.game.analyzer;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.game.ExportedCrashBundle;
import space.minecraftstl.xyml.game.ExportedCrashBundleText;
import space.minecraftstl.xyml.util.platform.Bits;
import space.minecraftstl.xyml.util.platform.OperatingSystem;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/// Verifies conservative metadata recovery from validated exported diagnostic text.
@NotNullByDefault
final class ExportedCrashBundleContextParserTest {
    /// Standard crash-report fields recover only game, selected Java, and VM bitness.
    @Test
    void recoversStandardCrashReportMetadataWithoutCapabilities() {
        LogAnalyzable context = parse("""
                ---- Minecraft Crash Report ----
                \tMinecraft Version: 1.7.10
                \tJava Version: 1.8.0_202, Oracle Corporation
                \tJava VM Version: Java HotSpot(TM) 64-Bit Server VM (mixed mode), Oracle Corporation
                """);

        assertEquals("1.7.10", context.gameVersion());
        assertEquals(8, context.currentJavaVersion());
        assertEquals(Bits.BIT_64, context.javaBits());
        assertNull(context.requiredJavaVersion());
        assertNull(context.mainClass());
        assertEquals(OperatingSystem.UNKNOWN, context.operatingSystem());
        assertEquals(-1, context.systemCodePage());
        assertNull(context.gameDirectory());
        assertNull(context.javaPath());
        assertNull(context.maxMemoryMiB());
        assertNull(context.javaRuntimeRepair());
        assertNull(context.missingDependencySearch());
    }

    /// Explicit class-file compatibility evidence proves both required and selected Java majors.
    @Test
    void recoversExplicitClassVersionRequirement() {
        LogAnalyzable context = parse("""
                java.lang.UnsupportedClassVersionError: example/Entry has been compiled by a more recent version
                of the Java Runtime (class file version 61.0), this version of the Java Runtime only recognizes
                class file versions up to 52.0
                """.replace("\n", " "));

        assertEquals(17, context.requiredJavaVersion());
        assertEquals(8, context.currentJavaVersion());
    }

    /// Conflicting physical logs fail closed instead of borrowing metadata from one launch.
    @Test
    void conflictingMetadataDegradesToUnknown() {
        ExportedCrashBundle bundle = bundle(List.of(
                text("""
                        Minecraft Version: 1.7.10
                        Java Version: 1.8.0_202, Oracle Corporation
                        Java VM Version: Java HotSpot(TM) 32-Bit Server VM, Oracle Corporation
                        """, "crash-reports/old.txt"),
                text("""
                        Loading Minecraft 1.20.4 with Fabric Loader 0.15.6
                        Java Version: 17.0.10, Eclipse Adoptium
                        Java VM Version: OpenJDK 64-Bit Server VM, Eclipse Adoptium
                        """, "minecraft.log")));

        LogAnalyzable context = ExportedCrashBundleContextParser.parse(bundle);

        assertNull(context.gameVersion());
        assertNull(context.currentJavaVersion());
        assertEquals(Bits.UNKNOWN, context.javaBits());
    }

    /// Adjacent version and architecture prose does not create launch metadata or Java requirements.
    @Test
    void ignoresUnqualifiedVersionAndArchitectureProse() {
        LogAnalyzable context = parse("""
                Recommended Minecraft version 1.7.10
                This documentation mentions Java 8 and Java 17.
                WSOCK32.dll:Windows Socket 32-Bit DLL
                class file version 61.0
                """);

        assertNull(context.gameVersion());
        assertNull(context.currentJavaVersion());
        assertNull(context.requiredJavaVersion());
        assertEquals(Bits.UNKNOWN, context.javaBits());
    }

    /// Entry names are provenance only and can never supply analysis context.
    @Test
    void ignoresMetadataShapedEntryNames() {
        ExportedCrashBundle bundle = bundle(List.of(new ExportedCrashBundleText(
                ExportedCrashBundleText.Kind.LOG,
                "ordinary diagnostic output",
                List.of("Java Version: 17/Minecraft Version: 1.20.4.log"))));

        LogAnalyzable context = ExportedCrashBundleContextParser.parse(bundle);

        assertNull(context.gameVersion());
        assertNull(context.currentJavaVersion());
        assertEquals(Bits.UNKNOWN, context.javaBits());
    }

    /// Parses one crash-report body in a canonical immutable bundle.
    private static LogAnalyzable parse(String content) {
        return ExportedCrashBundleContextParser.parse(bundle(List.of(text(
                content,
                "crash-reports/crash-test.txt"))));
    }

    /// Creates one immutable canonical test bundle.
    private static ExportedCrashBundle bundle(List<ExportedCrashBundleText> texts) {
        return new ExportedCrashBundle(
                Path.of("minecraft-exported-crash-info-context-test.zip"),
                texts);
    }

    /// Creates one immutable crash-report text fixture.
    private static ExportedCrashBundleText text(String content, String source) {
        return new ExportedCrashBundleText(
                ExportedCrashBundleText.Kind.CRASH_REPORT,
                content,
                List.of(source));
    }
}

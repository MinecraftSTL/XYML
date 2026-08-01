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
package space.minecraftstl.xyml.ui.swing;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/// Verifies offline launcher-font precedence and fontconfig family selection without using installed fonts.
@NotNullByDefault
final class SwingLauncherFontManagerTest {
    /// Isolated filesystem root for fallback discovery tests.
    @TempDir
    private Path temporaryDirectory;

    /// Launcher-local localized WOFF files take precedence over user and legacy default files.
    @Test
    void prefersLauncherLocalLocalizedFont() throws IOException {
        SwingLauncherFontManager.FontDiscoveryPaths paths = discoveryPaths();
        Path localized = createFile(paths.localHome().resolve("font/font_zh_Hans_CN.woff"));
        createFile(paths.userHome().resolve("font/font_zh_Hans_CN.ttf"));
        createFile(paths.localHome().resolve("font.ttf"));
        List<Path> attempts = new ArrayList<>();
        AtomicInteger fontMatchCalls = new AtomicInteger();

        @Nullable String family = SwingLauncherFontManager.discoverFallback(
                paths,
                List.of(Locale.forLanguageTag("zh-Hans-CN"), Locale.ROOT),
                file -> {
                    attempts.add(file);
                    return List.of("Localized UI");
                },
                "zh-CN",
                pattern -> {
                    fontMatchCalls.incrementAndGet();
                    return "Fontconfig";
                });

        assertEquals("Localized UI", family);
        assertEquals(List.of(localized.toAbsolutePath().normalize()), attempts);
        assertEquals(0, fontMatchCalls.get());
    }

    /// User localized defaults retain precedence over launcher-local legacy root files.
    @Test
    void prefersUserLocalizedFontBeforeLegacyFiles() throws IOException {
        SwingLauncherFontManager.FontDiscoveryPaths paths = discoveryPaths();
        Path userLocalized = createFile(paths.userHome().resolve("font/font.otf"));
        createFile(paths.localHome().resolve("font.ttf"));
        List<Path> attempts = new ArrayList<>();

        @Nullable String family = SwingLauncherFontManager.discoverFallback(
                paths,
                List.of(Locale.ROOT),
                file -> {
                    attempts.add(file);
                    return List.of("User Localized");
                },
                "",
                pattern -> null);

        assertEquals("User Localized", family);
        assertEquals(List.of(userLocalized.toAbsolutePath().normalize()), attempts);
    }

    /// An unreadable higher-priority extension falls through to the next usable local format.
    @Test
    void continuesAfterUnusableFontFile() throws IOException {
        SwingLauncherFontManager.FontDiscoveryPaths paths = discoveryPaths();
        Path ttf = createFile(paths.localHome().resolve("font.ttf"));
        Path otf = createFile(paths.localHome().resolve("font.otf"));
        List<Path> attempts = new ArrayList<>();

        @Nullable String family = SwingLauncherFontManager.discoverFallback(
                paths,
                List.of(Locale.ROOT),
                file -> {
                    attempts.add(file);
                    return file.endsWith("font.ttf") ? List.of() : List.of("OpenType Fallback");
                },
                "",
                pattern -> null);

        assertEquals("OpenType Fallback", family);
        assertEquals(List.of(
                ttf.toAbsolutePath().normalize(),
                otf.toAbsolutePath().normalize()), attempts);
    }

    /// Fontconfig runs only after all local paths fail and receives the exact locale pattern.
    @Test
    void fallsBackToFontconfigAfterLocalPaths() {
        SwingLauncherFontManager.FontDiscoveryPaths paths = discoveryPaths();
        List<String> patterns = new ArrayList<>();

        @Nullable String family = SwingLauncherFontManager.discoverFallback(
                paths,
                List.of(Locale.ROOT),
                file -> List.of(),
                ":lang=zh-CN:charset=0x6e38,0x620f",
                pattern -> {
                    patterns.add(pattern);
                    return "Noto Sans CJK SC";
                });

        assertEquals("Noto Sans CJK SC", family);
        assertEquals(List.of(":lang=zh-CN:charset=0x6e38,0x620f"), patterns);
    }

    /// Fontconfig output selects the matching family from multi-family files and falls back predictably.
    @Test
    void selectsFamilyReportedByFontconfig() {
        Path font = temporaryDirectory.resolve("collection.ttc").toAbsolutePath().normalize();

        assertEquals(
                "Noto Sans CJK SC",
                SwingLauncherFontManager.selectFcMatchResult(
                        "Noto Sans CJK SC,Noto Sans CJK SC Medium\n" + font,
                        file -> List.of("Noto Sans CJK SC Medium", "Noto Sans CJK SC")));
        assertEquals(
                "First Available",
                SwingLauncherFontManager.selectFcMatchResult(
                        "Missing Family\n" + font,
                        file -> List.of("First Available")));
        assertNull(SwingLauncherFontManager.selectFcMatchResult("invalid", file -> List.of("Unused")));
    }

    /// Creates normalized isolated discovery roots.
    ///
    /// @return test discovery roots
    private SwingLauncherFontManager.FontDiscoveryPaths discoveryPaths() {
        return new SwingLauncherFontManager.FontDiscoveryPaths(
                temporaryDirectory.resolve("local"),
                temporaryDirectory.resolve("current"),
                temporaryDirectory.resolve("user"),
                temporaryDirectory.resolve("jar"));
    }

    /// Creates one empty local font candidate and all parent directories.
    ///
    /// @param file candidate path
    /// @return created candidate
    /// @throws IOException when the temporary filesystem rejects the write
    private static Path createFile(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.createFile(file);
    }
}

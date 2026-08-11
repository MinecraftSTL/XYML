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
package space.minecraftstl.xyml.ui.swing.page.settings.theme;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import space.minecraftstl.xyml.theme.NetworkBackgroundImageCachePolicy;
import space.minecraftstl.xyml.theme.ResolvedTheme;
import space.minecraftstl.xyml.theme.ThemeBackground;
import space.minecraftstl.xyml.theme.ThemePackManifest;
import space.minecraftstl.xyml.ui.swing.SwingBackgroundSource;
import space.minecraftstl.xyml.ui.swing.SwingWindowAppearanceRequest;
import space.minecraftstl.xyml.util.gson.JsonUtils;

import javax.swing.SwingUtilities;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/// Verifies current-theme export defaults, asynchronous publication, and portable metadata.
@NotNullByDefault
public final class CurrentThemePackExportServiceTest {
    /// Temporary archive target.
    @TempDir
    private Path temporaryDirectory;

    /// Selected account names take precedence and every metadata field is prefilled.
    @Test
    public void defaultsPreferSelectedAccountAndPrefillMetadata() throws Exception {
        CurrentThemePackExportService service = service(
                () -> "Selected account",
                () -> "System user",
                () -> "Unknown",
                new QueuedExecutor());

        ThemePackExportDefaults defaults = onEdt(service::defaults);

        assertEquals("space.minecraftstl.xyml.theme-pack.test", defaults.packId());
        assertEquals("2026-07-31 16:00:00", defaults.name());
        assertEquals(CurrentThemePackExportService.CURRENT_THEME_PACK_VERSION, defaults.version());
        assertEquals("Selected account", defaults.author());
    }

    /// Blank account and system names fall back in order to the localized unknown-author value.
    @Test
    public void defaultsUseOrderedAuthorFallbacks() throws Exception {
        ThemePackExportDefaults systemUser = onEdt(service(
                () -> " ",
                () -> "System user",
                () -> "Unknown",
                new QueuedExecutor())::defaults);
        ThemePackExportDefaults unknown = onEdt(service(
                () -> " ",
                () -> " ",
                () -> "Unknown",
                new QueuedExecutor())::defaults);

        assertEquals("System user", systemUser.author());
        assertEquals("Unknown", unknown.author());
    }

    /// Export work leaves the EDT and publishes a readable `.xyml-theme` archive.
    @Test
    public void exportsCapturedAppearanceOnWorker() throws Exception {
        QueuedExecutor executor = new QueuedExecutor();
        CurrentThemePackExportService service = service(
                () -> "Account",
                () -> "System user",
                () -> "Unknown",
                executor);
        Path output = temporaryDirectory.resolve("current.xyml-theme");
        ThemePackExportRequest request = new ThemePackExportRequest(
                "space.minecraftstl.xyml.theme-pack.test",
                "Current theme",
                "2.0.0",
                "Author",
                output);

        CompletionStage<Path> export = onEdt(() -> service.export(request));
        executor.runAll();

        assertEquals(output.toAbsolutePath().normalize(), export.toCompletableFuture().join());
        try (ZipFile archive = new ZipFile(output.toFile());
             InputStreamReader reader = new InputStreamReader(
                     archive.getInputStream(archive.getEntry("manifest.json")),
                     StandardCharsets.UTF_8)) {
            ThemePackManifest manifest = JsonUtils.GSON.fromJson(reader, ThemePackManifest.class);
            assertEquals("Current theme", manifest.displayName());
            assertEquals("Author", manifest.authors().get(0).displayName());
            assertEquals("2.0.0", manifest.version());
            ThemeBackground source = manifest.themes().get(0).appearance().background().source();
            ThemeBackground.Paint paint = assertInstanceOf(ThemeBackground.Paint.class, source);
            assertEquals("#112233", paint.paint());
        }
    }

    /// Suggested filenames replace unsafe characters and retain a non-empty fallback.
    @Test
    public void sanitizesSuggestedFileNames() {
        assertEquals("theme_pack", SwingThemePackManagementInteractions.sanitizeThemePackFileName("theme:pack."));
        assertEquals("theme-pack", SwingThemePackManagementInteractions.sanitizeThemePackFileName("..."));
    }

    /// Creates a deterministic service with a solid-paint appearance.
    ///
    /// @param accountName selected-account supplier
    /// @param systemUser system-user supplier
    /// @param unknownAuthor unknown-author supplier
    /// @param executor queued background executor
    /// @return deterministic exporter
    private static CurrentThemePackExportService service(
            Supplier<String> accountName,
            Supplier<String> systemUser,
            Supplier<String> unknownAuthor,
            Executor executor) {
        CurrentThemePackAppearance appearance = new CurrentThemePackAppearance(
                ResolvedTheme.DEFAULT,
                new SwingWindowAppearanceRequest(
                        new SwingBackgroundSource.Paint("#112233"),
                        0.75,
                        NetworkBackgroundImageCachePolicy.DISABLED,
                        false));
        return new CurrentThemePackExportService(
                () -> appearance,
                accountName,
                systemUser,
                unknownAuthor,
                executor,
                Clock.fixed(Instant.parse("2026-07-31T16:00:00Z"), ZoneOffset.UTC),
                () -> "space.minecraftstl.xyml.theme-pack.test");
    }

    /// Executes one value supplier synchronously on the EDT.
    ///
    /// @param supplier value supplier
    /// @param <T> result type
    /// @return supplied result
    private static <T> T onEdt(Supplier<T> supplier) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(supplier.get());
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        return result.get();
    }

    /// Deterministic executor that never runs tasks on the EDT.
    @NotNullByDefault
    private static final class QueuedExecutor implements Executor {
        /// Pending tasks in submission order.
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        /// Queues one task.
        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        /// Runs all queued tasks, including tasks submitted by earlier completions.
        private void runAll() {
            while (!tasks.isEmpty()) {
                tasks.remove().run();
            }
        }
    }
}

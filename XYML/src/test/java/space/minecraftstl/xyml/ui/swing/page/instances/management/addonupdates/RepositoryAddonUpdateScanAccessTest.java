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
package space.minecraftstl.xyml.ui.swing.page.instances.management.addonupdates;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.addon.LocalAddonFile;
import space.minecraftstl.xyml.addon.RemoteAddon;
import space.minecraftstl.xyml.download.DownloadProvider;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies winner modeling without requiring a real repository or installed-instance scan.
@NotNullByDefault
final class RepositoryAddonUpdateScanAccessTest {
    /// Converts a malformed selected update into one file-scoped failure and retains source diagnostics.
    @Test
    void malformedWinnerBecomesFileFailureWithoutDiscardingSourceFailures() {
        TestLocalAddonFile localAddon = new TestLocalAddonFile(Path.of("addons", "broken.jar"));
        LocalAddonFile.AddonUpdate winner = new LocalAddonFile.AddonUpdate(
                localAddon,
                version(RemoteAddon.Source.CURSEFORGE, "1.0.0", Instant.parse("2026-01-01T00:00:00Z")),
                version(null, "1.1.0", Instant.parse("2026-02-01T00:00:00Z")),
                true);
        @Unmodifiable List<String> sourceFailures = List.of("CURSEFORGE: timed out");
        List<AddonUpdateItem> updates = new ArrayList<>();
        List<AddonUpdateCheckFailure> failures = new ArrayList<>();

        RepositoryAddonUpdateScanAccess.recordWinner(
                localAddon,
                winner,
                sourceFailures,
                updates,
                failures,
                (update, source) -> {
                    throw new AssertionError("A malformed winner must fail before source-page resolution");
                });

        assertAll(
                () -> assertTrue(updates.isEmpty()),
                () -> assertEquals(1, failures.size()),
                () -> assertEquals("broken.jar", failures.get(0).fileName()),
                () -> assertEquals(localAddon.getFile(), failures.get(0).localFile()),
                () -> assertTrue(failures.get(0).detail().contains("CURSEFORGE: timed out")),
                () -> assertTrue(failures.get(0).detail().contains("Selected update:")));
    }

    /// Keeps a valid update when optional source-page resolution raises a runtime failure.
    @Test
    void sourcePageRuntimeFailureKeepsUpdateWithoutSourcePage() {
        TestLocalAddonFile localAddon = new TestLocalAddonFile(Path.of("addons", "bad-page.jar"));
        LocalAddonFile.AddonUpdate winner = update(localAddon);
        @Unmodifiable List<String> sourceFailures = List.of("CURSEFORGE: service unavailable");
        List<AddonUpdateItem> updates = new ArrayList<>();
        List<AddonUpdateCheckFailure> failures = new ArrayList<>();

        RepositoryAddonUpdateScanAccess.recordWinner(
                localAddon,
                winner,
                sourceFailures,
                updates,
                failures,
                (update, source) -> {
                    throw new IllegalArgumentException("invalid source page");
                });

        AddonUpdateItem item = updates.get(0);
        assertAll(
                () -> assertEquals(1, updates.size()),
                () -> assertTrue(failures.isEmpty()),
                () -> assertSame(winner, item.update()),
                () -> assertNull(item.sourcePage()));
    }

    /// Models a valid winner once and preserves the exact Core update and resolved source page.
    @Test
    void normalWinnerProducesUpdateItem() {
        TestLocalAddonFile localAddon = new TestLocalAddonFile(Path.of("addons", "normal.jar"));
        LocalAddonFile.AddonUpdate winner = update(localAddon);
        URI sourcePage = URI.create("https://example.invalid/mod/normal");
        List<AddonUpdateItem> updates = new ArrayList<>();
        List<AddonUpdateCheckFailure> failures = new ArrayList<>();

        RepositoryAddonUpdateScanAccess.recordWinner(
                localAddon,
                winner,
                List.of(),
                updates,
                failures,
                (update, source) -> {
                    assertSame(winner, update);
                    assertEquals(RemoteAddon.Source.MODRINTH, source);
                    return sourcePage;
                });

        AddonUpdateItem item = updates.get(0);
        assertAll(
                () -> assertEquals(1, updates.size()),
                () -> assertTrue(failures.isEmpty()),
                () -> assertSame(winner, item.update()),
                () -> assertEquals("normal.jar", item.fileName()),
                () -> assertEquals("1.0.0", item.currentVersion()),
                () -> assertEquals("1.1.0", item.targetVersion()),
                () -> assertEquals(RemoteAddon.Source.MODRINTH, item.source()),
                () -> assertEquals(sourcePage, item.sourcePage()));
    }

    /// Creates one normal update whose target comes from Modrinth.
    ///
    /// @param localAddon exact local add-on retained by the update
    /// @return valid update fixture
    private static LocalAddonFile.AddonUpdate update(TestLocalAddonFile localAddon) {
        return new LocalAddonFile.AddonUpdate(
                localAddon,
                version(RemoteAddon.Source.CURSEFORGE, "1.0.0", Instant.parse("2026-01-01T00:00:00Z")),
                version(RemoteAddon.Source.MODRINTH, "1.1.0", Instant.parse("2026-02-01T00:00:00Z")),
                true);
    }

    /// Creates one compact remote version fixture.
    ///
    /// @param source remote source implementation, or `null` for malformed metadata
    /// @param number displayed version number
    /// @param published publication timestamp used by winner selection
    /// @return remote version fixture
    private static RemoteAddon.Version version(
            @Nullable RemoteAddon.Source source,
            String number,
            Instant published) {
        @Nullable RemoteAddon.IVersion self = source == null ? null : () -> source;
        return new RemoteAddon.Version(
                self,
                "normal-project",
                "Normal Project",
                number,
                "",
                published,
                RemoteAddon.VersionType.Release,
                new RemoteAddon.File(Map.of(), "https://example.invalid/normal.jar", "normal.jar"),
                List.of(),
                List.of("1.21.1"),
                List.of());
    }

    /// Minimal local add-on fixture used only by the package-visible winner-modeling seam.
    @NotNullByDefault
    private static final class TestLocalAddonFile extends LocalAddonFile {
        /// Stable normalized fixture path.
        private final Path file;

        /// Creates a local fixture for the supplied path.
        ///
        /// @param file local add-on path
        private TestLocalAddonFile(Path file) {
            this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        }

        /// {@inheritDoc}
        @Override
        public Path getFile() {
            return file;
        }

        /// {@inheritDoc}
        @Override
        public String getFileName() {
            return Objects.requireNonNull(file.getFileName(), "file name").toString();
        }

        /// {@inheritDoc}
        @Override
        public void markDisabled() {
            // No local mutation is required by winner-modeling tests.
        }

        /// {@inheritDoc}
        @Override
        public void setOld(boolean old) {
            // No local mutation is required by winner-modeling tests.
        }

        /// {@inheritDoc}
        @Override
        public boolean keepOldFiles() {
            return false;
        }

        /// {@inheritDoc}
        @Override
        public void delete() {
            // No local mutation is required by winner-modeling tests.
        }

        /// {@inheritDoc}
        @Override
        public @Nullable AddonUpdate checkUpdates(
                DownloadProvider downloadProvider,
                String gameVersion,
                RemoteAddon.Source source) throws IOException {
            return null;
        }
    }
}

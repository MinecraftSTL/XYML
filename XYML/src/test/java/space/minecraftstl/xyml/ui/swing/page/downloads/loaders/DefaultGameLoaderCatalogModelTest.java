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
package space.minecraftstl.xyml.ui.swing.page.downloads.loaders;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.download.RemoteVersion;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies explicit-selection boundaries and stale-result handling for the toolkit-neutral loader model.
@NotNullByDefault
final class DefaultGameLoaderCatalogModelTest {
    /// Confirms construction and selection remain local until the user explicitly requests a refresh.
    @Test
    void defersSourceUseUntilExplicitRefreshAfterBothSelections() {
        RecordingSource source = new RecordingSource();
        DefaultGameLoaderCatalogModel model = new DefaultGameLoaderCatalogModel(source);
        try {
            assertAll(
                    () -> assertEquals(GameLoaderCatalogStatus.AWAITING_GAME_VERSION,
                            model.snapshot().status()),
                    () -> assertEquals(0, source.requestCount()));

            model.selectGameVersion("1.20.1");
            assertAll(
                    () -> assertEquals(GameLoaderCatalogStatus.AWAITING_LOADER,
                            model.snapshot().status()),
                    () -> assertEquals(0, source.requestCount()));

            model.selectLoaderKind(GameLoaderKind.FABRIC);
            assertAll(
                    () -> assertEquals(GameLoaderCatalogStatus.IDLE, model.snapshot().status()),
                    () -> assertEquals(0, source.requestCount()));

            CompletionStage<GameLoaderCatalogSnapshot> result = model.refreshAsync();
            RemoteVersion remoteVersion = remoteVersion("fabric", "1.20.1", "0.16.0");
            source.request(0).complete(List.of(new GameLoaderCatalogItem(
                    GameLoaderKind.FABRIC,
                    remoteVersion)));
            GameLoaderCatalogSnapshot ready = result.toCompletableFuture().join();

            assertAll(
                    () -> assertEquals(1, source.requestCount()),
                    () -> assertEquals(GameLoaderCatalogStatus.READY, ready.status()),
                    () -> assertEquals(GameLoaderKind.FABRIC, ready.loaderKind().orElseThrow()),
                    () -> assertSame(remoteVersion, ready.items().get(0).remoteVersion()));
        } finally {
            model.close();
        }
    }

    /// Rejects refresh requests before both selections without invoking the source.
    @Test
    void rejectsRefreshBeforeBothSelectionsWithoutSourceUse() {
        RecordingSource source = new RecordingSource();
        DefaultGameLoaderCatalogModel model = new DefaultGameLoaderCatalogModel(source);
        try {
            assertThrows(IllegalStateException.class, model::refreshAsync);
            model.selectGameVersion("1.20.1");
            assertThrows(IllegalStateException.class, model::refreshAsync);

            assertEquals(0, source.requestCount());
        } finally {
            model.close();
        }
    }

    /// Drops a completed source result when its game-version selection has already been superseded.
    @Test
    void dropsStaleRefreshResultsAfterGameVersionSelectionChanges() {
        RecordingSource source = new RecordingSource();
        DefaultGameLoaderCatalogModel model = new DefaultGameLoaderCatalogModel(source);
        try {
            model.selectGameVersion("1.20.1");
            model.selectLoaderKind(GameLoaderKind.FABRIC);
            CompletionStage<GameLoaderCatalogSnapshot> firstResult = model.refreshAsync();

            model.selectGameVersion("1.12.2");
            source.request(0).complete(List.of(new GameLoaderCatalogItem(
                    GameLoaderKind.FABRIC,
                    remoteVersion("fabric", "1.20.1", "0.16.0"))));
            GameLoaderCatalogSnapshot resolved = firstResult.toCompletableFuture().join();

            assertAll(
                    () -> assertEquals(GameLoaderCatalogStatus.AWAITING_LOADER, resolved.status()),
                    () -> assertEquals("1.12.2", resolved.gameVersion().orElseThrow()),
                    () -> assertTrue(resolved.items().isEmpty()),
                    () -> assertEquals(resolved, model.snapshot()));
        } finally {
            model.close();
        }
    }

    /// Rejects a source response whose retained Core loader targets another Minecraft version.
    @Test
    void rejectsWrongGameVersionWithoutPublishingItems() {
        RecordingSource source = new RecordingSource();
        DefaultGameLoaderCatalogModel model = new DefaultGameLoaderCatalogModel(source);
        try {
            model.selectGameVersion("1.20.1");
            model.selectLoaderKind(GameLoaderKind.FABRIC);

            CompletionStage<GameLoaderCatalogSnapshot> result = model.refreshAsync();
            source.request(0).complete(List.of(new GameLoaderCatalogItem(
                    GameLoaderKind.FABRIC,
                    remoteVersion("fabric", "1.20.2", "0.16.0"))));
            GameLoaderCatalogSnapshot failed = result.toCompletableFuture().join();

            assertAll(
                    () -> assertEquals(GameLoaderCatalogStatus.FAILED, failed.status()),
                    () -> assertTrue(failed.items().isEmpty()),
                    () -> assertTrue(failed.failure().isPresent()));
        } finally {
            model.close();
        }
    }

    /// Creates one concrete remote-version fixture whose identity must reach the terminal snapshot unchanged.
    ///
    /// @param libraryId Core version-list identifier
    /// @param gameVersion target Minecraft version
    /// @param selfVersion concrete remote loader version
    /// @return stable remote version fixture
    private static RemoteVersion remoteVersion(
            String libraryId,
            String gameVersion,
            String selfVersion) {
        return new RemoteVersion(
                libraryId,
                gameVersion,
                selfVersion,
                Instant.EPOCH,
                List.of("https://example.invalid/" + libraryId + ".jar"));
    }

    /// Captures loader source requests and lets each test complete them explicitly.
    @NotNullByDefault
    private static final class RecordingSource implements GameLoaderCatalogSource {
        /// Requests captured in the order the model explicitly issued them.
        private final List<SourceRequest> requests = new ArrayList<>();

        /// Captures one request without starting network work.
        ///
        /// @param request explicit loader request
        /// @return test-controlled pending result
        @Override
        public CompletionStage<@Unmodifiable List<GameLoaderCatalogItem>> refreshAsync(
                GameLoaderCatalogRequest request) {
            SourceRequest sourceRequest = new SourceRequest(
                    request,
                    new CompletableFuture<>());
            requests.add(sourceRequest);
            return sourceRequest.result().minimalCompletionStage();
        }

        /// Returns a captured source request by zero-based invocation index.
        ///
        /// @param index captured request index
        /// @return pending request
        private SourceRequest request(int index) {
            return requests.get(index);
        }

        /// Returns the number of explicit source requests observed.
        ///
        /// @return explicit source request count
        private int requestCount() {
            return requests.size();
        }
    }

    /// Holds one captured source request and its test-controlled terminal result.
    ///
    /// @param request original explicit request
    /// @param result pending test-controlled response
    @NotNullByDefault
    private record SourceRequest(
            GameLoaderCatalogRequest request,
            CompletableFuture<@Unmodifiable List<GameLoaderCatalogItem>> result) {
        /// Completes this request with immutable concrete loader entries.
        ///
        /// @param items explicit source items
        private void complete(@Unmodifiable List<GameLoaderCatalogItem> items) {
            result.complete(items);
        }
    }
}

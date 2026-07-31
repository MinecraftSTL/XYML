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
package space.minecraftstl.xyml.theme;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/// Offline catalog for theme packs embedded in the launcher distribution.
///
/// Classpath reads and image validation are always scheduled on the caller-owned executor and never performed before
/// that executor starts the task. This catalog is the no-download fallback for first launch and packaged runtimes.
@NotNullByDefault
public final class BuiltinThemePackCatalog {
    /// Stable built-in pack IDs in presentation order.
    public static final @Unmodifiable List<String> PACK_IDS = List.of("xyml.default", "xyml.classic");

    /// Resource ceilings applied to bundled content as defense against broken build artifacts.
    private final ThemePackArchiveLimits limits;

    /// Creates a catalog with launcher-default limits.
    public BuiltinThemePackCatalog() {
        this(ThemePackArchiveLimits.launcherDefaults());
    }

    /// Creates a catalog with explicit validation limits.
    ///
    /// @param limits resource ceilings
    public BuiltinThemePackCatalog(ThemePackArchiveLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /// Loads and validates every packaged theme pack on a caller-owned non-EDT worker executor.
    ///
    /// @param executor caller-owned worker executor
    /// @return completion stage containing immutable built-in packs
    public CompletionStage<@Unmodifiable List<BuiltinThemePack>> loadAll(Executor executor) {
        Executor checkedExecutor = Objects.requireNonNull(executor, "executor");
        CompletableFuture<@Unmodifiable List<BuiltinThemePack>> future = new CompletableFuture<>();
        try {
            checkedExecutor.execute(() -> {
                try {
                    ThemePackIoSupport.requireBackgroundThread();
                    List<BuiltinThemePack> packs = new ArrayList<>(PACK_IDS.size());
                    for (String id : PACK_IDS) {
                        packs.add(loadOne(id));
                    }
                    future.complete(List.copyOf(packs));
                } catch (Throwable failure) {
                    future.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException failure) {
            future.completeExceptionally(failure);
        }
        return future;
    }

    /// Loads one manifest and every referenced bundled image.
    private BuiltinThemePack loadOne(String expectedId) throws IOException {
        String root = "/assets/themes/" + expectedId;
        byte[] manifestBytes;
        try (@Nullable InputStream input = BuiltinThemePackCatalog.class.getResourceAsStream(
                root + "/" + LocalThemePackRepository.MANIFEST_ENTRY)) {
            if (input == null) {
                throw new FileNotFoundException("Missing built-in theme-pack manifest: " + expectedId);
            }
            manifestBytes = ThemePackIoSupport.readBounded(input, limits.maximumManifestBytes());
        }
        ThemePackManifest manifest = ThemePackIoSupport.parseManifest(manifestBytes);
        if (!expectedId.equals(manifest.id())) {
            throw new IOException("Built-in theme-pack resource ID does not match its manifest: " + expectedId);
        }
        BuiltinThemePack pack = new BuiltinThemePack(root, manifest);
        for (String reference : manifest.referencedAssets()) {
            ThemePackIoSupport.validateImage(pack.asset(reference), limits);
        }
        return pack;
    }
}

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
package space.minecraftstl.xyml.game;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.util.gson.JsonUtils;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/// A detached, deeply isolated view of committed repository manifests.
/// Serialized values preserve unknown JSON fields and never expose mutable Gson trees.
@NotNullByDefault
public final class GameRepositorySnapshot {
    /// Captured normalized repository root.
    private final Path baseDirectory;

    /// Repository publication revision.
    private final long revision;

    /// Immutable, deterministically ordered manifest payloads.
    private final @Unmodifiable Map<GameInstanceID, String> manifests;

    /// Captures committed values without retaining caller-owned manifest objects.
    GameRepositorySnapshot(Path baseDirectory, long revision, Map<GameInstanceID, GameInstanceManifest> manifests) {
        this.baseDirectory = baseDirectory.toAbsolutePath().normalize();
        this.revision = revision;
        Map<GameInstanceID, String> payloads = new TreeMap<>();
        manifests.forEach((id, manifest) -> payloads.put(id, JsonUtils.GSON.toJson(manifest)));
        this.manifests = Collections.unmodifiableMap(payloads);
    }

    /// Returns the root represented by this snapshot.
    public Path getBaseDirectory() {
        return baseDirectory;
    }

    /// Returns the committed publication revision.
    public long getRevision() {
        return revision;
    }

    /// Returns whether the captured index contains the instance.
    public boolean hasInstance(GameInstanceID id) {
        return manifests.containsKey(id);
    }

    /// Returns a defensive manifest copy, including unknown serialized fields.
    public GameInstanceManifest getInstanceManifest(GameInstanceID id) {
        @Nullable String json = manifests.get(id);
        if (json == null) {
            throw new NoSuchGameInstanceException(id);
        }
        return JsonUtils.fromNonNullJson(json, GameInstanceManifest.class);
    }

    /// Returns immutable, independently decoded manifests in instance-id order.
    public @Unmodifiable Collection<GameInstanceManifest> getInstanceManifests() {
        return manifests.keySet().stream().map(this::getInstanceManifest).toList();
    }

    /// Resolves a stored instance against this snapshot, not the live repository.
    public GameInstanceManifest.Resolved resolve(GameInstanceID id) {
        return resolve(getInstanceManifest(id));
    }

    /// Resolves an isolated manifest with the repository's established inheritance and patch semantics.
    public GameInstanceManifest.Resolved resolve(GameInstanceManifest manifest) {
        return DefaultGameRepository.resolveSnapshot(this, manifest, new HashSet<>());
    }

    /// Returns the immutable serialized value used for optimistic conflict checks.
    @Nullable String payload(GameInstanceID id) {
        return manifests.get(id);
    }

    /// Returns immutable identifiers without exposing manifest payload objects.
    @Unmodifiable Set<GameInstanceID> ids() {
        return manifests.keySet();
    }
}

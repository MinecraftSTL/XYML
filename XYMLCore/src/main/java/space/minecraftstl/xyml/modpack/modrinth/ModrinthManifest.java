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
package space.minecraftstl.xyml.modpack.modrinth;

import com.google.gson.JsonParseException;
import space.minecraftstl.xyml.addon.RemoteAddon;
import space.minecraftstl.xyml.modpack.ModpackFile;
import space.minecraftstl.xyml.modpack.ModpackManifest;
import space.minecraftstl.xyml.modpack.ModpackProvider;
import space.minecraftstl.xyml.util.DigestUtils;
import space.minecraftstl.xyml.util.StringUtils;
import space.minecraftstl.xyml.util.gson.JsonSerializable;
import space.minecraftstl.xyml.util.gson.TolerableValidationException;
import space.minecraftstl.xyml.util.gson.Validation;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@NotNullByDefault
public class ModrinthManifest implements ModpackManifest, ModpackManifest.SupportOptional, Validation {

    private final String game;
    private final int formatVersion;
    private final String versionId;
    private final String name;
    private final @Nullable String summary;
    private final List<File> files;
    private final Map<String, String> dependencies;

    public ModrinthManifest(String game, int formatVersion, String versionId, String name, @Nullable String summary, List<File> files, Map<String, String> dependencies) {
        this.game = game;
        this.formatVersion = formatVersion;
        this.versionId = versionId;
        this.name = name;
        this.summary = summary;
        this.files = files;
        this.dependencies = dependencies;
    }

    public String getGame() {
        return game;
    }

    public int getFormatVersion() {
        return formatVersion;
    }

    public String getVersionId() {
        return versionId;
    }

    public String getName() {
        return name;
    }

    public String getSummary() {
        return summary == null ? "" : summary;
    }

    @Override
    public @Unmodifiable List<File> getFiles() {
        return files;
    }

    /// Returns a copy with a different file list.
    ///
    /// @param files replacement file list
    /// @return updated manifest
    public ModrinthManifest withFiles(List<File> files) {
        return new ModrinthManifest(game, formatVersion, versionId, name, summary, files, dependencies);
    }

    public Map<String, String> getDependencies() {
        return dependencies;
    }

    public String getGameVersion() {
        return dependencies.get("minecraft");
    }

    @Override
    public ModpackProvider getProvider() {
        return ModrinthModpackProvider.INSTANCE;
    }

    @Override
    public void validate() throws JsonParseException, TolerableValidationException {
        if (dependencies == null || dependencies.get("minecraft") == null) {
            throw new JsonParseException("missing Modrinth.dependencies.minecraft");
        }
    }

    @JsonSerializable
    @NotNullByDefault
    public static class File implements Validation, ModpackFile {
        private final String path;
        private final Map<String, String> hashes;
        @Nullable
        private final Map<String, String> env;
        private final List<String> downloads;
        private final int fileSize;
        @Nullable
        private final RemoteAddon remoteAddon;
        private final boolean addonQueried;

        public File(String path, Map<String, String> hashes, @Nullable Map<String, String> env, List<String> downloads, int fileSize) {
            this(path, hashes, env, downloads, fileSize, null, false);
        }

        /// Creates a Modrinth file entry with optional remote addon metadata.
        public File(
                String path,
                Map<String, String> hashes,
                @Nullable Map<String, String> env,
                List<String> downloads,
                int fileSize,
                @Nullable RemoteAddon remoteAddon,
                boolean addonQueried) {
            this.path = path;
            this.hashes = hashes;
            this.env = env;
            this.downloads = downloads;
            this.fileSize = fileSize;
            this.remoteAddon = remoteAddon;
            this.addonQueried = addonQueried;
        }

        public String getPath() {
            return path;
        }

        public Map<String, String> getHashes() {
            return hashes;
        }

        @Nullable
        public Map<String, String> getEnv() {
            return env;
        }

        public List<String> getDownloads() {
            return downloads;
        }

        public int getFileSize() {
            return fileSize;
        }

        /// Returns the relative file path.
        @Override
        public String path() {
            return path;
        }

        /// Returns the hash map.
        public Map<String, String> hashes() {
            return hashes;
        }

        /// Returns the environment map, or null when absent.
        public @Nullable Map<String, String> env() {
            return env;
        }

        /// Returns the download URLs.
        public List<String> downloads() {
            return downloads;
        }

        /// Returns the stable exclusion key.
        @Override
        public String key() {
            String sha512 = hashes == null ? null : hashes.get("sha512");
            return "modrinth:" + (sha512 == null ? path : sha512) + ":" + path;
        }

        /// Returns the final path component.
        @Override
        public String fileName() {
            return Path.of(path).getFileName().toString();
        }

        /// Returns whether this file is marked optional for the client.
        @Override
        public boolean optional() {
            return env != null && "optional".equals(env.get("client"));
        }

        @Override
        public @Nullable RemoteAddon remoteAddon() {
            return remoteAddon;
        }

        @Override
        public boolean addonQueried() {
            return addonQueried;
        }

        /// Returns a copy marked as queried with the supplied remote addon metadata.
        public File withAddon(@Nullable RemoteAddon remoteAddon) {
            return new File(path, hashes, env, downloads, fileSize, remoteAddon, true);
        }

        @Override
        public void validate() throws JsonParseException {
            if (StringUtils.isBlank(path))
                throw new JsonParseException("Modrinth file path is missing.");
            Path normalizedPath = Path.of(path).normalize();
            if (normalizedPath.isAbsolute() || normalizedPath.startsWith(".."))
                throw new JsonParseException("Modrinth file path escapes the instance directory: " + path);
            if (hashes == null || !DigestUtils.isSha512Digest(hashes.get("sha512")))
                throw new JsonParseException("Modrinth file sha512 is missing or invalid.");
            if (env != null && !env.containsKey("client"))
                throw new JsonParseException("Modrinth file env must contain a client key when present.");
            if (downloads == null || downloads.isEmpty())
                throw new JsonParseException("Modrinth file downloads are missing.");
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            File file = (File) o;
            return fileSize == file.fileSize && path.equals(file.path) && hashes.equals(file.hashes) && Objects.equals(this.env, file.env) && downloads.equals(file.downloads);
        }

        @Override
        public int hashCode() {
            return Objects.hash(path, hashes, env, downloads, fileSize);
        }
    }

}

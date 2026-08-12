/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml.addon;

import space.minecraftstl.xyml.addon.mod.ModLoaderType;
import space.minecraftstl.xyml.addon.repository.CurseForgeRemoteAddonRepository;
import space.minecraftstl.xyml.addon.repository.ModrinthRemoteAddonRepository;
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.task.FileDownloadTask;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/// Immutable remote project metadata shared by provider catalogs and installed-add-on updates.
///
/// @param slug provider project slug
/// @param author project author display name
/// @param title project display title
/// @param description provider description
/// @param categories immutable provider category snapshot
/// @param pageUrl public project page
/// @param iconUrl public icon URL
/// @param data provider-specific operations
/// @param type add-on type, or `null` when a provider result has no mapped type
@NotNullByDefault
public record RemoteAddon(String slug, String author, String title, String description,
                          @Unmodifiable List<String> categories,
                          String pageUrl, String iconUrl, IAddon data, @Nullable Type type) {

    /// Defensively snapshots provider-owned category metadata.
    public RemoteAddon {
        categories = List.copyOf(categories);
    }

    public static final RemoteAddon BROKEN = new RemoteAddon("", "", "RemoteAddon.BROKEN", "", Collections.emptyList(), "", "", new IAddon() {
        @Override
        public List<RemoteAddon> loadDependencies(RemoteAddonRepository repo, DownloadProvider downloadProvider) throws IOException {
            throw new IOException();
        }

        @Override
        public Stream<Version> loadVersions(RemoteAddonRepository repo, DownloadProvider downloadProvider) throws IOException {
            throw new IOException();
        }
    }, Type.MOD);

    public enum VersionType {
        Release,
        Beta,
        Alpha
    }

    public enum DependencyType {
        REQUIRED,
        OPTIONAL,
        TOOL,
        INCLUDE,
        EMBEDDED,
        INCOMPATIBLE,
        BROKEN
    }

    public static final class Dependency {
        private static Dependency BROKEN_DEPENDENCY = null;

        private final DependencyType type;

        private final @Nullable Source source;

        private final @Nullable String id;

        private transient RemoteAddon remoteAddon = null;

        private Dependency(DependencyType type, @Nullable Source source, @Nullable String id) {
            this.type = type;
            this.source = source;
            this.id = id;
        }

        public static Dependency ofGeneral(DependencyType type, Source source, String id) {
            if (type == DependencyType.BROKEN) {
                return ofBroken();
            } else {
                return new Dependency(type, source, id);
            }
        }

        public static Dependency ofBroken() {
            if (BROKEN_DEPENDENCY == null) {
                BROKEN_DEPENDENCY = new Dependency(DependencyType.BROKEN, null, null);
            }
            return BROKEN_DEPENDENCY;
        }

        public DependencyType getType() {
            return this.type;
        }

        @Nullable
        public Source getSource() {
            return this.source;
        }

        @Nullable
        public String getId() {
            return this.id;
        }

        public RemoteAddon load(DownloadProvider downloadProvider) throws IOException {
            if (this.remoteAddon == null) {
                if (this.type == DependencyType.BROKEN) {
                    this.remoteAddon = RemoteAddon.BROKEN;
                } else {
                    this.remoteAddon = this.source.getCommonRepo().resolveDependency(downloadProvider, this.id);
                }
            }
            return this.remoteAddon;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Dependency that = (Dependency) o;

            if (type != that.type) return false;
            if (source != that.source) return false;
            return id.equals(that.id);
        }

        @Override
        public int hashCode() {
            int result = type.hashCode();
            result = 31 * result + source.hashCode();
            result = 31 * result + id.hashCode();
            return result;
        }
    }

    public enum Source {
        CURSEFORGE(
                CurseForgeRemoteAddonRepository.COMMON,
                CurseForgeRemoteAddonRepository.MODS,
                CurseForgeRemoteAddonRepository.RESOURCE_PACKS,
                CurseForgeRemoteAddonRepository.SHADERS,
                CurseForgeRemoteAddonRepository.WORLDS,
                CurseForgeRemoteAddonRepository.MODPACKS,
                CurseForgeRemoteAddonRepository.CUSTOMIZATIONS
        ),
        MODRINTH(
                ModrinthRemoteAddonRepository.COMMON,
                ModrinthRemoteAddonRepository.MODS,
                ModrinthRemoteAddonRepository.RESOURCE_PACKS,
                ModrinthRemoteAddonRepository.SHADER_PACKS,
                null,
                ModrinthRemoteAddonRepository.MODPACKS,
                null
        );

        private final RemoteAddonRepository commonRepo;
        private final RemoteAddonRepository modRepo;
        private final RemoteAddonRepository resourcePackRepo;
        private final RemoteAddonRepository shaderPackRepo;
        private final RemoteAddonRepository worldRepo;
        private final RemoteAddonRepository modpackRepo;
        private final RemoteAddonRepository customizationRepo;

        @Nullable
        public RemoteAddonRepository getRepoForType(Type type) {
            return switch (type) {
                case MOD -> modRepo;
                case RESOURCE_PACK -> resourcePackRepo;
                case SHADER_PACK -> shaderPackRepo;
                case WORLD -> worldRepo;
                case MODPACK -> modpackRepo;
                case CUSTOMIZATION -> customizationRepo;
            };
        }

        public RemoteAddonRepository getCommonRepo() {
            return commonRepo;
        }

        Source(
                RemoteAddonRepository commonRepo,
                RemoteAddonRepository modRepo,
                RemoteAddonRepository resourcePackRepo,
                RemoteAddonRepository shaderPackRepo,
                RemoteAddonRepository worldRepo,
                RemoteAddonRepository modpackRepo,
                RemoteAddonRepository customizationRepo
        ) {
            this.commonRepo = commonRepo;
            this.modRepo = modRepo;
            this.resourcePackRepo = resourcePackRepo;
            this.shaderPackRepo = shaderPackRepo;
            this.worldRepo = worldRepo;
            this.modpackRepo = modpackRepo;
            this.customizationRepo = customizationRepo;
        }
    }

    public enum Type {
        MOD,
        MODPACK,
        RESOURCE_PACK,
        SHADER_PACK,
        WORLD,
        CUSTOMIZATION
    }

    public interface IAddon {
        List<RemoteAddon> loadDependencies(RemoteAddonRepository repo, DownloadProvider downloadProvider) throws IOException;

        Stream<Version> loadVersions(RemoteAddonRepository repo, DownloadProvider downloadProvider) throws IOException;
    }

    public interface IVersion {
        Source getSource();
    }

    /// Immutable provider version metadata. Changelog text is loaded on demand through the repository.
    ///
    /// @param self provider-specific version value
    /// @param versionId provider version identifier used by follow-up APIs
    /// @param projectId provider project identifier
    /// @param name display name
    /// @param version display version
    /// @param datePublished publication timestamp
    /// @param versionType release channel
    /// @param file downloadable artifact
    /// @param dependencies immutable provider dependency snapshot
    /// @param gameVersions immutable compatible game-version snapshot
    /// @param loaders immutable compatible loader snapshot
    @NotNullByDefault
    public record Version(IVersion self, String versionId, String projectId, String name, String version,
                          Instant datePublished, VersionType versionType, File file,
                          @Unmodifiable List<Dependency> dependencies,
                          @Unmodifiable List<String> gameVersions,
                          @Unmodifiable List<ModLoaderType> loaders) {
        /// Defensively snapshots provider-owned collections.
        public Version {
            dependencies = List.copyOf(dependencies);
            gameVersions = List.copyOf(gameVersions);
            loaders = List.copyOf(loaders);
        }
    }

    public record File(Map<String, String> hashes, String url, String filename) {

        public FileDownloadTask.IntegrityCheck getIntegrityCheck() {
            if (hashes.containsKey("md5")) {
                return new FileDownloadTask.IntegrityCheck("MD5", hashes.get("md5"));
            } else if (hashes.containsKey("sha1")) {
                return new FileDownloadTask.IntegrityCheck("SHA-1", hashes.get("sha1"));
            } else if (hashes.containsKey("sha256")) {
                return new FileDownloadTask.IntegrityCheck("SHA-256", hashes.get("sha256"));
            } else if (hashes.containsKey("sha512")) {
                return new FileDownloadTask.IntegrityCheck("SHA-512", hashes.get("sha512"));
            } else {
                return null;
            }
        }
    }
}

// SPDX-FileCopyrightText: Copyright (C) 2021 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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
                    this.remoteAddon = this.source.getRepository().resolveDependency(downloadProvider, this.id);
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
        CURSEFORGE(CurseForgeRemoteAddonRepository.getInstance()),
        MODRINTH(ModrinthRemoteAddonRepository.getInstance());

        private final RemoteAddonRepository repo;

        public RemoteAddonRepository getRepository() {
            return repo;
        }

        Source(RemoteAddonRepository repo) {
            this.repo = repo;
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

    /// Provider-declared runtime environment; unknown metadata never asserts incompatibility.
    @NotNullByDefault
    public enum Environment {
        /// No recognized provider environment was supplied.
        UNKNOWN,
        /// Functionality runs on the logical client.
        CLIENT_ONLY,
        /// Server functionality also works in an integrated singleplayer server.
        SERVER_ONLY,
        /// Functionality requires a dedicated server and does not work in singleplayer.
        DEDICATED_SERVER_ONLY,
        /// Installation is required on both logical sides.
        CLIENT_AND_SERVER,
        /// Required on the server with optional client enhancements.
        SERVER_ONLY_CLIENT_OPTIONAL,
        /// Required on the client with optional server enhancements.
        CLIENT_ONLY_SERVER_OPTIONAL,
        /// Either side works, with enhanced functionality on both.
        CLIENT_OR_SERVER_PREFERS_BOTH,
        /// Either side provides the same functionality.
        CLIENT_OR_SERVER,
        /// Functionality only works in singleplayer.
        SINGLEPLAYER_ONLY;

        /// Maps documented provider values without guessing future or absent metadata.
        ///
        /// @param value raw provider environment, or null when omitted
        /// @return recognized environment, otherwise unknown
        public static Environment fromProvider(@Nullable String value) {
            if (value == null) return UNKNOWN;
            return switch (value) {
                case "client_only" -> CLIENT_ONLY;
                case "server_only" -> SERVER_ONLY;
                case "dedicated_server_only" -> DEDICATED_SERVER_ONLY;
                case "client_and_server" -> CLIENT_AND_SERVER;
                case "server_only_client_optional" -> SERVER_ONLY_CLIENT_OPTIONAL;
                case "client_only_server_optional" -> CLIENT_ONLY_SERVER_OPTIONAL;
                case "client_or_server_prefers_both" -> CLIENT_OR_SERVER_PREFERS_BOTH;
                case "client_or_server" -> CLIENT_OR_SERVER;
                case "singleplayer_only" -> SINGLEPLAYER_ONLY;
                default -> UNKNOWN;
            };
        }
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
    /// @param environment provider runtime environment, unknown when absent
    @NotNullByDefault
    public record Version(IVersion self, String versionId, String projectId, String name, String version,
                          Instant datePublished, VersionType versionType, File file,
                          @Unmodifiable List<Dependency> dependencies,
                          @Unmodifiable List<String> gameVersions,
                          @Unmodifiable List<ModLoaderType> loaders, Environment environment) {
        /// Defensively snapshots provider-owned collections.
        public Version {
            dependencies = List.copyOf(dependencies);
            gameVersions = List.copyOf(gameVersions);
            loaders = List.copyOf(loaders);
            environment = environment == null ? Environment.UNKNOWN : environment;
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

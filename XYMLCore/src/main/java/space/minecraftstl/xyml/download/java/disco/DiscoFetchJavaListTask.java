/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2024 huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml.download.java.disco;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.download.java.JavaPackageType;
import space.minecraftstl.xyml.task.BoundedTextFetchTask;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.util.io.NetworkUtils;
import space.minecraftstl.xyml.util.platform.OperatingSystem;
import space.minecraftstl.xyml.util.platform.Platform;
import space.minecraftstl.xyml.util.versioning.VersionNumber;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/// Fetches the latest directly downloadable Disco Java package for every package type and feature version.
///
/// Directory JSON is capped before parsing so an oversized or compressed response cannot allocate without bound.
@NotNullByDefault
public final class DiscoFetchJavaListTask extends Task<EnumMap<JavaPackageType, TreeMap<Integer, DiscoJavaRemoteVersion>>> {
    /// Maximum decoded bytes accepted for one Disco package directory response.
    private static final long MAXIMUM_DIRECTORY_RESPONSE_BYTES = 16L * 1024L * 1024L;

    /// Configurable Disco API root used by launcher and focused integration tests.
    public static final String API_ROOT = System.getProperty("hmcl.discoapi.override", "https://api.foojay.io/disco/v3.0");

    /// Explicitly selected distribution used to reject inconsistent response entries.
    private final DiscoJavaDistribution distribution;

    /// Platform archive type requested from Disco and rechecked in the response.
    private final String archiveType;

    /// Bounded provider-aware package directory request.
    private final Task<String> fetchPackagesTask;

    /// Creates a stopped package directory request for one distribution and exact platform.
    ///
    /// @param downloadProvider provider supplying ordered API candidates
    /// @param distribution explicitly selected distribution
    /// @param platform exact target platform
    public DiscoFetchJavaListTask(
            DownloadProvider downloadProvider,
            DiscoJavaDistribution distribution,
            Platform platform) {
        this.distribution = Objects.requireNonNull(distribution, "distribution");
        this.archiveType = platform.getOperatingSystem() == OperatingSystem.WINDOWS ? "zip" : "tar.gz";

        HashMap<String, String> params = new HashMap<>();
        params.put("distribution", distribution.getApiParameter());
        params.put("operating_system", platform.getOperatingSystem().getCheckedName());
        params.put("architecture", platform.getArchitecture().getCheckedName());
        params.put("archive_type", archiveType);
        params.put("directly_downloadable", "true");
        if (platform.getOperatingSystem() == OperatingSystem.LINUX)
            params.put("lib_c_type", "glibc");

        this.fetchPackagesTask = new BoundedTextFetchTask(
                downloadProvider.injectURLWithCandidates(
                        NetworkUtils.withQuery(API_ROOT + "/packages", params)),
                MAXIMUM_DIRECTORY_RESPONSE_BYTES);
    }

    /// Returns the sole bounded directory request prerequisite.
    ///
    /// @return immutable singleton prerequisite collection
    @Override
    public @Unmodifiable Collection<Task<?>> getDependents() {
        return Collections.singleton(fetchPackagesTask);
    }

    /// Parses the bounded response and retains the newest distribution build for each Java feature version.
    ///
    /// @throws Exception when the directory response is malformed
    @Override
    public void execute() throws Exception {
        String json = Objects.requireNonNull(fetchPackagesTask.getResult(), "Disco package directory response");
        @Nullable List<DiscoJavaRemoteVersion> list = JsonUtils.fromNonNullJson(
                json,
                DiscoResult.typeOf(DiscoJavaRemoteVersion.class)).getResult();
        if (list == null) {
            throw new IOException("Disco package directory has no result list");
        }
        EnumMap<JavaPackageType, TreeMap<Integer, DiscoJavaRemoteVersion>> result = new EnumMap<>(JavaPackageType.class);

        for (DiscoJavaRemoteVersion version : list) {
            if (!distribution.getApiParameter().equals(version.getDistribution())
                    || !version.isDirectlyDownloadable()
                    || !archiveType.equals(version.getArchiveType()))
                continue;

            if (!distribution.testVersion(version))
                continue;

            JavaPackageType packageType = JavaPackageType.of("jdk".equals(version.getPackageType()), version.isJavaFXBundled());
            TreeMap<Integer, DiscoJavaRemoteVersion> map = result.computeIfAbsent(packageType, ignored -> new TreeMap<>());

            int jdkVersion = version.getJdkVersion();
            DiscoJavaRemoteVersion oldVersion = map.get(jdkVersion);
            if (oldVersion == null || VersionNumber.compare(version.getDistributionVersion(), oldVersion.getDistributionVersion()) > 0)
                map.put(jdkVersion, version);
        }

        setResult(result);
    }

}

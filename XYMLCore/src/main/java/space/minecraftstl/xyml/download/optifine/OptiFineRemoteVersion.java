/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml.download.optifine;

import space.minecraftstl.xyml.download.*;
import space.minecraftstl.xyml.download.game.GameDownloadTask;
import space.minecraftstl.xyml.game.GameComponentType;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.game.GameInstancePatch;
import space.minecraftstl.xyml.task.GetTask;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.StringUtils;
import space.minecraftstl.xyml.util.gson.JsonSerializable;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.*;

import static space.minecraftstl.xyml.util.gson.JsonUtils.listTypeOf;

@NotNullByDefault
public final class OptiFineRemoteVersion extends ComponentRemoteVersion {

    private static String toLookupVersion(String version) {
        return switch (version) {
            case "1.8" -> "1.8.0";
            case "1.9" -> "1.9.0";
            default -> version;
        };
    }

    private static String fromLookupVersion(String version) {
        return switch (version) {
            case "1.8.0" -> "1.8";
            case "1.9.0" -> "1.9";
            default -> version;
        };
    }

    public static Task<ComponentRemoteVersionList<OptiFineRemoteVersion>> fetchBMCLAsync(DownloadProvider downloadProvider, String bmclRoot, GameVersionNumber gameVersion) {
        @JsonSerializable
        record OptiFineVersion(String dl, String ver,
                               String date, String type,
                               @Nullable String patch, String mirror,
                               String mcversion) {
        }

        return new GetTask(DownloadCandidates.of(bmclRoot + "/optifine/" + toLookupVersion(gameVersion.toNormalizedString()))).thenApplyAsync(result -> {
            var root = JsonUtils.fromNonNullJson(result, listTypeOf(OptiFineVersion.class));

            var versions = new TreeSet<OptiFineRemoteVersion>();
            Set<String> duplicates = new HashSet<>();
            for (OptiFineVersion element : root) {
                String version = element.type() + "_" + element.patch();
                String mirror = bmclRoot + "/optifine/" + toLookupVersion(element.mcversion()) + "/" + element.type() + "/" + element.patch();
                if (!duplicates.add(mirror))
                    continue;

                boolean isPre = element.patch() != null && (element.patch().startsWith("pre") || element.patch().startsWith("alpha"));

                if (StringUtils.isBlank(element.mcversion()))
                    continue;

                versions.add(new OptiFineRemoteVersion(gameVersion, version, List.of(mirror), isPre));
            }

            return ComponentRemoteVersionList.of(GameComponentType.OPTIFINE, versions);
        });

    }

    private final String fullVersion;

    public OptiFineRemoteVersion(GameVersionNumber gameVersion, String selfVersion, List<String> urls,
                                 boolean snapshot) {
        super(GameComponentType.OPTIFINE, gameVersion, selfVersion, null, snapshot ? Type.SNAPSHOT : Type.RELEASE, urls);
        this.fullVersion = getGameVersion() + "_" + getSelfVersion();
    }

    @Override
    public String getFullVersion() {
        return fullVersion;
    }

    @Override
    public Task<GameInstancePatch> getInstallTask(DefaultDependencyManager dependencyManager, GameInstanceManifest
            baseManifest, Path modsDirectory) {
        return new GameDownloadTask(dependencyManager, getGameVersion().toString(), baseManifest)
                .thenComposeAsync(minecraftJar -> new OptiFineInstallTask(
                        dependencyManager,
                        baseManifest,
                        this,
                        minecraftJar));
    }
}

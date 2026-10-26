// SPDX-FileCopyrightText: Copyright (C) 2022 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.download.legacyfabric;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.download.DownloadProvider;
import space.minecraftstl.xyml.download.fabric.FabricInstallTask;
import space.minecraftstl.xyml.game.*;
import space.minecraftstl.xyml.task.GetTask;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.util.versioning.GameVersionNumber;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public final class LegacyFabricInstallTask extends Task<GameInstancePatch> {

    private final DefaultDependencyManager dependencyManager;
    private final GameInstanceManifest manifest;
    private final LegacyFabricRemoteVersion remote;
    private final GetTask launchMetaTask;
    private final List<Task<?>> dependencies = new ArrayList<>(1);

    public LegacyFabricInstallTask(DefaultDependencyManager dependencyManager, GameInstanceManifest manifest, LegacyFabricRemoteVersion remoteVersion) {
        this.dependencyManager = dependencyManager;
        this.manifest = manifest;
        this.remote = remoteVersion;

        DownloadProvider downloadProvider = dependencyManager.getDownloadProvider();
        launchMetaTask = new GetTask(downloadProvider.getDownloadCandidates(remoteVersion));
        launchMetaTask.setCacheRepository(dependencyManager.getCacheRepository());
        setResources(TaskResource.repositoryMetadata(dependencyManager.getGameRepository().getBaseDirectory()));
        releaseResourcesBeforeDependencies();
    }

    @Override
    public boolean doPreExecute() {
        return true;
    }

    @Override
    public Collection<Task<?>> getDependents() {
        return Collections.singleton(launchMetaTask);
    }

    @Override
    public Collection<Task<?>> getDependencies() {
        return dependencies;
    }

    @Override
    public boolean isRelyingOnDependencies() {
        return false;
    }

    @Override
    public void execute() {
        setResult(getPatch(JsonUtils.GSON.fromJson(launchMetaTask.getResult(), FabricInstallTask.FabricInfo.class), remote.getGameVersion(), remote.getSelfVersion()));

        dependencies.add(new space.minecraftstl.xyml.download.game.GameLibrariesTask(dependencyManager, manifest, true, getResult().getLibraries()));
    }

    private GameInstancePatch getPatch(FabricInstallTask.FabricInfo legacyFabricInfo, GameVersionNumber gameVersion, String loaderVersion) {
        JsonObject launcherMeta = legacyFabricInfo.getLauncherMeta();
        Arguments arguments = new Arguments();

        String mainClass;
        if (!launcherMeta.get("mainClass").isJsonObject()) {
            mainClass = launcherMeta.get("mainClass").getAsString();
        } else {
            mainClass = launcherMeta.get("mainClass").getAsJsonObject().get("client").getAsString();
        }

        if (launcherMeta.has("launchwrapper")) {
            String clientTweaker = launcherMeta.get("launchwrapper").getAsJsonObject().get("tweakers").getAsJsonObject().get("client").getAsJsonArray().get(0).getAsString();
            arguments = arguments.addGameArguments("--tweakClass", clientTweaker);
        }

        JsonObject librariesObject = launcherMeta.getAsJsonObject("libraries");
        List<Library> libraries = new ArrayList<>();

        // "common, server" is hard coded in fabric installer.
        // Don't know the purpose of ignoring client libraries.
        for (String side : new String[]{"common", "server"}) {
            for (JsonElement element : librariesObject.getAsJsonArray(side)) {
                libraries.add(JsonUtils.GSON.fromJson(element, Library.class));
            }
        }

        // libraries.add(new Library(Artifact.fromDescriptor(legacyFabricInfo.hashed.maven), getMavenRepositoryByGroup(legacyFabricInfo.hashed.maven), null));
        libraries.add(new Library(Artifact.fromDescriptor(legacyFabricInfo.getIntermediary().getMaven()), getMavenRepositoryByGroup(legacyFabricInfo.getIntermediary().getMaven()), null));
        libraries.add(new Library(Artifact.fromDescriptor(legacyFabricInfo.getLoader().getMaven()), getMavenRepositoryByGroup(legacyFabricInfo.getLoader().getMaven()), null));

        return new GameInstancePatch(GameComponentType.LEGACY_FABRIC.getPatchId(), loaderVersion, GameInstancePatch.PRIORITY_LOADER, arguments, mainClass, libraries);
    }

    private static String getMavenRepositoryByGroup(String maven) {
        Artifact artifact = Artifact.fromDescriptor(maven);
        return switch (artifact.getGroup()) {
            case "net.fabricmc" -> "https://maven.fabricmc.net/";
            case "net.legacyfabric" -> "https://maven.legacyfabric.net/";
            default -> "https://maven.fabricmc.net/";
        };
    }
}

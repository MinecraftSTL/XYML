/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2022  huangyuhui <huanghongxun2008@126.com> and contributors
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

import com.google.gson.JsonParseException;
import kala.compress.archivers.zip.ZipArchiveReader;
import space.minecraftstl.xyml.download.DefaultDependencyManager;
import space.minecraftstl.xyml.modpack.MismatchedModpackTypeException;
import space.minecraftstl.xyml.modpack.Modpack;
import space.minecraftstl.xyml.modpack.ModpackProvider;
import space.minecraftstl.xyml.modpack.ModpackUpdateTask;
import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.util.StringUtils;
import space.minecraftstl.xyml.util.gson.JsonUtils;
import space.minecraftstl.xyml.util.io.CompressingUtils;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;

public final class XYMLModpackProvider implements ModpackProvider {
    public static final XYMLModpackProvider INSTANCE = new XYMLModpackProvider();

    @Override
    public String getName() {
        return "HMCL";
    }

    @Override
    public Task<?> createCompletionTask(DefaultDependencyManager dependencyManager, String version) {
        return null;
    }

    @Override
    public Task<?> createUpdateTask(DefaultDependencyManager dependencyManager, String name, Path zipFile, Modpack modpack) throws MismatchedModpackTypeException {
        if (!(modpack.getManifest() instanceof XYMLModpackManifest))
            throw new MismatchedModpackTypeException(getName(), modpack.getManifest().getProvider().getName());

        if (!(dependencyManager.getGameRepository() instanceof XYMLGameRepository repository)) {
            throw new IllegalArgumentException("XYMLModpackProvider requires XYMLGameRepository");
        }

        return new ModpackUpdateTask(dependencyManager.getGameRepository(), name, new XYMLModpackInstallTask(repository, zipFile, modpack, name));
    }

    @Override
    public Modpack readManifest(ZipArchiveReader file, Path path, Charset encoding) throws IOException, JsonParseException {
        String manifestJson = CompressingUtils.readTextZipEntry(file, "modpack.json");
        Modpack manifest = JsonUtils.fromNonNullJson(manifestJson, XYMLModpack.class).setEncoding(encoding);
        String gameJson = CompressingUtils.readTextZipEntry(file, "minecraft/pack.json");
        Version game = JsonUtils.fromNonNullJson(gameJson, Version.class);
        if (game.getJar() == null)
            if (StringUtils.isBlank(manifest.getVersion()))
                throw new JsonParseException("Cannot recognize the game version of modpack " + file + ".");
            else
                manifest.setManifest(XYMLModpackManifest.INSTANCE);
        else
            manifest.setManifest(XYMLModpackManifest.INSTANCE).setGameVersion(game.getJar());
        return manifest;
    }

    private final static class XYMLModpack extends Modpack {
        @Override
        public Task<?> getInstallTask(DefaultDependencyManager dependencyManager, Path zipFile, String name, String iconUrl) {
            return new XYMLModpackInstallTask((XYMLGameRepository) dependencyManager.getGameRepository(), zipFile, this, name);
        }
    }

}

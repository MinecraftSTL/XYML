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
package space.minecraftstl.xyml.game;

import space.minecraftstl.xyml.task.Task;
import space.minecraftstl.xyml.task.TaskResource;
import space.minecraftstl.xyml.util.io.Unzipper;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Extracts the detected game directory from a manually assembled modpack archive.
@NotNullByDefault
public class ManuallyCreatedModpackInstallTask extends Task<Path> {

    /// Input manually assembled archive.
    private final Path zipFile;

    /// Archive entry-name charset.
    private final Charset charset;

    /// User-selected destination name.
    private final String name;

    /// Destination directory snapshot used by both arbitration and extraction.
    private final Path destination;

    /// Creates a manual archive installation with exact archive and destination resources.
    ///
    /// @param zipFile input modpack archive
    /// @param charset archive entry-name charset
    /// @param name destination directory name
    public ManuallyCreatedModpackInstallTask(Path zipFile, Charset charset, String name) {
        this.zipFile = zipFile;
        this.charset = charset;
        this.name = name;
        this.destination = Paths.get("externalgames").resolve(name);

        setName(i18n("modpack.installing"));
        setResources(TaskResource.gameDirectory(destination), TaskResource.archive(zipFile));
    }

    /// {@inheritDoc}
    @Override
    public void execute() throws Exception {
        String subdirectory = ModpackHelper.findMinecraftDirectoryInManuallyCreatedModpack(
                zipFile.toString(),
                zipFile);

        setResult(destination);

        new Unzipper(zipFile, destination)
                .setSubDirectory(subdirectory)
                .setTerminateIfSubDirectoryNotExists()
                .setEncoding(charset)
                .unzip();
    }
}

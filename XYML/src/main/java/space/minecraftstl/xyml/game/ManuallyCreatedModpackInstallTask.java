// SPDX-FileCopyrightText: Copyright (C) 2021 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

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

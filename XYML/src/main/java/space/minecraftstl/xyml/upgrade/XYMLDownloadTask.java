// SPDX-FileCopyrightText: Copyright (C) 2020 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.upgrade;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.task.FileDownloadTask;
import space.minecraftstl.xyml.task.TaskResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/// Downloads one launcher upgrade artifact and removes a failed partial destination.
@NotNullByDefault
final class XYMLDownloadTask extends FileDownloadTask {

    /// Expected downloaded archive format.
    private final RemoteVersion.Type archiveFormat;

    /// Creates a stopped launcher-upgrade download task.
    ///
    /// @param version selected remote launcher version
    /// @param target upgrade archive destination
    XYMLDownloadTask(RemoteVersion version, Path target) {
        super(version.url(), target, version.integrityCheck());
        archiveFormat = version.type();
        Path upgradeDirectory = Objects.requireNonNull(getPath().getParent(), "upgrade directory");
        setResources(
                TaskResource.downloadTarget(getPath()),
                TaskResource.launcherUpgrade(upgradeDirectory));
    }

    /// Verifies the supported archive format and removes the destination after any failure.
    @Override
    public void execute() throws Exception {
        super.execute();

        try {
            Path target = getPath();
            switch (archiveFormat) {
                case JAR:
                    break;
                default:
                    throw new IllegalArgumentException("Unknown format: " + archiveFormat);
            }
        } catch (Throwable e) {
            try {
                Files.deleteIfExists(getPath());
            } catch (Throwable e2) {
                e.addSuppressed(e2);
            }
            throw e;
        }
    }
}

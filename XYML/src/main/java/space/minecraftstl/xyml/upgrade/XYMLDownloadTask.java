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

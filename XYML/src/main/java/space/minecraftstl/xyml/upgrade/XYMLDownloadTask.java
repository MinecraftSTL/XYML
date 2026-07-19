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

import space.minecraftstl.xyml.task.FileDownloadTask;

import java.nio.file.Files;
import java.nio.file.Path;

final class XYMLDownloadTask extends FileDownloadTask {

    private final RemoteVersion.Type archiveFormat;

    public XYMLDownloadTask(RemoteVersion version, Path target) {
        super(version.url(), target, version.integrityCheck());
        archiveFormat = version.type();
    }

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

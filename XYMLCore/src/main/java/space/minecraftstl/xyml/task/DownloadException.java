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
package space.minecraftstl.xyml.task;

import org.jetbrains.annotations.NotNullByDefault;
import org.glavo.url.WebURL;

import java.io.IOException;

import static java.util.Objects.requireNonNull;

/// Reports that a resource could not be downloaded from a specific URL.
@NotNullByDefault
public class DownloadException extends IOException {

    /// URL whose download failed.
    private final WebURL url;

    /// Creates a download failure with its source URL and underlying cause.
    ///
    /// @param url source URL
    /// @param cause underlying failure
    public DownloadException(WebURL url, Throwable cause) {
        super("Unable to download " + url + ", " + cause.getMessage(), requireNonNull(cause));
        this.url = url;
    }

    /// Returns the URL whose download failed.
    public WebURL getUrl() {
        return url;
    }
}

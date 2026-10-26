// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.modpack.curse;

import com.google.gson.annotations.SerializedName;
import space.minecraftstl.xyml.util.Immutable;

/**
 * CurseMetaMod is JSON structure for
 * https://cursemeta.dries007.net/&lt;projectID&gt;/&lt;fileID&gt;.json
 * https://addons-ecs.forgesvc.net/api/v2/addon/&lt;projectID&gt;/file/&lt;fileID&gt;
 */
@Immutable
public final class CurseMetaMod {
    @SerializedName(value = "Id", alternate = "id")
    private final int id;

    @SerializedName(value = "FileName", alternate = "fileName")
    private final String fileName;

    @SerializedName(value = "FileNameOnDisk")
    private final String fileNameOnDisk;

    @SerializedName(value = "DownloadURL", alternate = "downloadUrl")
    private final String downloadURL;

    public CurseMetaMod() {
        this(0, "", "", "");
    }

    public CurseMetaMod(int id, String fileName, String fileNameOnDisk, String downloadURL) {
        this.id = id;
        this.fileName = fileName;
        this.fileNameOnDisk = fileNameOnDisk;
        this.downloadURL = downloadURL;
    }

    public int getId() {
        return id;
    }

    public String getFileName() {
        return fileName;
    }

    public String getFileNameOnDisk() {
        return fileNameOnDisk;
    }

    public String getDownloadURL() {
        return downloadURL;
    }
}

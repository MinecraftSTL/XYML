// SPDX-FileCopyrightText: Copyright (C) 2020 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.download.forge;

import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.util.Immutable;
import space.minecraftstl.xyml.util.gson.Validation;

/**
 *
 * @author huangyuhui
 */
@Immutable
public final class ForgeInstallProfile implements Validation {

    @SerializedName("install")
    private final ForgeInstall install;

    @SerializedName("versionInfo")
    private final GameInstanceManifest versionInfo;

    public ForgeInstallProfile(ForgeInstall install, GameInstanceManifest versionInfo) {
        this.install = install;
        this.versionInfo = versionInfo;
    }

    public ForgeInstall getInstall() {
        return install;
    }

    public GameInstanceManifest getVersionInfo() {
        return versionInfo;
    }

    @Override
    public void validate() throws JsonParseException {
        if (install == null)
            throw new JsonParseException("InstallProfile install cannot be null");

        if (versionInfo == null)
            throw new JsonParseException("InstallProfile versionInfo cannot be null");
    }
}

// SPDX-FileCopyrightText: Copyright (C) 2020 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.game;

import space.minecraftstl.xyml.modpack.ModpackManifest;
import space.minecraftstl.xyml.modpack.ModpackProvider;

public final class XYMLModpackManifest implements ModpackManifest {
    public static final XYMLModpackManifest INSTANCE = new XYMLModpackManifest();

    private XYMLModpackManifest() {}

    @Override
    public ModpackProvider getProvider() {
        return XYMLModpackProvider.INSTANCE;
    }
}

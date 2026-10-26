// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

// Modified by MinecraftSTL in 2026 for the XYML namespace and monorepo build.
package space.minecraftstl.xyml.library.nbt;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;

public final class TestResources {
    /// Prevents construction of this resource lookup utility.
    private TestResources() {
    }

    public static Path getResource(String name) {
        URL resource = TestResources.class.getResource(name);
        if (resource == null) {
            throw new IllegalArgumentException("Resource not found: " + name);
        }

        try {
            return Path.of(resource.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }
}

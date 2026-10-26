// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.util.io;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;
import java.util.Objects;

/// Recycle-bin operations backed by the current operating system and desktop integration.
@NotNullByDefault
public final class SystemTrashOperations implements TrashOperations {
    /// Shared stateless system implementation.
    public static final SystemTrashOperations INSTANCE = new SystemTrashOperations();

    /// Prevents additional construction of the stateless singleton.
    private SystemTrashOperations() {
    }

    /// Returns whether the current platform exposes a usable recycle bin.
    @Override
    public boolean isSupported() {
        return FileUtils.isMoveToTrashSupported();
    }

    /// Moves one path through the current platform recycle-bin implementation.
    @Override
    public boolean moveToTrash(Path path) {
        return FileUtils.moveToTrash(Objects.requireNonNull(path, "path"));
    }
}

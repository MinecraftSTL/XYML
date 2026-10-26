// SPDX-FileCopyrightText: Copyright (C) 2020 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.download.game;

import space.minecraftstl.xyml.game.Library;
import org.jetbrains.annotations.NotNull;

public class LibraryDownloadException extends Exception {
    private final Library library;

    public LibraryDownloadException(Library library, @NotNull Throwable cause) {
        super("Unable to download library " + library, cause);

        this.library = library;
    }

    public Library getLibrary() {
        return library;
    }
}

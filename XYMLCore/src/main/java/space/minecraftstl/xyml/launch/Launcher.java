// SPDX-FileCopyrightText: Copyright (C) 2020 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.launch;

import space.minecraftstl.xyml.auth.AuthInfo;
import space.minecraftstl.xyml.game.GameInstanceManifest;
import space.minecraftstl.xyml.game.GameRepository;
import space.minecraftstl.xyml.game.LaunchOptions;
import space.minecraftstl.xyml.util.platform.ManagedProcess;

import java.io.IOException;
import java.nio.file.Path;

/**
 *
 * @author huangyuhui
 */
public abstract class Launcher {

    protected final GameRepository repository;
    protected final GameInstanceManifest manifest;
    protected final AuthInfo authInfo;
    protected final LaunchOptions options;
    protected final ProcessListener listener;
    protected final boolean daemon;

    public Launcher(GameRepository repository, GameInstanceManifest manifest, AuthInfo authInfo, LaunchOptions options) {
        this(repository, manifest, authInfo, options, null);
    }

    public Launcher(GameRepository repository, GameInstanceManifest manifest, AuthInfo authInfo, LaunchOptions options, ProcessListener listener) {
        this(repository, manifest, authInfo, options, listener, true);
    }

    public Launcher(GameRepository repository, GameInstanceManifest manifest, AuthInfo authInfo, LaunchOptions options, ProcessListener listener, boolean daemon) {
        this.repository = repository;
        this.manifest = manifest;
        this.authInfo = authInfo;
        this.options = options;
        this.listener = listener;
        this.daemon = daemon;
    }

    /**
     * @param file the file path.
     */
    public abstract void makeLaunchScript(Path file) throws IOException;

    public abstract ManagedProcess launch() throws IOException, InterruptedException;

}

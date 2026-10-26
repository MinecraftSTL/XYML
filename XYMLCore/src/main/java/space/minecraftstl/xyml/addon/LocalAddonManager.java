// SPDX-FileCopyrightText: Copyright (C) 2025 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.addon;

import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameRepository;
import space.minecraftstl.xyml.util.StringUtils;
import space.minecraftstl.xyml.util.io.FileUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/// Manages one kind of local add-on for a specific installed game instance.
@NotNullByDefault
public abstract class LocalAddonManager<T extends LocalAddonFile> {

    public static final String DISABLED_EXTENSION = ".disabled";
    public static final String OLD_EXTENSION = ".old";

    public static String getLocalAddonName(Path file) {
        return StringUtils.removeSuffix(FileUtils.getName(file), DISABLED_EXTENSION, OLD_EXTENSION);
    }

    protected final ReentrantLock lock = new ReentrantLock();

    protected final Set<@NotNull T> localFiles = new LinkedHashSet<>();

    protected final GameRepository repository;
    protected final GameInstanceID instanceId;

    /// Creates an add-on manager scoped to one installed game instance.
    ///
    /// @param gameRepository repository containing the installed instance
    /// @param instanceId target installed instance identifier
    public LocalAddonManager(GameRepository gameRepository, GameInstanceID instanceId) {
        this.repository = gameRepository;
        this.instanceId = instanceId;
    }

    /// Returns the repository containing the managed instance.
    public GameRepository getRepository() {
        return repository;
    }

    /// Returns the managed instance identifier.
    public GameInstanceID getInstanceId() {
        return instanceId;
    }

    public abstract Path getDirectory();

    public abstract void refresh() throws IOException;

    public abstract Comparator<T> getComparator();

    public @Unmodifiable List<T> getLocalFiles() throws IOException {
        lock.lock();
        try {
            return localFiles.stream().sorted(getComparator()).toList();
        } finally {
            lock.unlock();
        }
    }

    public Path setOld(T modFile, boolean old) throws IOException {
        lock.lock();
        try {
            Path newPath;
            if (old) {
                newPath = backupFile(modFile.getFile());
                localFiles.remove(modFile);
            } else {
                newPath = restoreFile(modFile.getFile());
                localFiles.add(modFile);
            }
            return newPath;
        } finally {
            lock.unlock();
        }
    }

    private Path backupFile(Path file) throws IOException {
        Path newPath = file.resolveSibling(
                StringUtils.addSuffix(
                        StringUtils.removeSuffix(FileUtils.getName(file), DISABLED_EXTENSION),
                        OLD_EXTENSION
                )
        );
        if (Files.exists(file)) {
            Files.move(file, newPath, StandardCopyOption.REPLACE_EXISTING);
        }
        return newPath;
    }

    private Path restoreFile(Path file) throws IOException {
        Path newPath = file.resolveSibling(
                StringUtils.removeSuffix(FileUtils.getName(file), OLD_EXTENSION)
        );
        if (Files.exists(file)) {
            Files.move(file, newPath, StandardCopyOption.REPLACE_EXISTING);
        }
        return newPath;
    }
}

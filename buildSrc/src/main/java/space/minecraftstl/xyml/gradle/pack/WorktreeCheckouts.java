/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml.gradle.pack;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// Parses `git worktree list --porcelain` output into per-worktree branch checkouts.
@NotNullByDefault
final class WorktreeCheckouts {
    /// Prefix of the worktree path line.
    private static final String PATH_PREFIX = "worktree ";

    /// Prefix of the checked-out branch line.
    private static final String BRANCH_PREFIX = "branch refs/heads/";

    /// One parsed worktree entry.
    ///
    /// @param path absolute worktree path reported by Git
    /// @param branch short checked-out branch name, or `null` for detached worktrees
    record Entry(Path path, @Nullable String branch) {
    }

    /// Prevents instantiation of this stateless helper.
    private WorktreeCheckouts() {
    }

    /// Parses porcelain worktree output.
    ///
    /// @param porcelainOutput complete `git worktree list --porcelain` output
    /// @return immutable entries in Git order
    static @Unmodifiable List<Entry> parse(String porcelainOutput) {
        List<Entry> entries = new ArrayList<>();
        @Nullable String path = null;
        @Nullable String branch = null;
        for (String line : porcelainOutput.split("\n", -1)) {
            if (line.isBlank()) {
                addEntry(entries, path, branch);
                path = null;
                branch = null;
                continue;
            }
            if (line.startsWith(PATH_PREFIX)) {
                addEntry(entries, path, branch);
                path = line.substring(PATH_PREFIX.length());
                branch = null;
                continue;
            }
            if (line.startsWith(BRANCH_PREFIX)) {
                branch = line.substring(BRANCH_PREFIX.length());
            }
        }
        addEntry(entries, path, branch);
        return List.copyOf(entries);
    }

    /// Finds every worktree that checks out one branch.
    ///
    /// @param entries parsed worktree entries
    /// @param branchName short branch name
    /// @return immutable worktree paths that currently check out the branch
    static @Unmodifiable List<Path> pathsCheckingOut(@Unmodifiable List<Entry> entries, String branchName) {
        List<Path> paths = new ArrayList<>();
        for (Entry entry : entries) {
            if (branchName.equals(entry.branch())) {
                paths.add(entry.path());
            }
        }
        return List.copyOf(paths);
    }

    /// Adds one completed entry when Git reported a path.
    ///
    /// @param entries mutable result list
    /// @param path reported path, or `null` when no path was read yet
    /// @param branch reported branch, or `null` for detached worktrees
    private static void addEntry(List<Entry> entries, @Nullable String path, @Nullable String branch) {
        if (path == null) {
            return;
        }
        entries.add(new Entry(Path.of(path), branch));
    }
}

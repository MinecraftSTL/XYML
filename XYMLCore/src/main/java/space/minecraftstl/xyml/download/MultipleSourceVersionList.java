/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
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
package space.minecraftstl.xyml.download;

import space.minecraftstl.xyml.task.Task;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static space.minecraftstl.xyml.util.logging.Logger.LOG;

public class MultipleSourceVersionList extends VersionList<RemoteVersion> {

    private final VersionList<?>[] backends;

    MultipleSourceVersionList(VersionList<?>[] backends) {
        this.backends = backends;

        assert (backends.length >= 1);
    }

    @Override
    public boolean hasType() {
        boolean hasType = backends[0].hasType();
        assert (Arrays.stream(backends).allMatch(versionList -> versionList.hasType() == hasType));
        return hasType;
    }

    @Override
    public Task<?> refreshAsync() {
        throw new UnsupportedOperationException("MultipleSourceVersionList does not support loading the entire remote version list.");
    }

    private Task<?> refreshAsync(String gameVersion, int sourceIndex) {
        VersionList<?> versionList = backends[sourceIndex];
        Task<?> refreshTask = versionList.refreshAsync(gameVersion);

        return new Task<>() {
            private Task<?> nextTask = null;

            {
                setSignificance(TaskSignificance.MODERATE);
                setName("MultipleSourceVersionList.refreshAsync(task=%s, index=%d, all=%d)".formatted(
                        refreshTask.getName(), sourceIndex, backends.length)
                );
                asOrchestration();
            }

            @Override
            public Collection<Task<?>> getDependents() {
                return List.of(refreshTask);
            }

            @Override
            public Collection<? extends Task<?>> getDependencies() {
                return nextTask != null ? List.of(nextTask) : List.of();
            }

            @Override
            public boolean isRelyingOnDependents() {
                return false;
            }

            @Override
            public void execute() throws Exception {
                if (isDependentsSucceeded()) {
                    List<RemoteVersion> sourceVersions = copyVersions(versionList, gameVersion);
                    if (!sourceVersions.isEmpty()) {
                        lock.writeLock().lock();
                        try {
                            versions.clear(gameVersion);
                            versions.putAll(gameVersion, sourceVersions);
                        } finally {
                            lock.writeLock().unlock();
                        }

                        setResult(refreshTask.getResult());
                        return;
                    }

                    if (sourceIndex < backends.length - 1) {
                        LOG.warning("Version list source returned no versions; trying another source");
                        nextTask = refreshAsync(gameVersion, sourceIndex + 1);
                        nextTask.storeTo(this::setResult);
                        return;
                    }

                    // The previous bucket was cleared before the first source attempt,
                    // so an empty success remains unloaded.
                    setResult(refreshTask.getResult());
                } else {
                    Exception exception = refreshTask.getException();
                    assert exception != null;

                    if (sourceIndex == backends.length - 1) {
                        LOG.warning("Failed to fetch versions list from all sources", exception);
                        setSignificance(TaskSignificance.MINOR);
                        throw exception;
                    } else {
                        LOG.warning("Failed to fetch versions list and try to fetch from other source", exception);
                        nextTask = refreshAsync(gameVersion, sourceIndex + 1);
                        nextTask.storeTo(this::setResult);
                    }
                }
            }
        };
    }

    /// Clears the cached game-version bucket before refreshing it from the first backend.
    ///
    /// @param gameVersion game version whose version list must be reloaded
    /// @return orchestration task that clears the old bucket before running the ordered fallback chain
    @Override
    public Task<?> refreshAsync(String gameVersion) {
        return Task.runAsync(() -> {
            lock.writeLock().lock();
            try {
                versions.clear(gameVersion);
            } finally {
                lock.writeLock().unlock();
            }
        }).asOrchestration().thenComposeAsync(() -> refreshAsync(gameVersion, 0)).asOrchestration();
    }

    /// Copies one backend's concrete versions without publishing its mutable collection.
    ///
    /// @param versionList backend list refreshed for the requested game version
    /// @param gameVersion requested game version
    /// @return immutable versions retaining their concrete runtime type
    private static List<RemoteVersion> copyVersions(VersionList<?> versionList, String gameVersion) {
        List<RemoteVersion> copied = new ArrayList<>();
        for (Object value : versionList.getVersions(gameVersion)) {
            if (!(value instanceof RemoteVersion remoteVersion)) {
                throw new IllegalStateException("Version list returned a non-version value");
            }
            copied.add(remoteVersion);
        }
        return List.copyOf(copied);
    }
}

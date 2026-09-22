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
package space.minecraftstl.xyml.download;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.task.Task;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies ordered fallback and cache clearing for [MultipleSourceVersionList].
@NotNullByDefault
public final class MultipleSourceVersionListTest {
    /// A successful empty primary response does not prevent a later source from supplying versions.
    @Test
    public void skipsSuccessfulEmptySourceAndUsesLaterSource() {
        MutableVersionList primary = new MutableVersionList();
        RemoteVersion expected = remoteVersion("fabric", "0.16.0");
        MutableVersionList secondary = new MutableVersionList(expected);
        MultipleSourceVersionList list = new MultipleSourceVersionList(
                new VersionList<?>[] {primary, secondary});

        assertTrue(list.refreshAsync("1.20.1").test());

        List<RemoteVersion> versions = List.copyOf(list.getVersions("1.20.1"));
        assertEquals(1, versions.size());
        assertSame(expected, versions.get(0));
    }

    /// A failed primary source falls back to a healthy source without losing its concrete version object.
    @Test
    public void fallsBackAfterPrimaryFailure() {
        MutableVersionList primary = new MutableVersionList();
        primary.failWith(new IOException("primary unavailable"));
        RemoteVersion expected = remoteVersion("forge", "47.2.0");
        MutableVersionList secondary = new MutableVersionList(expected);
        MultipleSourceVersionList list = new MultipleSourceVersionList(
                new VersionList<?>[] {primary, secondary});

        assertTrue(list.refreshAsync("1.20.1").test());

        List<RemoteVersion> versions = List.copyOf(list.getVersions("1.20.1"));
        assertEquals(1, versions.size());
        assertSame(expected, versions.get(0));
    }

    /// An empty refresh clears the last successfully published version bucket.
    @Test
    public void clearsCachedVersionsWhenAllSourcesReturnEmpty() {
        RemoteVersion cached = remoteVersion("quilt", "0.26.3");
        MutableVersionList primary = new MutableVersionList(cached);
        MultipleSourceVersionList list = new MultipleSourceVersionList(
                new VersionList<?>[] {primary});

        assertTrue(list.refreshAsync("1.20.1").test());
        assertTrue(list.isLoaded("1.20.1"));

        primary.setVersion(null);
        assertTrue(list.refreshAsync("1.20.1").test());

        assertFalse(list.isLoaded("1.20.1"));
        assertTrue(list.getVersions("1.20.1").isEmpty());
    }

    /// A failed refresh also clears the last successfully published version bucket.
    @Test
    public void clearsCachedVersionsWhenAllSourcesFail() {
        RemoteVersion cached = remoteVersion("quilt", "0.26.3");
        MutableVersionList primary = new MutableVersionList(cached);
        MultipleSourceVersionList list = new MultipleSourceVersionList(
                new VersionList<?>[] {primary});

        assertTrue(list.refreshAsync("1.20.1").test());
        assertTrue(list.isLoaded("1.20.1"));

        primary.setVersion(null);
        primary.failWith(new IOException("primary unavailable"));
        assertFalse(list.refreshAsync("1.20.1").test());

        assertFalse(list.isLoaded("1.20.1"));
        assertTrue(list.getVersions("1.20.1").isEmpty());
    }

    /// Creates one concrete remote version fixture.
    ///
    /// @param libraryId loader identifier
    /// @param selfVersion loader version
    /// @return remote version fixture
    private static RemoteVersion remoteVersion(String libraryId, String selfVersion) {
        return new RemoteVersion(libraryId, "1.20.1", selfVersion, Instant.EPOCH, List.of());
    }

    /// Provides controlled success, empty, and failure responses for one source.
    @NotNullByDefault
    private static final class MutableVersionList extends VersionList<RemoteVersion> {
        private @Nullable RemoteVersion version;
        private @Nullable IOException failure;

        private MutableVersionList() {
        }

        private MutableVersionList(RemoteVersion version) {
            this.version = version;
        }

        private void setVersion(@Nullable RemoteVersion version) {
            this.version = version;
        }

        private void failWith(IOException failure) {
            this.failure = failure;
        }

        @Override
        public boolean hasType() {
            return false;
        }

        @Override
        public Task<?> refreshAsync() {
            throw new UnsupportedOperationException("Whole-list refresh is not supported by this test list");
        }

        @Override
        public Task<?> refreshAsync(String gameVersion) {
            return Task.runAsync(() -> {
                if (failure != null) {
                    throw failure;
                }
                versions.clear(gameVersion);
                if (version != null) {
                    versions.put(gameVersion, version);
                }
            });
        }
    }
}

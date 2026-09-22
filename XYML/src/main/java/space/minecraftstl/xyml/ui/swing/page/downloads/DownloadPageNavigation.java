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
package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import java.util.Objects;
import java.util.function.Consumer;

/// Delivers one pending download-category request to the lazily created download page.
@NotNullByDefault
public final class DownloadPageNavigation {
    /// Category waiting for the download page to attach.
    private @Nullable DownloadPageTarget pendingTarget;

    /// Current download-page category consumer, or null while the page is not cached.
    private @Nullable Consumer<DownloadPageTarget> consumer;

    /// Queues or immediately delivers one category request on the EDT.
    ///
    /// @param target requested download category
    public void request(DownloadPageTarget target) {
        EdtDispatcher.requireEventDispatchThread();
        DownloadPageTarget requested = Objects.requireNonNull(target, "target");
        @Nullable Consumer<DownloadPageTarget> current = consumer;
        if (current == null) {
            pendingTarget = requested;
        } else {
            current.accept(requested);
        }
    }

    /// Attaches the download page and consumes any queued request.
    ///
    /// @param newConsumer download-page category consumer
    public void attach(Consumer<DownloadPageTarget> newConsumer) {
        EdtDispatcher.requireEventDispatchThread();
        Consumer<DownloadPageTarget> attached = Objects.requireNonNull(newConsumer, "newConsumer");
        if (consumer != null && consumer != attached) {
            throw new IllegalStateException("A download page is already attached");
        }
        consumer = attached;
        @Nullable DownloadPageTarget pending = pendingTarget;
        if (pending != null) {
            pendingTarget = null;
            attached.accept(pending);
        }
    }

    /// Detaches the current download page when it closes.
    ///
    /// @param current current download-page consumer
    public void detach(Consumer<DownloadPageTarget> current) {
        EdtDispatcher.requireEventDispatchThread();
        if (consumer == Objects.requireNonNull(current, "current")) {
            consumer = null;
        }
    }
}

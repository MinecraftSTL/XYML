// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.downloads;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import java.util.Objects;
import java.util.function.Consumer;

/// Delivers one pending download-center request to the lazily created download page.
@NotNullByDefault
public final class DownloadPageNavigation {
    /// Request waiting for the download page to attach, or null when none is queued.
    private @Nullable DownloadPageRequest pendingRequest;

    /// Current download-page request consumer, or null while the page is not cached.
    private @Nullable Consumer<DownloadPageRequest> consumer;

    /// Queues or immediately delivers one category request on the EDT.
    ///
    /// @param target requested download category
    public void request(DownloadPageTarget target) {
        request(DownloadPageRequest.of(target));
    }

    /// Queues or immediately delivers one category request with its optional instance context on the EDT.
    ///
    /// @param request requested download category and optional instance context
    public void request(DownloadPageRequest request) {
        EdtDispatcher.requireEventDispatchThread();
        DownloadPageRequest requested = Objects.requireNonNull(request, "request");
        @Nullable Consumer<DownloadPageRequest> current = consumer;
        if (current == null) {
            pendingRequest = requested;
        } else {
            current.accept(requested);
        }
    }

    /// Attaches the download page and consumes any queued request.
    ///
    /// @param newConsumer download-page request consumer
    public void attach(Consumer<DownloadPageRequest> newConsumer) {
        EdtDispatcher.requireEventDispatchThread();
        Consumer<DownloadPageRequest> attached = Objects.requireNonNull(newConsumer, "newConsumer");
        if (consumer != null && consumer != attached) {
            throw new IllegalStateException("A download page is already attached");
        }
        consumer = attached;
        @Nullable DownloadPageRequest pending = pendingRequest;
        if (pending != null) {
            pendingRequest = null;
            attached.accept(pending);
        }
    }

    /// Detaches the current download page when it closes.
    ///
    /// @param current current download-page request consumer
    public void detach(Consumer<DownloadPageRequest> current) {
        EdtDispatcher.requireEventDispatchThread();
        if (consumer == Objects.requireNonNull(current, "current")) {
            consumer = null;
        }
    }
}

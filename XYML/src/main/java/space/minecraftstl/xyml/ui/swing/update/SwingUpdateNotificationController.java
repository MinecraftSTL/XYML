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
package space.minecraftstl.xyml.ui.swing.update;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChange;
import space.minecraftstl.xyml.upgrade.RemoteVersion;
import space.minecraftstl.xyml.upgrade.UpdateChannel;
import space.minecraftstl.xyml.util.Lang;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/// Connects successful update-check snapshots to one native Swing offer per remote release identity.
///
/// The controller subscribes before reconciling current state, so construction cannot miss a concurrently committed
/// successful check. It owns only its subscription; the service and presenter remain composition-root resources.
@NotNullByDefault
public final class SwingUpdateNotificationController implements AutoCloseable {
    /// Serializes offered-release identity and closure.
    private final Object stateLock = new Object();

    /// Native update offer presenter.
    private final SwingUpdatePromptPresenter presenter;

    /// Owned service subscription.
    private final Subscription subscription;

    /// Most recent release identity already offered, or null before any offer.
    private @Nullable ReleaseIdentity lastOfferedIdentity;

    /// Whether the controller has stopped presenting new successful results.
    private boolean closed;

    /// Subscribes to a service and reconciles an already successful snapshot.
    ///
    /// @param service update-check service
    /// @param presenter native prompt presenter
    public SwingUpdateNotificationController(
            SwingUpdateCheckService service,
            SwingUpdatePromptPresenter presenter) {
        Objects.requireNonNull(service, "service");
        this.presenter = Objects.requireNonNull(presenter, "presenter");
        subscription = service.subscribe(this::snapshotChanged);
        offerIfAvailable(service.snapshot());
    }

    /// Unsubscribes and gates future snapshot offers.
    @Override
    public void close() {
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            closed = true;
        }
        subscription.unsubscribe();
    }

    /// Reconciles one committed service transition.
    ///
    /// @param change immutable snapshot transition
    private void snapshotChanged(ValueChange<UpdateCheckSnapshot> change) {
        @Nullable UpdateCheckSnapshot snapshot = Objects.requireNonNull(change, "change").currentValue();
        if (snapshot != null) {
            offerIfAvailable(snapshot);
        }
    }

    /// Presents a newly successful available release at most once per stable identity.
    ///
    /// @param snapshot service snapshot to reconcile
    private void offerIfAvailable(UpdateCheckSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.status() != UpdateCheckSnapshot.Status.SUCCEEDED) {
            return;
        }
        Optional<UpdateCheckResult> optionalResult = snapshot.lastSuccessfulResult();
        if (optionalResult.isEmpty() || !optionalResult.get().updateAvailable()) {
            return;
        }

        UpdateCheckResult result = optionalResult.get();
        ReleaseIdentity identity = ReleaseIdentity.from(result.remoteVersion());
        synchronized (stateLock) {
            if (closed || identity.equals(lastOfferedIdentity)) {
                return;
            }
            lastOfferedIdentity = identity;
        }

        CompletionStage<SwingUpdatePromptPresenter.Outcome> presentation;
        try {
            presentation = Objects.requireNonNull(
                    presenter.present(result),
                    "update presenter returned null");
        } catch (RuntimeException presentationFailure) {
            presentationFailed(identity, presentationFailure);
            return;
        }
        presentation.whenComplete((
                @Nullable SwingUpdatePromptPresenter.Outcome ignored,
                @Nullable Throwable presentationFailure) -> {
            if (presentationFailure != null) {
                presentationFailed(identity, presentationFailure);
            }
        });
    }

    /// Allows a later successful check to retry an offer that could not be presented.
    ///
    /// @param identity release whose presentation failed
    /// @param failure presentation failure
    private void presentationFailed(ReleaseIdentity identity, Throwable failure) {
        synchronized (stateLock) {
            if (identity.equals(lastOfferedIdentity)) {
                lastOfferedIdentity = null;
            }
        }
        try {
            Lang.handleUncaughtException(failure);
        } catch (RuntimeException ignored) {
            // Presentation diagnostics must not disturb update-check publication.
        }
    }

    /// Stable identity used to avoid repeating the same automatic offer.
    ///
    /// @param channel remote release channel
    /// @param version remote version string
    /// @param downloadUrl remote artifact URL distinguishing rebuilt release metadata
    @NotNullByDefault
    private record ReleaseIdentity(
            UpdateChannel channel,
            String version,
            String downloadUrl) {
        /// Validates one release identity.
        private ReleaseIdentity {
            Objects.requireNonNull(channel, "channel");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(downloadUrl, "downloadUrl");
        }

        /// Creates an identity from one fetched remote release.
        ///
        /// @param remoteVersion fetched remote release
        /// @return stable offer identity
        private static ReleaseIdentity from(RemoteVersion remoteVersion) {
            Objects.requireNonNull(remoteVersion, "remoteVersion");
            return new ReleaseIdentity(
                    remoteVersion.channel(),
                    remoteVersion.version(),
                    remoteVersion.url());
        }
    }
}

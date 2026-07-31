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

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/// Immutable observable state of [SwingUpdateCheckService].
///
/// A failed attempt is represented independently from [#lastSuccessfulResult()], so transient network failures
/// never erase the latest usable remote-version result.
///
/// @param status current service lifecycle state
/// @param activeRequest request currently performing source work, if any
/// @param lastSuccessfulResult latest successful result retained across later failures
/// @param lastFailure latest failed attempt, cleared when another attempt starts or succeeds
/// @param revision monotonically increasing state revision
@NotNullByDefault
public record UpdateCheckSnapshot(
        Status status,
        Optional<UpdateCheckRequest> activeRequest,
        Optional<UpdateCheckResult> lastSuccessfulResult,
        Optional<Failure> lastFailure,
        long revision) {
    /// Validates one complete service snapshot and its state-specific invariants.
    public UpdateCheckSnapshot {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(activeRequest, "activeRequest");
        Objects.requireNonNull(lastSuccessfulResult, "lastSuccessfulResult");
        Objects.requireNonNull(lastFailure, "lastFailure");
        if ((status == Status.CHECKING) != activeRequest.isPresent()) {
            throw new IllegalArgumentException("Only CHECKING snapshots may contain an active request");
        }
        if (status == Status.SUCCEEDED && lastSuccessfulResult.isEmpty()) {
            throw new IllegalArgumentException("SUCCEEDED snapshots require a successful result");
        }
        if (status == Status.FAILED && lastFailure.isEmpty()) {
            throw new IllegalArgumentException("FAILED snapshots require a failure");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
    }

    /// Creates the initial idle state before any check has started.
    ///
    /// @return revision-zero empty snapshot
    public static UpdateCheckSnapshot idle() {
        return new UpdateCheckSnapshot(
                Status.IDLE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0L);
    }

    /// Update-check lifecycle independent from either JavaFX or Swing widgets.
    @NotNullByDefault
    public enum Status {
        /// No attempt has started.
        IDLE,

        /// One request is actively performing source work.
        CHECKING,

        /// The latest completed attempt succeeded.
        SUCCEEDED,

        /// The latest completed attempt failed while an older success may remain available.
        FAILED,

        /// The service no longer accepts work or publishes remote results.
        CLOSED
    }

    /// Immutable diagnostic details for one failed update check.
    ///
    /// Throwable objects are deliberately not retained in the snapshot because they expose mutable stack traces;
    /// the matching completion stage still completes with the original failure instance.
    ///
    /// @param request request that failed
    /// @param failureType fully qualified failure class name
    /// @param message stable non-empty diagnostic message
    /// @param failedAt failure completion time
    @NotNullByDefault
    public record Failure(
            UpdateCheckRequest request,
            String failureType,
            String message,
            Instant failedAt) {
        /// Validates one immutable failure description.
        public Failure {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(failureType, "failureType");
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(failedAt, "failedAt");
            if (failureType.isBlank()) {
                throw new IllegalArgumentException("failureType must not be blank");
            }
            if (message.isBlank()) {
                throw new IllegalArgumentException("message must not be blank");
            }
        }
    }
}

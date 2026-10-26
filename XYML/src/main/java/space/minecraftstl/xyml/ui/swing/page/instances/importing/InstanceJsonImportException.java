// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.importing;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.GameInstanceID;

import java.nio.file.Path;
import java.util.Objects;

/// Identifies user-correctable failures discovered while preparing an instance JSON import.
@NotNullByDefault
public final class InstanceJsonImportException extends Exception {
    /// Serialization version for the checked workflow exception.
    private static final long serialVersionUID = 1L;

    /// Stable failure category used by localized Swing feedback.
    private final Reason reason;

    /// Creates a categorized import failure.
    ///
    /// @param reason stable user-facing failure category
    /// @param message diagnostic message for logs and task details
    /// @param cause underlying parse or I/O failure, or null when validation found the failure
    public InstanceJsonImportException(
            Reason reason,
            String message,
            @Nullable Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /// Returns the stable user-facing failure category.
    ///
    /// @return categorized failure reason
    public Reason reason() {
        return reason;
    }

    /// Creates an invalid-instance-ID failure.
    ///
    /// @param instanceId rejected destination ID
    /// @return categorized failure
    static InstanceJsonImportException invalidInstanceId(String instanceId) {
        return new InstanceJsonImportException(
                Reason.INVALID_INSTANCE_ID,
                "Invalid destination instance ID: " + instanceId,
                null);
    }

    /// Creates an existing-instance conflict failure.
    ///
    /// @param instanceId conflicting destination ID
    /// @return categorized failure
    static InstanceJsonImportException instanceAlreadyExists(GameInstanceID instanceId) {
        return new InstanceJsonImportException(
                Reason.INSTANCE_ALREADY_EXISTS,
                "Destination instance already exists: " + Objects.requireNonNull(instanceId, "instanceId"),
                null);
    }

    /// Creates a malformed or unreadable JSON failure.
    ///
    /// @param source source that could not be parsed
    /// @param cause parser or I/O failure
    /// @return categorized failure
    static InstanceJsonImportException malformedJson(Path source, Throwable cause) {
        return new InstanceJsonImportException(
                Reason.MALFORMED_JSON,
                "Unable to read game instance manifest JSON: " + source,
                Objects.requireNonNull(cause, "cause"));
    }

    /// Stable user-correctable failure categories.
    @NotNullByDefault
    public enum Reason {
        /// Destination ID is blank, unsafe, or reserved.
        INVALID_INSTANCE_ID,

        /// Destination ID conflicts with an existing instance.
        INSTANCE_ALREADY_EXISTS,

        /// Source cannot be read as a game instance manifest JSON document.
        MALFORMED_JSON
    }
}

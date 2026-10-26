// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.instances.management.maintenance;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.awt.Component;
import java.net.URI;
import java.nio.file.Path;

/// Separates native file selection and confirmation dialogs from maintenance task ownership.
@NotNullByDefault
public interface InstanceMaintenanceInteractions {
    /// Chooses one local modpack archive for an existing modpack update.
    ///
    /// @param owner native dialog owner
    /// @return selected `.zip` or `.mrpack` archive, or null after cancellation
    @Nullable Path chooseModpackArchive(Component owner);

    /// Prompts for one direct HTTP or HTTPS modpack source.
    ///
    /// @param owner native dialog owner
    /// @return validated remote archive or server-manifest URI, or null after cancellation
    @Nullable URI chooseModpackUri(Component owner);

    /// Chooses a standalone launch-script destination.
    ///
    /// @param owner native dialog owner
    /// @param initialDirectory instance run directory used when it already exists
    /// @return normalized destination with a supported platform suffix, or null after cancellation
    @Nullable Path chooseLaunchScript(Component owner, Path initialDirectory);

    /// Confirms one destructive cleanup operation.
    ///
    /// @param owner native dialog owner
    /// @param action visible operation name
    /// @param sharedScope whether the target data is shared by multiple instances
    /// @return whether the user explicitly approved the operation
    boolean confirmDestructive(Component owner, String action, boolean sharedScope);

    /// Shows a concise operation failure.
    ///
    /// @param owner native dialog owner
    /// @param title visible operation title
    /// @param detail non-blank failure detail
    void showFailure(Component owner, String title, String detail);

    /// Shows a successful result whose path is useful to the user.
    ///
    /// @param owner native dialog owner
    /// @param title visible operation title
    /// @param detail non-blank completion detail
    void showSuccess(Component owner, String title, String detail);
}

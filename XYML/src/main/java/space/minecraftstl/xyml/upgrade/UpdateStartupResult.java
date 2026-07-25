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
package space.minecraftstl.xyml.upgrade;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Objects;

/// Describes the toolkit-neutral outcome of processing launcher update arguments.
///
/// A caller owns presentation of any notice, so update application can run before either desktop toolkit starts.
@NotNullByDefault
public final class UpdateStartupResult {
    /// Shared result used when ordinary launcher startup should continue.
    private static final UpdateStartupResult CONTINUE = new UpdateStartupResult(false, null, null);

    /// Shared result used when a successful migration or update application already started another process.
    private static final UpdateStartupResult EXIT = new UpdateStartupResult(true, null, null);

    /// Whether the current process must exit instead of starting the launcher UI.
    private final boolean shouldExit;

    /// Semantic notice for the presentation layer, or `null` when no message is required.
    private final @Nullable Notice notice;

    /// Update failure attached to an apply-failure notice, or `null` for non-failure outcomes.
    private final @Nullable IOException failure;

    /// Creates one validated update-startup result.
    ///
    /// @param shouldExit whether the current process must exit
    /// @param notice semantic notice for the presentation layer, or `null`
    /// @param failure update failure, or `null`
    private UpdateStartupResult(
            boolean shouldExit,
            @Nullable Notice notice,
            @Nullable IOException failure) {
        if (!shouldExit && (notice != null || failure != null)) {
            throw new IllegalArgumentException("A continuing launch cannot carry an update notice");
        }
        if ((notice == Notice.APPLY_FAILED) != (failure != null)) {
            throw new IllegalArgumentException("Only an apply-failure notice carries a failure");
        }
        this.shouldExit = shouldExit;
        this.notice = notice;
        this.failure = failure;
    }

    /// Returns the shared ordinary-startup result.
    ///
    /// @return result that allows launcher startup to continue
    public static UpdateStartupResult continueLaunch() {
        return CONTINUE;
    }

    /// Returns the shared quiet-exit result.
    ///
    /// @return result that exits without a user-facing notice
    public static UpdateStartupResult exit() {
        return EXIT;
    }

    /// Creates an exit result carrying a non-failure notice.
    ///
    /// @param notice notice to present before exit
    /// @return result that exits after presenting the notice
    public static UpdateStartupResult exitWithNotice(Notice notice) {
        Objects.requireNonNull(notice, "notice");
        if (notice == Notice.APPLY_FAILED) {
            throw new IllegalArgumentException("Apply-failure notices require a failure");
        }
        return new UpdateStartupResult(true, notice, null);
    }

    /// Creates an exit result carrying an update application failure.
    ///
    /// @param failure update application or migration failure
    /// @return result that exits after presenting the failure
    public static UpdateStartupResult failed(IOException failure) {
        return new UpdateStartupResult(
                true,
                Notice.APPLY_FAILED,
                Objects.requireNonNull(failure, "failure"));
    }

    /// Reports whether the launcher must stop before initializing its UI.
    ///
    /// @return `true` when the current process must exit
    public boolean shouldExit() {
        return shouldExit;
    }

    /// Returns the semantic notice that a desktop presentation layer should show.
    ///
    /// @return notice, or `null` when no message is required
    public @Nullable Notice notice() {
        return notice;
    }

    /// Returns the update failure associated with an apply-failure notice.
    ///
    /// @return failure, or `null` for all other outcomes
    public @Nullable IOException failure() {
        return failure;
    }

    /// Semantic startup notices independent from Swing and JavaFX message types.
    @NotNullByDefault
    public enum Notice {
        /// The current operating system cannot safely replace the launcher artifact.
        UNSUPPORTED_WINDOWS_VERSION,

        /// An update application or old-version migration failed.
        APPLY_FAILED,

        /// A legacy update location requires the user to restart manually.
        MANUAL_REBOOT_REQUIRED
    }
}

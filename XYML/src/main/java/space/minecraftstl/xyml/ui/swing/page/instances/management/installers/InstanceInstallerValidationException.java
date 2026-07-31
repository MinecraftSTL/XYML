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
package space.minecraftstl.xyml.ui.swing.page.instances.management.installers;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.Serial;
import java.util.Objects;

/// Reports an instance installer request that violates the shared loader compatibility policy.
///
/// Presentation code can localize [Reason] without parsing a task failure message. The detail remains
/// intentionally technical because this service is also used by non-Swing callers and tests.
@NotNullByDefault
public final class InstanceInstallerValidationException extends IllegalArgumentException {
    /// Serialization identifier for the stable exception shape.
    @Serial
    private static final long serialVersionUID = 1L;

    /// Stable non-localized validation category.
    private final Reason reason;

    /// Creates one typed installer validation failure.
    ///
    /// @param reason stable validation category
    /// @param detail concise technical description of the rejected input
    public InstanceInstallerValidationException(Reason reason, String detail) {
        super(Objects.requireNonNull(reason, "reason") + ": " + requireDetail(detail));
        this.reason = reason;
    }

    /// Returns the stable rejection category.
    ///
    /// @return typed validation reason
    public Reason reason() {
        return reason;
    }

    /// Rejects empty diagnostic detail so failures remain actionable in logs and tests.
    ///
    /// @param detail technical validation detail
    /// @return exact validated detail
    private static String requireDetail(String detail) {
        String value = Objects.requireNonNull(detail, "detail");
        if (value.isBlank()) {
            throw new IllegalArgumentException("detail must not be blank");
        }
        return value;
    }

    /// Enumerates compatibility and operation precondition failures without presentation text.
    @NotNullByDefault
    public enum Reason {
        /// No remote loader was supplied for an installation operation.
        EMPTY_REMOTE_SELECTION,

        /// Core could not determine the instance's Minecraft version from its primary JAR.
        GAME_VERSION_UNAVAILABLE,

        /// A remote version belongs to a library type that the loader matrix does not manage.
        UNSUPPORTED_LIBRARY_ID,

        /// A remote version belongs to a different Minecraft version than the target instance.
        GAME_VERSION_MISMATCH,

        /// A loader kind was not historically offered for the target Minecraft version.
        LOADER_UNAVAILABLE_FOR_GAME_VERSION,

        /// The caller supplied two versions for the same loader kind in one ordered operation.
        DUPLICATE_LOADER_SELECTION,

        /// The resulting loader set contains a mutually incompatible pair.
        INCOMPATIBLE_LOADER_SELECTION,

        /// An API companion would exist without its required parent loader.
        REQUIRED_PARENT_MISSING,

        /// A requested API companion appears before its requested parent loader.
        REQUIRED_PARENT_AFTER_COMPANION,

        /// The caller attempted to remove the Minecraft base-game library through installer management.
        BASE_GAME_REMOVAL_FORBIDDEN,

        /// Removing a parent loader would leave an installed API companion without its parent.
        REQUIRED_COMPANION_WOULD_BE_ORPHANED,

        /// The requested library is absent, protected, or lacks an explicit safe removal structure.
        LIBRARY_REMOVAL_NOT_ALLOWED,

        /// A requested offline installer path is missing or is not a regular file.
        OFFLINE_INSTALLER_NOT_A_REGULAR_FILE
    }
}

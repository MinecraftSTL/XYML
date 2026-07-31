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
package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.observable.Subscription;
import space.minecraftstl.xyml.observable.ValueChangeListener;

import java.io.IOException;

/// Provides safe authlib-injector server discovery and persistent configuration mutation.
///
/// Endpoint discovery may perform network I/O and callers must therefore invoke [#prepareServer(String)]
/// away from the Swing event dispatch thread. Configuration mutations may synchronously wait for their
/// persistence bridge and must likewise run away from the Swing event dispatch thread.
@NotNullByDefault
public interface AuthlibServerStore {
    /// Returns the latest immutable configured-server snapshot.
    ///
    /// @return current configured server options
    AuthlibServerSnapshot snapshot();

    /// Registers for completed configured-server transitions.
    ///
    /// @param listener server snapshot transition listener
    /// @return independently cancellable registration
    Subscription subscribe(ValueChangeListener<AuthlibServerSnapshot> listener);

    /// Resolves an endpoint and downloads enough metadata for explicit user confirmation.
    ///
    /// The resulting opaque value belongs to this exact store and must not be passed to another one.
    ///
    /// @param endpoint user-supplied authlib-injector endpoint
    /// @return resolved server awaiting persistence
    /// @throws IOException when resolving or parsing endpoint metadata fails
    PreparedAuthlibServer prepareServer(String endpoint) throws IOException;

    /// Persists one previously resolved server unless its endpoint is already configured.
    ///
    /// @param server opaque resolved server created by this store
    /// @param allowReadOnlyOverwrite whether confirmed backup-and-overwrite may make storage writable
    /// @throws AuthlibServerStorageOverwriteRequiredException when storage is read-only and overwrite is not allowed
    void addServer(PreparedAuthlibServer server, boolean allowReadOnlyOverwrite);

    /// Permanently removes one configured endpoint by its stable URL.
    ///
    /// @param serverUrl configured server URL
    /// @param allowReadOnlyOverwrite whether confirmed backup-and-overwrite may make storage writable
    /// @throws AuthlibServerStorageOverwriteRequiredException when storage is read-only and overwrite is not allowed
    void removeServer(String serverUrl, boolean allowReadOnlyOverwrite);
}

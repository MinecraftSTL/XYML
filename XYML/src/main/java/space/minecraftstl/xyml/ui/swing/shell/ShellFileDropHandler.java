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
package space.minecraftstl.xyml.ui.swing.shell;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JComponent;
import javax.swing.TransferHandler;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

/// Routes one supported local file dropped on the application shell to a caller-owned command.
///
/// This handler performs only in-memory transfer inspection on the Swing event-dispatch thread. It
/// deliberately does not stat, open, or parse the path; the receiving workflow owns all filesystem
/// validation and blocking work. Multiple files and non-file transfer flavors are rejected so a
/// single user action cannot ambiguously launch several nested workflows.
@NotNullByDefault
public final class ShellFileDropHandler extends TransferHandler {
    /// Ordered mutable route table confined to the Swing event-dispatch thread.
    private final List<Route> routes = new ArrayList<>();

    /// Creates an empty handler ready to receive independently removable routes.
    private ShellFileDropHandler() {
    }

    /// Creates a local-file transfer handler.
    ///
    /// @param supportedPath pure supported-path predicate
    /// @param openCommand command invoked for one accepted path
    public ShellFileDropHandler(
            Predicate<Path> supportedPath,
            Consumer<Path> openCommand) {
        routes.add(new Route(supportedPath, openCommand));
    }

    /// Registers one ordered file route on a Swing component without replacing compatible routes.
    ///
    /// The returned registration removes only the new route. When it was also responsible for
    /// installing an otherwise empty handler, removing the final route restores a null transfer
    /// handler. An unrelated pre-existing transfer handler is rejected because silently replacing it
    /// would disable an unknown workflow.
    ///
    /// @param target component receiving local-file drops
    /// @param supportedPath pure predicate identifying paths handled by this workflow
    /// @param openCommand command invoked for the first matching route
    /// @return independently removable route registration
    public static RouteRegistration register(
            JComponent target,
            Predicate<Path> supportedPath,
            Consumer<Path> openCommand) {
        JComponent resolvedTarget = Objects.requireNonNull(target, "target");
        Predicate<Path> resolvedPredicate = Objects.requireNonNull(
                supportedPath,
                "supportedPath");
        Consumer<Path> resolvedCommand = Objects.requireNonNull(openCommand, "openCommand");
        AtomicReference<@Nullable RouteRegistration> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> result.set(registerOnEventDispatchThread(
                resolvedTarget,
                resolvedPredicate,
                resolvedCommand)));
        return Objects.requireNonNull(result.get(), "route registration was not created");
    }

    /// Adds one route to the target's shared handler on the Swing event-dispatch thread.
    ///
    /// @param target component receiving local-file drops
    /// @param supportedPath pure path predicate
    /// @param openCommand matching route command
    /// @return independently removable route registration
    private static RouteRegistration registerOnEventDispatchThread(
            JComponent target,
            Predicate<Path> supportedPath,
            Consumer<Path> openCommand) {
        EdtDispatcher.requireEventDispatchThread();
        Route route = new Route(supportedPath, openCommand);
        @Nullable TransferHandler current = target.getTransferHandler();
        final ShellFileDropHandler handler;
        if (current == null) {
            handler = new ShellFileDropHandler();
            target.setTransferHandler(handler);
        } else if (current instanceof ShellFileDropHandler compatible) {
            handler = compatible;
        } else {
            throw new IllegalStateException(
                    "The target already has an incompatible transfer handler");
        }
        handler.routes.add(route);
        return new RegisteredRoute(target, handler, route);
    }

    /// Reports whether the payload contains exactly one supported local file path.
    ///
    /// @param support proposed transfer
    /// @return whether `importData` can route the payload
    @Override
    public boolean canImport(TransferSupport support) {
        Objects.requireNonNull(support, "support");
        @Nullable Path path = extractSinglePath(support.getTransferable());
        return path != null && findRoute(path) != null;
    }

    /// Delivers one supported local file path to the caller-owned command.
    ///
    /// @param support accepted transfer
    /// @return whether the command was invoked successfully
    @Override
    public boolean importData(TransferSupport support) {
        Objects.requireNonNull(support, "support");
        @Nullable Path path = extractSinglePath(support.getTransferable());
        if (path == null) {
            return false;
        }
        @Nullable Route route = findRoute(path);
        if (route == null) {
            return false;
        }
        try {
            route.openCommand().accept(path);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /// Returns the first matching route without allowing one malformed predicate to break Swing DnD.
    ///
    /// @param path normalized absolute transfer path
    /// @return first matching route, or null when none accepts the path
    private @Nullable Route findRoute(Path path) {
        for (Route route : List.copyOf(routes)) {
            try {
                if (route.supportedPath().test(path)) {
                    return route;
                }
            } catch (RuntimeException ignored) {
                // One malformed predicate must not hide compatible routes registered after it.
            }
        }
        return null;
    }

    /// Extracts exactly one normalized path from the standard Java file-list flavor.
    ///
    /// @param transferable platform transfer payload
    /// @return normalized path, or `null` for unsupported or malformed payloads
    private static @Nullable Path extractSinglePath(Transferable transferable) {
        Objects.requireNonNull(transferable, "transferable");
        if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            return null;
        }
        try {
            Object transferred = transferable.getTransferData(DataFlavor.javaFileListFlavor);
            if (!(transferred instanceof List<?> files)
                    || files.size() != 1
                    || !(files.get(0) instanceof File file)) {
                return null;
            }
            return file.toPath().toAbsolutePath().normalize();
        } catch (IOException | UnsupportedFlavorException | RuntimeException ignored) {
            return null;
        }
    }

    /// One immutable route in registration order.
    ///
    /// @param supportedPath pure supported-path predicate
    /// @param openCommand command invoked for one accepted path
    @NotNullByDefault
    private record Route(Predicate<Path> supportedPath, Consumer<Path> openCommand) {
        /// Validates and creates an immutable route.
        private Route {
            Objects.requireNonNull(supportedPath, "supportedPath");
            Objects.requireNonNull(openCommand, "openCommand");
        }
    }

    /// Independently removes one registered shell-file route.
    @NotNullByDefault
    @FunctionalInterface
    public interface RouteRegistration extends AutoCloseable {
        /// Removes this route at most once without disturbing sibling routes.
        @Override
        void close();
    }

    /// Registration retaining the exact target, handler, and route identities it owns.
    @NotNullByDefault
    private static final class RegisteredRoute implements RouteRegistration {
        /// Component on which the shared transfer handler was observed or installed.
        private final JComponent target;

        /// Shared ordered transfer handler containing this registration.
        private final ShellFileDropHandler handler;

        /// Exact route removed by this registration.
        private final Route route;

        /// Whether this registration has already been removed.
        private boolean closed;

        /// Creates one independently removable registration.
        ///
        /// @param target component hosting the shared handler
        /// @param handler shared ordered handler
        /// @param route exact route owned by this registration
        private RegisteredRoute(
                JComponent target,
                ShellFileDropHandler handler,
                Route route) {
            this.target = Objects.requireNonNull(target, "target");
            this.handler = Objects.requireNonNull(handler, "handler");
            this.route = Objects.requireNonNull(route, "route");
        }

        /// Removes only this route and clears the handler when no route remains.
        @Override
        public void close() {
            EdtDispatcher.executeAndWait(this::closeOnEventDispatchThread);
        }

        /// Removes the route and possibly the empty shared handler on the EDT.
        private void closeOnEventDispatchThread() {
            EdtDispatcher.requireEventDispatchThread();
            if (closed) {
                return;
            }
            closed = true;
            handler.routes.remove(route);
            if (handler.routes.isEmpty() && target.getTransferHandler() == handler) {
                target.setTransferHandler(null);
            }
        }
    }
}

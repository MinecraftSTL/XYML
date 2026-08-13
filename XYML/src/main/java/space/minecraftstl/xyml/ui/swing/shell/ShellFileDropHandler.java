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
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.ui.swing.EdtDispatcher;

import javax.swing.JComponent;
import javax.swing.TransferHandler;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

/// Composes local-file, filtered file-list, and text drop routes on one Swing component.
///
/// The handler normalizes local paths but does not open or parse them. Route predicates should remain
/// cheap; receiving workflows own complete validation and blocking work. Single-file routes take
/// precedence, then filtered file-list routes, then text routes.
@NotNullByDefault
public final class ShellFileDropHandler extends TransferHandler {
    /// Ordered single-file routes confined to the Swing event-dispatch thread.
    private final List<Route> routes = new ArrayList<>();

    /// Ordered multi-file routes confined to the Swing event-dispatch thread.
    private final List<FileListRoute> fileListRoutes = new ArrayList<>();

    /// Ordered text routes confined to the Swing event-dispatch thread.
    private final List<TextRoute> textRoutes = new ArrayList<>();

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

    /// Registers a route receiving every matching path from a local file-list transfer.
    ///
    /// Unsupported files in the same drag are ignored. The command receives an immutable list in
    /// transfer order, matching HMCL's filtered multi-file drop behavior.
    ///
    /// @param target component receiving local-file drops
    /// @param supportedPath pure predicate identifying paths handled by this workflow
    /// @param openCommand command invoked with all matching paths
    /// @return independently removable route registration
    public static RouteRegistration registerFiles(
            JComponent target,
            Predicate<Path> supportedPath,
            Consumer<@Unmodifiable List<Path>> openCommand) {
        JComponent resolvedTarget = Objects.requireNonNull(target, "target");
        Predicate<Path> resolvedPredicate = Objects.requireNonNull(supportedPath, "supportedPath");
        Consumer<@Unmodifiable List<Path>> resolvedCommand = Objects.requireNonNull(openCommand, "openCommand");
        AtomicReference<@Nullable RouteRegistration> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> {
            ShellFileDropHandler handler = handlerFor(resolvedTarget);
            FileListRoute route = new FileListRoute(resolvedPredicate, resolvedCommand);
            handler.fileListRoutes.add(route);
            result.set(new RegisteredRoute(
                    resolvedTarget,
                    handler,
                    () -> handler.fileListRoutes.remove(route)));
        });
        return Objects.requireNonNull(result.get(), "file-list route registration was not created");
    }

    /// Registers a route receiving text from browser or desktop drag sources.
    ///
    /// @param target component receiving text drops
    /// @param supportedText pure predicate identifying supported text
    /// @param openCommand command invoked with accepted trimmed text
    /// @return independently removable route registration
    public static RouteRegistration registerText(
            JComponent target,
            Predicate<String> supportedText,
            Consumer<String> openCommand) {
        JComponent resolvedTarget = Objects.requireNonNull(target, "target");
        Predicate<String> resolvedPredicate = Objects.requireNonNull(supportedText, "supportedText");
        Consumer<String> resolvedCommand = Objects.requireNonNull(openCommand, "openCommand");
        AtomicReference<@Nullable RouteRegistration> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> {
            ShellFileDropHandler handler = handlerFor(resolvedTarget);
            TextRoute route = new TextRoute(resolvedPredicate, resolvedCommand);
            handler.textRoutes.add(route);
            result.set(new RegisteredRoute(
                    resolvedTarget,
                    handler,
                    () -> handler.textRoutes.remove(route)));
        });
        return Objects.requireNonNull(result.get(), "text route registration was not created");
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
        ShellFileDropHandler handler = handlerFor(target);
        handler.routes.add(route);
        return new RegisteredRoute(target, handler, () -> handler.routes.remove(route));
    }

    /// Returns or installs the one compatible composite handler on a target.
    ///
    /// @param target component receiving drops
    /// @return existing or newly installed handler
    private static ShellFileDropHandler handlerFor(JComponent target) {
        EdtDispatcher.requireEventDispatchThread();
        @Nullable TransferHandler current = target.getTransferHandler();
        if (current == null) {
            ShellFileDropHandler handler = new ShellFileDropHandler();
            target.setTransferHandler(handler);
            return handler;
        }
        if (current instanceof ShellFileDropHandler compatible) {
            return compatible;
        }
        throw new IllegalStateException("The target already has an incompatible transfer handler");
    }

    /// Reports whether any registered route accepts the transfer payload.
    ///
    /// @param support proposed transfer
    /// @return whether `importData` can route the payload
    @Override
    public boolean canImport(TransferSupport support) {
        Objects.requireNonNull(support, "support");
        @Unmodifiable List<Path> paths = extractPaths(support.getTransferable());
        if (paths.size() == 1 && findRoute(paths.get(0)) != null) {
            return true;
        }
        if (!paths.isEmpty() && findFileListRoute(paths) != null) {
            return true;
        }
        @Nullable String text = extractText(support.getTransferable());
        return text != null && findTextRoute(support.getComponent(), text) != null;
    }

    /// Delivers the payload to the first matching route by route-kind precedence.
    ///
    /// @param support accepted transfer
    /// @return whether the command was invoked successfully
    @Override
    public boolean importData(TransferSupport support) {
        Objects.requireNonNull(support, "support");
        Transferable transferable = support.getTransferable();
        @Unmodifiable List<Path> paths = extractPaths(transferable);
        if (paths.size() == 1) {
            Path path = paths.get(0);
            @Nullable Route route = findRoute(path);
            if (route != null) {
                try {
                    route.openCommand().accept(path);
                    return true;
                } catch (RuntimeException ignored) {
                    return false;
                }
            }
        }
        if (!paths.isEmpty()) {
            @Nullable FileListRoute fileListRoute = findFileListRoute(paths);
            if (fileListRoute != null) {
                @Unmodifiable List<Path> accepted = matchingPaths(fileListRoute, paths);
                if (!accepted.isEmpty()) {
                    try {
                        fileListRoute.openCommand().accept(accepted);
                        return true;
                    } catch (RuntimeException ignored) {
                        return false;
                    }
                }
            }
        }
        @Nullable String text = extractText(transferable);
        if (text != null) {
            @Nullable TextRoute textRoute = findTextRoute(support.getComponent(), text);
            if (textRoute != null) {
                try {
                    textRoute.openCommand().accept(text);
                    return true;
                } catch (RuntimeException ignored) {
                    return false;
                }
            }
        }
        return false;
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

    /// Returns the first multi-file route accepting at least one path.
    private @Nullable FileListRoute findFileListRoute(@Unmodifiable List<Path> paths) {
        for (FileListRoute route : List.copyOf(fileListRoutes)) {
            if (!matchingPaths(route, paths).isEmpty()) {
                return route;
            }
        }
        return null;
    }

    /// Filters paths accepted by one multi-file route without exposing predicate failures.
    private static @Unmodifiable List<Path> matchingPaths(
            FileListRoute route,
            @Unmodifiable List<Path> paths) {
        List<Path> accepted = new ArrayList<>();
        for (Path path : paths) {
            try {
                if (route.supportedPath().test(path)) {
                    accepted.add(path);
                }
            } catch (RuntimeException ignored) {
                // A malformed predicate must not escape the Swing transfer callback.
            }
        }
        return List.copyOf(accepted);
    }

    /// Returns the first route accepting transferred text.
    private @Nullable TextRoute findTextRoute(Component transferTarget, String text) {
        @Nullable TextRoute localRoute = findLocalTextRoute(text);
        if (localRoute != null) {
            return localRoute;
        }
        @Nullable Container ancestor = Objects.requireNonNull(transferTarget, "transferTarget").getParent();
        while (ancestor != null) {
            if (ancestor instanceof JComponent component
                    && component.getTransferHandler() instanceof ShellFileDropHandler handler) {
                @Nullable TextRoute inheritedRoute = handler.findLocalTextRoute(text);
                if (inheritedRoute != null) {
                    return inheritedRoute;
                }
            }
            ancestor = ancestor.getParent();
        }
        return null;
    }

    /// Returns the first local route accepting transferred text.
    private @Nullable TextRoute findLocalTextRoute(String text) {
        for (TextRoute route : List.copyOf(textRoutes)) {
            try {
                if (route.supportedText().test(text)) {
                    return route;
                }
            } catch (RuntimeException ignored) {
                // One malformed predicate must not hide compatible routes registered after it.
            }
        }
        return null;
    }

    /// Extracts normalized paths from the standard Java file-list flavor.
    ///
    /// @param transferable platform transfer payload
    /// @return immutable normalized paths, or an empty list for unsupported or malformed payloads
    private static @Unmodifiable List<Path> extractPaths(Transferable transferable) {
        Objects.requireNonNull(transferable, "transferable");
        if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            return List.of();
        }
        try {
            Object transferred = transferable.getTransferData(DataFlavor.javaFileListFlavor);
            if (!(transferred instanceof List<?> files)) {
                return List.of();
            }
            List<Path> paths = new ArrayList<>(files.size());
            for (Object file : files) {
                if (!(file instanceof File source)) {
                    return List.of();
                }
                paths.add(source.toPath().toAbsolutePath().normalize());
            }
            return List.copyOf(paths);
        } catch (IOException | UnsupportedFlavorException | RuntimeException ignored) {
            return List.of();
        }
    }

    /// Extracts trimmed browser or desktop text from the standard string flavor.
    private static @Nullable String extractText(Transferable transferable) {
        Objects.requireNonNull(transferable, "transferable");
        if (!transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
            return null;
        }
        try {
            Object transferred = transferable.getTransferData(DataFlavor.stringFlavor);
            if (transferred instanceof String text && !text.isBlank()) {
                return text.trim();
            }
        } catch (IOException | UnsupportedFlavorException | RuntimeException ignored) {
            // Unsupported text payloads are not importable.
        }
        return null;
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

    /// Immutable filtered multi-file route.
    @NotNullByDefault
    private record FileListRoute(
            Predicate<Path> supportedPath,
            Consumer<@Unmodifiable List<Path>> openCommand) {
        /// Validates one multi-file route.
        private FileListRoute {
            Objects.requireNonNull(supportedPath, "supportedPath");
            Objects.requireNonNull(openCommand, "openCommand");
        }
    }

    /// Immutable text route.
    @NotNullByDefault
    private record TextRoute(Predicate<String> supportedText, Consumer<String> openCommand) {
        /// Validates one text route.
        private TextRoute {
            Objects.requireNonNull(supportedText, "supportedText");
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

        /// Exact route-removal action owned by this registration.
        private final Runnable removeRoute;

        /// Whether this registration has already been removed.
        private boolean closed;

        /// Creates one independently removable registration.
        ///
        /// @param target component hosting the shared handler
        /// @param handler shared ordered handler
        /// @param removeRoute exact route-removal action owned by this registration
        private RegisteredRoute(
                JComponent target,
                ShellFileDropHandler handler,
                Runnable removeRoute) {
            this.target = Objects.requireNonNull(target, "target");
            this.handler = Objects.requireNonNull(handler, "handler");
            this.removeRoute = Objects.requireNonNull(removeRoute, "removeRoute");
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
            removeRoute.run();
            if (handler.routes.isEmpty()
                    && handler.fileListRoutes.isEmpty()
                    && handler.textRoutes.isEmpty()
                    && target.getTransferHandler() == handler) {
                target.setTransferHandler(null);
            }
        }
    }
}

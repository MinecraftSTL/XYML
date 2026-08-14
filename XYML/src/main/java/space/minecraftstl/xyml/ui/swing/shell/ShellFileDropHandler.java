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

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
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
import java.io.Reader;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
    /// Maximum browser text payload inspected synchronously by one drop operation.
    private static final int MAX_TRANSFER_TEXT_LENGTH = 64 * 1024;

    /// Maximum distinct browser representations considered for one drop.
    private static final int MAX_TRANSFER_TEXT_CANDIDATES = 16;

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
        if (support.isDrop()) {
            // Windows OLE payloads are not guaranteed to be readable until Swing accepts the drop.
            return canImportDropFlavors(support.getComponent(), support);
        }
        Transferable transferable = support.getTransferable();
        @Unmodifiable List<Path> paths = extractPaths(transferable);
        if (paths.size() == 1 && findRoute(paths.get(0)) != null) {
            return true;
        }
        if (!paths.isEmpty() && findFileListRoute(paths) != null) {
            return true;
        }
        for (String text : extractTextCandidates(transferable)) {
            if (findTextRoute(support.getComponent(), text) != null) {
                return true;
            }
        }
        return false;
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
        for (String text : extractTextCandidates(transferable)) {
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

    /// Reports whether an unread native payload exposes a flavor owned by any reachable route.
    ///
    /// Native Windows drag sources can defer `getTransferData` until the target accepts the drop. This
    /// method therefore inspects only advertised flavors and never requests their values.
    ///
    /// @param transferTarget component receiving the native drag
    /// @param transferable deferred native payload
    /// @return whether a file or reachable text route can inspect the payload after drop acceptance
    boolean canImportDropFlavors(
            Component transferTarget,
            Transferable transferable) {
        Objects.requireNonNull(transferTarget, "transferTarget");
        Transferable payload = Objects.requireNonNull(transferable, "transferable");
        return canImportDropFlavors(
                transferTarget,
                payload.getTransferDataFlavors());
    }

    /// Reports whether an unread native Swing support exposes a flavor owned by any reachable route.
    ///
    /// @param transferTarget component receiving the native drag
    /// @param support native Swing transfer support
    /// @return whether a file or reachable text route can inspect the payload after drop acceptance
    private boolean canImportDropFlavors(
            Component transferTarget,
            TransferSupport support) {
        Objects.requireNonNull(transferTarget, "transferTarget");
        TransferSupport nativeSupport = Objects.requireNonNull(support, "support");
        return canImportDropFlavors(transferTarget, nativeSupport.getDataFlavors());
    }

    /// Reports whether advertised flavors can be handled without reading transfer data.
    ///
    /// @param transferTarget component receiving the native drag
    /// @param flavors advertised native flavors
    /// @return whether a file or reachable text route can inspect the payload after drop acceptance
    private boolean canImportDropFlavors(
            Component transferTarget,
            DataFlavor @Unmodifiable [] flavors) {
        Objects.requireNonNull(transferTarget, "transferTarget");
        DataFlavor[] advertised = Objects.requireNonNull(flavors, "flavors");
        if ((!routes.isEmpty() || !fileListRoutes.isEmpty()) && hasPotentialFileFlavor(advertised)) {
            return true;
        }
        return hasReachableTextRoute(transferTarget) && hasPotentialTextFlavor(advertised);
    }

    /// Returns whether this handler or a shell ancestor owns at least one text route.
    ///
    /// @param transferTarget component receiving the transfer
    /// @return whether browser text can be routed without reading it during native drag-over
    private boolean hasReachableTextRoute(Component transferTarget) {
        if (!textRoutes.isEmpty()) {
            return true;
        }
        @Nullable Container ancestor = Objects.requireNonNull(transferTarget, "transferTarget").getParent();
        while (ancestor != null) {
            if (ancestor instanceof JComponent component
                    && component.getTransferHandler() instanceof ShellFileDropHandler handler
                    && !handler.textRoutes.isEmpty()) {
                return true;
            }
            ancestor = ancestor.getParent();
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

    /// Extracts normalized paths from Java file lists or desktop file-URI transfers.
    ///
    /// @param transferable platform transfer payload
    /// @return immutable normalized paths, or an empty list for unsupported or malformed payloads
    private static @Unmodifiable List<Path> extractPaths(Transferable transferable) {
        Transferable payload = Objects.requireNonNull(transferable, "transferable");
        @Nullable DataFlavor fileListFlavor = findMatchingFlavor(payload, DataFlavor.javaFileListFlavor);
        if (fileListFlavor != null) {
            try {
                Object transferred = payload.getTransferData(fileListFlavor);
                if (transferred instanceof List<?> files) {
                    List<Path> paths = new ArrayList<>(files.size());
                    for (Object file : files) {
                        if (!(file instanceof File source)) {
                            paths.clear();
                            break;
                        }
                        paths.add(source.toPath().toAbsolutePath().normalize());
                    }
                    if (!paths.isEmpty()) {
                        return List.copyOf(paths);
                    }
                }
            } catch (IOException | UnsupportedFlavorException | RuntimeException ignored) {
                // File-URI flavors may still carry the same local paths below.
            }
        }
        List<Path> uriPaths = fileUriPaths(readFirstTextFlavor(payload, "text/uri-list"));
        if (!uriPaths.isEmpty()) {
            return uriPaths;
        }
        @Nullable String javaUrl = readJavaUrlFlavor(payload);
        @Nullable Path javaUrlPath = fileUriPath(javaUrl);
        return javaUrlPath == null ? List.of() : List.of(javaUrlPath);
    }

    /// Extracts ordered browser or desktop text representations without allowing one to hide another.
    private static @Unmodifiable List<String> extractTextCandidates(Transferable transferable) {
        Transferable payload = Objects.requireNonNull(transferable, "transferable");
        Set<String> candidates = new LinkedHashSet<>();
        addHtmlCandidates(candidates, readFirstTextFlavor(payload, "text/html"));
        addCandidate(candidates, readJavaUrlFlavor(payload));
        addUriListCandidates(candidates, readFirstTextFlavor(payload, "text/uri-list"));
        addUriListCandidates(candidates, readFirstTextFlavor(payload, "text/x-moz-url"));
        @Nullable DataFlavor stringFlavor = findMatchingFlavor(payload, DataFlavor.stringFlavor);
        if (stringFlavor != null) {
            try {
                Object transferred = payload.getTransferData(stringFlavor);
                if (transferred instanceof String text) {
                    addCandidate(candidates, text);
                }
            } catch (IOException | UnsupportedFlavorException | RuntimeException ignored) {
                // Browser-specific text flavors may still provide the same payload below.
            }
        }
        addCandidate(candidates, readFirstTextFlavor(payload, "text/plain"));
        return List.copyOf(candidates);
    }

    /// Returns whether a deferred payload might contain local files.
    ///
    /// @param transferable deferred platform payload
    /// @return whether the payload advertises a supported local-file representation
    private static boolean hasPotentialFileFlavor(Transferable transferable) {
        Transferable payload = Objects.requireNonNull(transferable, "transferable");
        return hasPotentialFileFlavor(payload.getTransferDataFlavors());
    }

    /// Returns whether one advertised flavor might contain local files.
    ///
    /// @param flavors advertised platform flavors
    /// @return whether the flavor list contains a supported local-file representation
    private static boolean hasPotentialFileFlavor(DataFlavor @Unmodifiable [] flavors) {
        DataFlavor[] advertised = Objects.requireNonNull(flavors, "flavors");
        if (findMatchingFlavor(advertised, DataFlavor.javaFileListFlavor) != null) {
            return true;
        }
        for (DataFlavor flavor : advertised) {
            if (flavor.isMimeTypeEqual("text/uri-list")
                    || flavor.isMimeTypeEqual("application/x-java-url")) {
                return true;
            }
        }
        return false;
    }

    /// Returns whether a deferred payload might contain browser or desktop text.
    ///
    /// @param transferable deferred platform payload
    /// @return whether the payload advertises a supported text representation
    private static boolean hasPotentialTextFlavor(Transferable transferable) {
        Transferable payload = Objects.requireNonNull(transferable, "transferable");
        return hasPotentialTextFlavor(payload.getTransferDataFlavors());
    }

    /// Returns whether one advertised flavor might contain browser or desktop text.
    ///
    /// @param flavors advertised platform flavors
    /// @return whether the flavor list contains a supported text representation
    private static boolean hasPotentialTextFlavor(DataFlavor @Unmodifiable [] flavors) {
        DataFlavor[] advertised = Objects.requireNonNull(flavors, "flavors");
        if (findMatchingFlavor(advertised, DataFlavor.stringFlavor) != null) {
            return true;
        }
        for (DataFlavor flavor : advertised) {
            if (flavor.isFlavorTextType()
                    || flavor.isMimeTypeEqual("application/x-java-url")) {
                return true;
            }
        }
        return false;
    }

    /// Finds the first platform flavor compatible with one standard Swing flavor.
    ///
    /// Windows and browser data providers sometimes add representation parameters while retaining
    /// the same MIME contract. `DataFlavor.match` accepts those compatible variants without reading
    /// deferred transfer data.
    ///
    /// @param transferable platform transfer payload
    /// @param requested standard flavor contract
    /// @return compatible advertised flavor, or null when absent
    private static @Nullable DataFlavor findMatchingFlavor(
            Transferable transferable,
            DataFlavor requested) {
        return findMatchingFlavor(
                Objects.requireNonNull(transferable, "transferable").getTransferDataFlavors(),
                requested);
    }

    /// Finds the first compatible flavor in an already advertised flavor array.
    ///
    /// @param flavors advertised platform flavors
    /// @param requested standard flavor contract
    /// @return compatible advertised flavor, or null when absent
    private static @Nullable DataFlavor findMatchingFlavor(
            DataFlavor @Unmodifiable [] flavors,
            DataFlavor requested) {
        DataFlavor expected = Objects.requireNonNull(requested, "requested");
        for (DataFlavor flavor : Objects.requireNonNull(flavors, "flavors")) {
            if (flavor.match(expected)) {
                return flavor;
            }
        }
        return null;
    }

    /// Reads the first decodable text flavor with one exact MIME type.
    ///
    /// @param transferable platform transfer payload
    /// @param mimeType MIME type without parameters
    /// @return decoded bounded text, or null when every matching flavor is unreadable
    private static @Nullable String readFirstTextFlavor(
            Transferable transferable,
            String mimeType) {
        for (DataFlavor flavor : Objects.requireNonNull(
                transferable,
                "transferable").getTransferDataFlavors()) {
            if (!flavor.isMimeTypeEqual(Objects.requireNonNull(mimeType, "mimeType"))) {
                continue;
            }
            @Nullable String text = readTextFlavor(transferable, flavor);
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    /// Reads one text flavor through the platform's charset-aware reader with a bounded size.
    ///
    /// @param transferable platform transfer payload
    /// @param flavor supported text flavor
    /// @return decoded text, or null for malformed, unsupported, or oversized data
    private static @Nullable String readTextFlavor(
            Transferable transferable,
            DataFlavor flavor) {
        try (Reader reader = Objects.requireNonNull(flavor, "flavor")
                .getReaderForText(Objects.requireNonNull(transferable, "transferable"))) {
            StringBuilder text = new StringBuilder();
            char[] buffer = new char[1024];
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                if (text.length() + count > MAX_TRANSFER_TEXT_LENGTH) {
                    return null;
                }
                text.append(buffer, 0, count);
            }
            return text.toString();
        } catch (IOException | UnsupportedFlavorException | RuntimeException ignored) {
            return null;
        }
    }

    /// Reads a Java URL flavor exposed by Chromium-family desktop drag sources.
    ///
    /// @param transferable platform transfer payload
    /// @return URL text, or null when absent or malformed
    private static @Nullable String readJavaUrlFlavor(Transferable transferable) {
        Transferable payload = Objects.requireNonNull(transferable, "transferable");
        for (DataFlavor flavor : payload.getTransferDataFlavors()) {
            if (!flavor.isMimeTypeEqual("application/x-java-url")) {
                continue;
            }
            try {
                Object transferred = payload.getTransferData(flavor);
                if (transferred instanceof URL url) {
                    String text = url.toExternalForm();
                    return text.length() <= MAX_TRANSFER_TEXT_LENGTH ? text : null;
                }
                if (transferred instanceof URI uri) {
                    String text = uri.toASCIIString();
                    return text.length() <= MAX_TRANSFER_TEXT_LENGTH ? text : null;
                }
                if (transferred instanceof String text && text.length() <= MAX_TRANSFER_TEXT_LENGTH) {
                    return text;
                }
            } catch (IOException | UnsupportedFlavorException | RuntimeException ignored) {
                // Another representation of the same MIME type may still be readable.
            }
        }
        return null;
    }

    /// Adds browser HTML clipboard attributes and links before generic visible text.
    ///
    /// @param candidates ordered bounded candidate collection
    /// @param html decoded HTML transfer fragment, or null
    private static void addHtmlCandidates(Set<String> candidates, @Nullable String html) {
        if (html == null || html.isBlank()) {
            return;
        }
        try {
            Element body = Jsoup.parseBodyFragment(html).body();
            for (Element element : body.select("[data-clipboard-text]")) {
                addCandidate(candidates, element.attr("data-clipboard-text"));
            }
            for (Element element : body.select("a[href]")) {
                addCandidate(candidates, element.attr("href"));
            }
        } catch (RuntimeException ignored) {
            // Malformed browser markup cannot prevent plain-text representations from being tried.
        }
    }

    /// Adds every non-comment line from URI-list and Mozilla URL transfers.
    ///
    /// @param candidates ordered bounded candidate collection
    /// @param uriList decoded line-oriented URL list, or null
    private static void addUriListCandidates(Set<String> candidates, @Nullable String uriList) {
        if (uriList == null) {
            return;
        }
        for (String line : uriList.lines().toList()) {
            String candidate = line.trim();
            if (!candidate.isEmpty() && !candidate.startsWith("#")) {
                addCandidate(candidates, candidate);
            }
        }
    }

    /// Adds one trimmed bounded representation while preserving the first occurrence.
    ///
    /// @param candidates ordered bounded candidate collection
    /// @param candidate browser representation, or null
    private static void addCandidate(Set<String> candidates, @Nullable String candidate) {
        if (candidate == null || candidates.size() >= MAX_TRANSFER_TEXT_CANDIDATES) {
            return;
        }
        String normalized = candidate.trim();
        if (!normalized.isEmpty() && normalized.length() <= MAX_TRANSFER_TEXT_LENGTH) {
            candidates.add(normalized);
        }
    }

    /// Converts every file URI in one desktop URI-list transfer to a normalized local path.
    ///
    /// @param uriList decoded URI-list text, or null
    /// @return immutable file paths in transfer order
    private static @Unmodifiable List<Path> fileUriPaths(@Nullable String uriList) {
        if (uriList == null) {
            return List.of();
        }
        List<Path> paths = new ArrayList<>();
        for (String line : uriList.lines().toList()) {
            String candidate = line.trim();
            if (candidate.isEmpty() || candidate.startsWith("#")) {
                continue;
            }
            @Nullable Path path = fileUriPath(candidate);
            if (path != null) {
                paths.add(path);
            }
        }
        return List.copyOf(paths);
    }

    /// Converts one absolute file URI to a normalized local path.
    ///
    /// @param candidate URI text, or null
    /// @return normalized local path, or null for non-file and malformed URIs
    private static @Nullable Path fileUriPath(@Nullable String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(candidate.trim());
            if (!"file".equalsIgnoreCase(uri.getScheme())
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                return null;
            }
            return Path.of(uri).toAbsolutePath().normalize();
        } catch (RuntimeException ignored) {
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

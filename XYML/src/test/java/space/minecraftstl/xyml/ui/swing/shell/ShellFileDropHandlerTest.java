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
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.TransferHandler;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.dnd.InvalidDnDOperationException;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies deterministic single-file filtering and command delivery for shell drag-and-drop.
@NotNullByDefault
final class ShellFileDropHandlerTest {
    /// Accepts one supported file and delivers its normalized absolute path once.
    @Test
    void importsOneSupportedFile() {
        AtomicReference<@Nullable Path> opened = new AtomicReference<>();
        ShellFileDropHandler handler = new ShellFileDropHandler(
                path -> path.getFileName().toString().endsWith(".dat"),
                opened::set);
        TransferHandler.TransferSupport support = support(List.of(new File("level.dat")));

        assertTrue(handler.canImport(support));
        assertTrue(handler.importData(support));
        assertEquals(Path.of("level.dat").toAbsolutePath().normalize(), opened.get());
    }

    /// Rejects multiple files and unsupported file extensions without invoking the command.
    @Test
    void rejectsAmbiguousAndUnsupportedFiles() {
        AtomicReference<@Nullable Path> opened = new AtomicReference<>();
        ShellFileDropHandler handler = new ShellFileDropHandler(
                path -> path.getFileName().toString().endsWith(".dat"),
                opened::set);
        TransferHandler.TransferSupport multiple = support(List.of(
                new File("level.dat"),
                new File("r.0.0.mca")));
        TransferHandler.TransferSupport unsupported = support(List.of(new File("notes.txt")));

        assertFalse(handler.canImport(multiple));
        assertFalse(handler.importData(multiple));
        assertFalse(handler.canImport(unsupported));
        assertFalse(handler.importData(unsupported));
        assertNull(opened.get());
    }

    /// Rejects transfer payloads that do not expose the platform file-list flavor.
    @Test
    void rejectsNonFileTransferFlavor() {
        ShellFileDropHandler handler = new ShellFileDropHandler(path -> true, ignored -> { });
        TransferHandler.TransferSupport support = new TransferHandler.TransferSupport(
                new JPanel(),
                new StringTransferable("level.dat"));

        assertFalse(handler.canImport(support));
        assertFalse(handler.importData(support));
    }

    /// Preserves registration order and removes one workflow without disturbing its siblings.
    @Test
    void composesOrderedIndependentlyRemovableRoutes() {
        JPanel target = new JPanel();
        List<String> deliveries = new ArrayList<>();
        ShellFileDropHandler.RouteRegistration firstJson = ShellFileDropHandler.register(
                target,
                path -> path.getFileName().toString().endsWith(".json"),
                path -> deliveries.add("first:" + path.getFileName()));
        ShellFileDropHandler.RouteRegistration secondJson = ShellFileDropHandler.register(
                target,
                path -> path.getFileName().toString().endsWith(".json"),
                path -> deliveries.add("second:" + path.getFileName()));
        ShellFileDropHandler.RouteRegistration nbt = ShellFileDropHandler.register(
                target,
                path -> path.getFileName().toString().endsWith(".dat"),
                path -> deliveries.add("nbt:" + path.getFileName()));
        TransferHandler handler = Objects.requireNonNull(target.getTransferHandler());

        assertTrue(handler.importData(support(List.of(new File("1.json")))));
        assertEquals(List.of("first:1.json"), deliveries);

        firstJson.close();
        assertTrue(handler.importData(support(List.of(new File("2.json")))));
        assertTrue(handler.importData(support(List.of(new File("level.dat")))));
        assertEquals(List.of("first:1.json", "second:2.json", "nbt:level.dat"), deliveries);

        secondJson.close();
        assertFalse(handler.canImport(support(List.of(new File("3.json")))));
        assertEquals(handler, target.getTransferHandler());
        nbt.close();
        assertNull(target.getTransferHandler());
    }

    /// Filters a multi-file transfer while retaining independently removable text routing.
    @Test
    void composesFilteredFileListsAndTextRoutes() {
        JPanel target = new JPanel();
        AtomicReference<@Nullable @Unmodifiable List<Path>> imported = new AtomicReference<>();
        AtomicReference<@Nullable String> openedText = new AtomicReference<>();
        ShellFileDropHandler.RouteRegistration files = ShellFileDropHandler.registerFiles(
                target,
                path -> path.getFileName().toString().endsWith(".jar"),
                imported::set);
        ShellFileDropHandler.RouteRegistration text = ShellFileDropHandler.registerText(
                target,
                value -> value.startsWith("authlib-injector:"),
                openedText::set);
        TransferHandler handler = Objects.requireNonNull(target.getTransferHandler());

        TransferHandler.TransferSupport mixedFiles = support(List.of(
                new File("first.jar"),
                new File("notes.txt"),
                new File("second.jar")));
        assertTrue(handler.canImport(mixedFiles));
        assertTrue(handler.importData(mixedFiles));
        assertEquals(
                List.of(
                        Path.of("first.jar").toAbsolutePath().normalize(),
                        Path.of("second.jar").toAbsolutePath().normalize()),
                imported.get());

        TransferHandler.TransferSupport textSupport = new TransferHandler.TransferSupport(
                target,
                new StringTransferable(" authlib-injector:yggdrasil-server:value "));
        assertTrue(handler.canImport(textSupport));
        assertTrue(handler.importData(textSupport));
        assertEquals("authlib-injector:yggdrasil-server:value", openedText.get());

        files.close();
        assertFalse(handler.canImport(mixedFiles));
        assertEquals(handler, target.getTransferHandler());
        text.close();
        assertNull(target.getTransferHandler());
    }

    /// A child page's file handler inherits a global text route from its shell ancestor.
    @Test
    void inheritsTextRoutesFromAncestorHandler() {
        JPanel shell = new JPanel();
        JPanel page = new JPanel();
        shell.add(page);
        AtomicReference<@Nullable String> openedText = new AtomicReference<>();
        ShellFileDropHandler.RouteRegistration globalText = ShellFileDropHandler.registerText(
                shell,
                value -> value.startsWith("authlib-injector:"),
                openedText::set);
        ShellFileDropHandler.RouteRegistration pageFiles = ShellFileDropHandler.registerFiles(
                page,
                path -> path.getFileName().toString().endsWith(".jar"),
                ignored -> { });
        TransferHandler pageHandler = Objects.requireNonNull(page.getTransferHandler());
        TransferHandler.TransferSupport support = new TransferHandler.TransferSupport(
                page,
                new StringTransferable("authlib-injector:yggdrasil-server:value"));

        assertTrue(pageHandler.canImport(support));
        assertTrue(pageHandler.importData(support));
        assertEquals("authlib-injector:yggdrasil-server:value", openedText.get());

        pageFiles.close();
        globalText.close();
        assertNull(page.getTransferHandler());
        assertNull(shell.getTransferHandler());
    }

    /// A non-composable child handler can explicitly forward browser text to the global shell route.
    @Test
    void delegatesTextFromCustomChildHandlerToAncestorRoute() {
        JPanel shell = new JPanel();
        JPanel customChild = new JPanel();
        shell.add(customChild);
        AtomicReference<@Nullable String> openedText = new AtomicReference<>();
        ShellFileDropHandler.RouteRegistration globalText = ShellFileDropHandler.registerText(
                shell,
                value -> value.startsWith("authlib-injector:"),
                openedText::set);
        customChild.setTransferHandler(new AncestorTextDelegatingTransferHandler());
        TransferHandler childHandler = Objects.requireNonNull(customChild.getTransferHandler());
        TransferHandler.TransferSupport support = new TransferHandler.TransferSupport(
                customChild,
                new StringTransferable("authlib-injector:yggdrasil-server:value"));

        assertTrue(childHandler.canImport(support));
        assertTrue(childHandler.importData(support));
        assertEquals("authlib-injector:yggdrasil-server:value", openedText.get());

        customChild.setTransferHandler(null);
        globalText.close();
        assertNull(shell.getTransferHandler());
    }

    /// Browser plain text reaches the protocol route when the Java String flavor is absent.
    @Test
    void importsBrowserPlainTextFlavor() throws ClassNotFoundException {
        JPanel target = new JPanel();
        List<String> opened = new ArrayList<>();
        String payload = "authlib-injector:yggdrasil-server:https%3A%2F%2Fexample.com%2Fapi";
        ShellFileDropHandler.RouteRegistration route = ShellFileDropHandler.registerText(
                target,
                payload::equals,
                opened::add);
        TransferHandler handler = Objects.requireNonNull(target.getTransferHandler());
        DataFlavor plainText = new DataFlavor(
                "text/plain;charset=UTF-8;class=java.io.InputStream");

        TransferHandler.TransferSupport plainSupport = new TransferHandler.TransferSupport(
                target,
                new TextFlavorTransferable(
                        plainText,
                        () -> new ByteArrayInputStream(
                                (" " + payload + " ").getBytes(StandardCharsets.UTF_8))));

        assertTrue(handler.canImport(plainSupport));
        assertTrue(handler.importData(plainSupport));
        assertEquals(List.of(payload), opened);

        route.close();
        assertNull(target.getTransferHandler());
    }

    /// HTML attributes and URI lists are not guessed as launcher protocol text.
    @Test
    void rejectsNonProtocolBrowserMetadata() throws ClassNotFoundException {
        JPanel target = new JPanel();
        String endpoint = "https://home.minecraftstl.space:8880/api/yggdrasil";
        ShellFileDropHandler.RouteRegistration route = ShellFileDropHandler.registerText(
                target,
                ignored -> true,
                ignored -> { });
        TransferHandler handler = Objects.requireNonNull(target.getTransferHandler());
        DataFlavor html = new DataFlavor(
                "text/html;charset=UTF-8;class=java.io.InputStream");
        DataFlavor uriList = new DataFlavor("text/uri-list;class=java.lang.String");
        TransferHandler.TransferSupport htmlSupport = new TransferHandler.TransferSupport(
                target,
                new TextFlavorTransferable(
                        html,
                        () -> new ByteArrayInputStream(
                                ("<button draggable=\"true\" data-clipboard-text=\"" + endpoint
                                        + "\">Drag this button</button>")
                                        .getBytes(StandardCharsets.UTF_8))));
        TransferHandler.TransferSupport uriSupport = new TransferHandler.TransferSupport(
                target,
                new TextFlavorTransferable(uriList, () -> endpoint));

        assertFalse(handler.canImport(htmlSupport));
        assertFalse(handler.importData(htmlSupport));
        assertFalse(handler.canImport(uriSupport));
        assertFalse(handler.importData(uriSupport));

        route.close();
        assertNull(target.getTransferHandler());
    }

    /// Desktop URI lists import local files while ignoring unrelated web URLs in the same payload.
    @Test
    void importsLocalFileUriLists() throws ClassNotFoundException {
        JPanel target = new JPanel();
        AtomicReference<@Nullable @Unmodifiable List<Path>> imported = new AtomicReference<>();
        ShellFileDropHandler.RouteRegistration route = ShellFileDropHandler.registerFiles(
                target,
                path -> path.getFileName().toString().endsWith(".jar"),
                imported::set);
        TransferHandler handler = Objects.requireNonNull(target.getTransferHandler());
        DataFlavor uriList = new DataFlavor("text/uri-list;class=java.lang.String");
        File first = new File("first.jar");
        File second = new File("second.jar");
        TransferHandler.TransferSupport support = new TransferHandler.TransferSupport(
                target,
                new TextFlavorTransferable(
                        uriList,
                        () -> "# desktop metadata\r\n"
                                + first.toURI().toASCIIString()
                                + "\r\nhttps://example.com/ignored\r\n"
                                + second.toURI().toASCIIString()
                                + "\r\n"));

        assertTrue(handler.canImport(support));
        assertTrue(handler.importData(support));
        assertEquals(
                List.of(
                        first.toPath().toAbsolutePath().normalize(),
                        second.toPath().toAbsolutePath().normalize()),
                imported.get());

        route.close();
        assertNull(target.getTransferHandler());
    }

    /// Native drag-over checks advertised flavors without requesting a deferred Windows OLE payload.
    @Test
    void acceptsDeferredNativeDropFlavorsWithoutReadingPayload() {
        JPanel shell = new JPanel();
        JPanel page = new JPanel();
        shell.add(page);
        AtomicBoolean fileRead = new AtomicBoolean();
        AtomicBoolean textRead = new AtomicBoolean();
        ShellFileDropHandler.RouteRegistration globalText = ShellFileDropHandler.registerText(
                shell,
                text -> text.startsWith("authlib-injector:"),
                ignored -> { });
        ShellFileDropHandler.RouteRegistration pageFiles = ShellFileDropHandler.registerFiles(
                page,
                path -> path.getFileName().toString().endsWith(".jar"),
                ignored -> { });
        ShellFileDropHandler handler = (ShellFileDropHandler) Objects.requireNonNull(
                page.getTransferHandler());

        assertTrue(handler.canImportDropFlavors(
                page,
                new DeferredTransferable(
                        new DataFlavor[]{DataFlavor.javaFileListFlavor},
                        fileRead)));
        assertTrue(handler.canImportDropFlavors(
                page,
                new DeferredTransferable(
                        new DataFlavor[]{DataFlavor.stringFlavor},
                        textRead)));
        assertFalse(fileRead.get());
        assertFalse(textRead.get());

        pageFiles.close();
        globalText.close();
        assertNull(page.getTransferHandler());
        assertNull(shell.getTransferHandler());
    }

    /// Creates a Swing transfer wrapper for one immutable local-file list.
    ///
    /// @param files local files exposed by the payload
    /// @return transfer support bound to a lightweight panel
    private static TransferHandler.TransferSupport support(@Unmodifiable List<File> files) {
        return new TransferHandler.TransferSupport(new JPanel(), new FileListTransferable(files));
    }

    /// Immutable standard Java file-list transferable used by focused tests.
    @NotNullByDefault
    private static final class FileListTransferable implements Transferable {
        /// Immutable local-file payload.
        private final @Unmodifiable List<File> files;

        /// Creates one file-list payload.
        ///
        /// @param files local files to expose
        private FileListTransferable(@Unmodifiable List<File> files) {
            this.files = List.copyOf(files);
        }

        /// Returns the single supported file-list flavor.
        ///
        /// @return defensive flavor array
        @Override
        public DataFlavor @Unmodifiable [] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.javaFileListFlavor};
        }

        /// Reports whether the requested flavor is the standard file-list flavor.
        ///
        /// @param flavor requested flavor
        /// @return whether the flavor is supported
        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.javaFileListFlavor.equals(flavor);
        }

        /// Returns the immutable local-file payload.
        ///
        /// @param flavor requested flavor
        /// @return immutable file list
        /// @throws UnsupportedFlavorException when the requested flavor is unsupported
        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }
            return files;
        }
    }

    /// Immutable string transferable proving non-file flavors are rejected.
    @NotNullByDefault
    private static final class StringTransferable implements Transferable {
        /// String payload.
        private final String value;

        /// Creates one string payload.
        ///
        /// @param value string value
        private StringTransferable(String value) {
            this.value = value;
        }

        /// Returns the single supported string flavor.
        ///
        /// @return defensive flavor array
        @Override
        public DataFlavor @Unmodifiable [] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.stringFlavor};
        }

        /// Reports whether the requested flavor is the string flavor.
        ///
        /// @param flavor requested flavor
        /// @return whether the flavor is supported
        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.stringFlavor.equals(flavor);
        }

        /// Returns the string payload.
        ///
        /// @param flavor requested flavor
        /// @return string value
        /// @throws UnsupportedFlavorException when the requested flavor is unsupported
        /// @throws IOException never thrown for the in-memory payload
        @Override
        public Object getTransferData(DataFlavor flavor)
                throws UnsupportedFlavorException, IOException {
            if (!isDataFlavorSupported(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }
            return value;
        }
    }

    /// Immutable single-flavor browser text transferable.
    @NotNullByDefault
    private static final class TextFlavorTransferable implements Transferable {
        /// Browser-specific text flavor.
        private final DataFlavor flavor;

        /// Factory producing a fresh payload for every transfer request.
        private final Supplier<Object> valueSupplier;

        /// Creates one browser text payload.
        ///
        /// @param flavor exposed text flavor
        /// @param valueSupplier fresh payload factory matching the flavor's representation class
        private TextFlavorTransferable(DataFlavor flavor, Supplier<Object> valueSupplier) {
            this.flavor = Objects.requireNonNull(flavor, "flavor");
            this.valueSupplier = Objects.requireNonNull(valueSupplier, "valueSupplier");
        }

        /// Returns the single browser text flavor.
        ///
        /// @return defensive flavor array
        @Override
        public DataFlavor @Unmodifiable [] getTransferDataFlavors() {
            return new DataFlavor[]{flavor};
        }

        /// Reports whether the requested flavor is the configured browser flavor.
        ///
        /// @param requestedFlavor requested flavor
        /// @return whether the flavor is supported
        @Override
        public boolean isDataFlavorSupported(DataFlavor requestedFlavor) {
            return flavor.equals(Objects.requireNonNull(requestedFlavor, "requestedFlavor"));
        }

        /// Returns the configured browser payload.
        ///
        /// @param requestedFlavor requested flavor
        /// @return configured payload
        /// @throws UnsupportedFlavorException when the requested flavor is unsupported
        @Override
        public Object getTransferData(DataFlavor requestedFlavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(requestedFlavor)) {
                throw new UnsupportedFlavorException(requestedFlavor);
            }
            return Objects.requireNonNull(valueSupplier.get(), "valueSupplier returned null");
        }
    }

    /// Minimal custom child handler delegating only ancestor-owned text routes.
    @NotNullByDefault
    private static final class AncestorTextDelegatingTransferHandler extends TransferHandler {
        /// Reports whether an ancestor text route can accept this payload.
        ///
        /// @param support proposed transfer
        /// @return whether ancestor text routing is available
        @Override
        public boolean canImport(TransferSupport support) {
            return ShellFileDropHandler.canImportAncestorText(Objects.requireNonNull(support, "support"));
        }

        /// Delivers accepted text to the ancestor route.
        ///
        /// @param support accepted transfer
        /// @return whether the ancestor command completed
        @Override
        public boolean importData(TransferSupport support) {
            return ShellFileDropHandler.importAncestorText(Objects.requireNonNull(support, "support"));
        }
    }

    /// Deferred native payload that fails if drag-over attempts to read it before drop acceptance.
    @NotNullByDefault
    private static final class DeferredTransferable implements Transferable {
        /// Native flavors advertised before data access becomes legal.
        private final DataFlavor @Unmodifiable [] flavors;

        /// Records an illegal early data request.
        private final AtomicBoolean readRequested;

        /// Creates one deferred native transfer.
        ///
        /// @param flavors advertised native flavors
        /// @param readRequested early-read recorder
        private DeferredTransferable(
                DataFlavor @Unmodifiable [] flavors,
                AtomicBoolean readRequested) {
            this.flavors = Objects.requireNonNull(flavors, "flavors").clone();
            this.readRequested = Objects.requireNonNull(readRequested, "readRequested");
        }

        /// Returns a defensive copy of the deferred flavors.
        ///
        /// @return defensive flavor array
        @Override
        public DataFlavor @Unmodifiable [] getTransferDataFlavors() {
            return flavors.clone();
        }

        /// Reports whether one flavor is advertised without reading its data.
        ///
        /// @param flavor requested flavor
        /// @return whether the flavor is advertised
        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            for (DataFlavor candidate : flavors) {
                if (candidate.equals(Objects.requireNonNull(flavor, "flavor"))) {
                    return true;
                }
            }
            return false;
        }

        /// Rejects data access until a real drop target has accepted the native operation.
        ///
        /// @param flavor requested flavor
        /// @return never returns
        /// @throws UnsupportedFlavorException when the flavor is not advertised
        /// @throws InvalidDnDOperationException for every advertised early read
        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }
            readRequested.set(true);
            throw new InvalidDnDOperationException("Native payload is deferred until drop acceptance");
        }
    }
}

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
package space.minecraftstl.xyml.ui.swing.page.instances.management.servers;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameRepository;
import space.minecraftstl.xyml.library.nbt.io.NBTFile;
import space.minecraftstl.xyml.library.nbt.tag.CompoundTag;
import space.minecraftstl.xyml.library.nbt.tag.ListTag;
import space.minecraftstl.xyml.library.nbt.tag.Tag;
import space.minecraftstl.xyml.library.nbt.tag.TagType;
import space.minecraftstl.xyml.ui.swing.choice.CatalogIconSupport;
import space.minecraftstl.xyml.ui.swing.choice.RichValueListCellRenderer;
import space.minecraftstl.xyml.util.ServerAddress;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Edits one instance's ordered Minecraft servers.dat list with a rich list and details surface.
@NotNullByDefault
public final class ServerCatalogPanel extends JPanel implements AutoCloseable {
    private static final javax.swing.Icon FALLBACK_ICON = CatalogIconSupport.placeholder(new Color(92, 126, 164, 100));

    private final ServerCatalogModel model;
    private final DefaultListModel<ServerCatalogItem> listModel = new DefaultListModel<>();
    private final JList<ServerCatalogItem> list = new JList<>(listModel);
    private final DefaultTableModel tableModel;
    private final JTable table;
    private final JLabel statusLabel;
    private final JLabel iconLabel = new JLabel();
    private final JTextArea addressValue = detailValue("serverCatalogAddress");
    private final JTextArea descriptionValue = detailValue("serverCatalogDescription");
    private final JButton editButton;
    private final JButton removeButton;
    private final JButton upButton;
    private final JButton downButton;
    private volatile boolean closed;

    /// Creates a server-list panel backed by the supplied instance repository.
    public ServerCatalogPanel(GameRepository repository, GameInstanceID instanceId, Executor executor) {
        this(new FileSystemServerCatalogAccess(repository, instanceId), executor);
    }

    /// Creates a server-list panel with an injectable storage boundary.
    ServerCatalogPanel(ServerCatalogAccess access, Executor executor) {
        super(new MigLayout("insets 0, fill", "[grow,fill]", "[][grow,fill]8[]"));
        model = new ServerCatalogModel(access, executor);
        tableModel = new DefaultTableModel(new Object[] {i18n("server.name"), i18n("server.address")}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        table = new JTable(tableModel);
        table.setName("serverCatalogTable");

        JPanel heading = new JPanel(new MigLayout("insets 0, fillx", "[grow,fill]", "[40!]"));
        heading.setOpaque(false);
        JLabel title = new JLabel(i18n("server.manage"));
        title.setName("serverCatalogTitle");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 28.0F));
        heading.add(title, "growx");
        add(heading, "growx");

        list.setName("serverCatalogList");
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setOpaque(false);
        list.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                showSelectedDetails();
                updateButtonState();
            }
        });
        list.setCellRenderer(new RichValueListCellRenderer<>(
                ServerCatalogItem::name,
                item -> item.description().isBlank() ? item.address() : item.description(),
                item -> "",
                item -> CatalogIconSupport.decode(item.icon(), FALLBACK_ICON),
                item -> item.name() + " — " + item.address()));
        JScrollPane listScroll = new JScrollPane(list);
        listScroll.setName("serverCatalogListScroll");
        listScroll.setBorder(BorderFactory.createEmptyBorder());
        listScroll.setMinimumSize(new Dimension(0, 0));

        JPanel details = new JPanel(new MigLayout("insets 12, fillx, wrap 1", "[grow,fill]", "[]8[]6[][]6[][][grow]"));
        details.setName("serverCatalogDetails");
        details.setOpaque(false);
        iconLabel.setName("serverCatalogIcon");
        iconLabel.setPreferredSize(new Dimension(CatalogIconSupport.ICON_SIZE, CatalogIconSupport.ICON_SIZE));
        details.add(iconLabel, "w 40!, h 40!");
        JLabel detailsTitle = new JLabel(i18n("server.details"));
        detailsTitle.setFont(detailsTitle.getFont().deriveFont(Font.BOLD));
        details.add(detailsTitle, "growx");
        details.add(new JLabel(i18n("server.address")), "growx");
        details.add(addressValue, "growx, wmin 0");
        details.add(new JLabel(i18n("server.description")), "growx");
        details.add(descriptionValue, "growx, wmin 0");

        JButton addButton = new JButton(i18n("server.add"));
        addButton.setName("serverCatalogAdd");
        addButton.addActionListener(event -> addServer());
        editButton = new JButton(i18n("server.edit"));
        editButton.setName("serverCatalogEdit");
        editButton.addActionListener(event -> editSelected());
        removeButton = new JButton(i18n("server.remove"));
        removeButton.setName("serverCatalogRemove");
        removeButton.addActionListener(event -> removeSelected());
        upButton = new JButton(i18n("server.move_up"));
        upButton.setName("serverCatalogMoveUp");
        upButton.addActionListener(event -> moveSelected(-1));
        downButton = new JButton(i18n("server.move_down"));
        downButton.setName("serverCatalogMoveDown");
        downButton.addActionListener(event -> moveSelected(1));
        JPanel commands = new JPanel(new MigLayout("insets 0, fillx, wrap 2", "[grow,fill][grow,fill]", "[40!]"));
        commands.setOpaque(false);
        commands.add(addButton, "growx");
        commands.add(editButton, "growx");
        commands.add(removeButton, "growx");
        commands.add(upButton, "growx");
        commands.add(downButton, "growx");
        details.add(commands, "growx");

        JPanel center = new JPanel(new MigLayout("insets 0, fill", "[grow,fill]12[280!,fill]", "[grow,fill]"));
        center.setOpaque(false);
        center.add(listScroll, "grow, wmin 0, hmin 0");
        center.add(details, "grow, wmin 0, hmin 0");
        add(center, "grow, wmin 0, hmin 0");

        statusLabel = new JLabel(i18n("server.loading"));
        statusLabel.setName("serverCatalogStatus");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
        add(statusLabel, "growx");
        showNoSelection();
        updateButtonState();
        model.load().thenAccept(this::publishSnapshot);
    }

    /// Publishes one model snapshot on the Swing event thread.
    private void publishSnapshot(ServerCatalogSnapshot snapshot) {
        if (closed) {
            return;
        }
        Runnable update = () -> {
            if (closed) {
                return;
            }
            int previousIndex = list.getSelectedIndex();
            listModel.clear();
            tableModel.setRowCount(0);
            for (ServerCatalogItem server : snapshot.servers()) {
                listModel.addElement(server);
                tableModel.addRow(new Object[] {server.name(), server.address()});
            }
            if (previousIndex >= 0 && previousIndex < listModel.size()) {
                list.setSelectedIndex(previousIndex);
            } else if (!listModel.isEmpty()) {
                list.setSelectedIndex(0);
            }
            if (snapshot.status() == ServerCatalogStatus.READY) {
                statusLabel.setText(snapshot.servers().isEmpty() ? i18n("server.empty") : " ");
            } else if (snapshot.status() == ServerCatalogStatus.FAILURE) {
                statusLabel.setText(i18n("server.failure", snapshot.message()));
            }
            showSelectedDetails();
            updateButtonState();
        };
        if (SwingUtilities.isEventDispatchThread()) {
            update.run();
        } else {
            SwingUtilities.invokeLater(update);
        }
    }

    private void addServer() {
        showEditor(null).ifPresent(values -> model.add(new ServerCatalogItem(values.name(), values.address()))
                .thenAccept(this::publishSnapshot).exceptionally(this::showFailure));
    }

    private void editSelected() {
        int index = list.getSelectedIndex();
        if (index < 0 || index >= listModel.size()) {
            return;
        }
        ServerCatalogItem selected = listModel.get(index);
        showEditor(selected).ifPresent(values -> model.edit(index, values.name(), values.address())
                .thenAccept(this::publishSnapshot).exceptionally(this::showFailure));
    }

    private void removeSelected() {
        int index = list.getSelectedIndex();
        if (index < 0) {
            return;
        }
        int result = JOptionPane.showConfirmDialog(this, i18n("server.remove.confirm"), i18n("server.remove"),
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (result == JOptionPane.YES_OPTION) {
            model.remove(index).thenAccept(this::publishSnapshot).exceptionally(this::showFailure);
        }
    }

    private void moveSelected(int delta) {
        int index = list.getSelectedIndex();
        if (index < 0) {
            return;
        }
        model.move(index, index + delta).thenAccept(snapshot -> {
            publishSnapshot(snapshot);
            SwingUtilities.invokeLater(() -> {
                if (!closed) {
                    int target = index + delta;
                    if (target >= 0 && target < listModel.getSize()) {
                        list.setSelectedIndex(target);
                    }
                }
            });
        }).exceptionally(this::showFailure);
    }

    private java.util.Optional<EditorValues> showEditor(ServerCatalogItem selected) {
        JTextField name = new JTextField(selected == null ? "" : selected.name());
        JTextField address = new JTextField(selected == null ? "" : selected.address());
        JPanel fields = new JPanel(new GridLayout(2, 2, 8, 8));
        fields.add(new JLabel(i18n("server.name")));
        fields.add(name);
        fields.add(new JLabel(i18n("server.address")));
        fields.add(address);
        int result = JOptionPane.showConfirmDialog(this, fields,
                i18n(selected == null ? "server.add" : "server.edit"), JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return java.util.Optional.empty();
        }
        String selectedName = name.getText().trim();
        String selectedAddress = address.getText().trim();
        if (selectedName.isEmpty() || selectedAddress.isEmpty()) {
            showInvalid();
            return java.util.Optional.empty();
        }
        try {
            ServerAddress.parse(selectedAddress);
        } catch (IllegalArgumentException exception) {
            showInvalid();
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new EditorValues(selectedName, selectedAddress));
    }

    private void showInvalid() {
        JOptionPane.showMessageDialog(this, i18n("server.invalid"), i18n("server.edit"), JOptionPane.ERROR_MESSAGE);
    }

    private void showSelectedDetails() {
        int index = list.getSelectedIndex();
        if (index < 0 || index >= listModel.size()) {
            showNoSelection();
            return;
        }
        ServerCatalogItem selected = listModel.get(index);
        addressValue.setText(selected.address());
        descriptionValue.setText(selected.description().isBlank() ? i18n("server.description.unavailable") : selected.description());
        iconLabel.setIcon(CatalogIconSupport.decode(selected.icon(), FALLBACK_ICON));
    }

    private void showNoSelection() {
        addressValue.setText("");
        descriptionValue.setText(i18n("server.no_selection"));
        iconLabel.setIcon(FALLBACK_ICON);
    }

    private void updateButtonState() {
        int index = list.getSelectedIndex();
        int size = listModel.getSize();
        boolean selected = index >= 0 && index < size;
        editButton.setEnabled(selected);
        removeButton.setEnabled(selected);
        upButton.setEnabled(selected && index > 0);
        downButton.setEnabled(selected && index + 1 < size);
    }

    private Void showFailure(Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        SwingUtilities.invokeLater(() -> {
            if (closed) {
                return;
            }
            String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            statusLabel.setText(i18n("server.failure", message));
            JOptionPane.showMessageDialog(this, statusLabel.getText(), i18n("server.edit"), JOptionPane.ERROR_MESSAGE);
        });
        return null;
    }

    /// Returns the compatibility table used by existing model/UI tests.
    JTable table() {
        return table;
    }

    private static JTextArea detailValue(String name) {
        JTextArea value = new JTextArea();
        value.setName(name);
        value.setEditable(false);
        value.setLineWrap(true);
        value.setWrapStyleWord(true);
        value.setOpaque(false);
        value.setBorder(BorderFactory.createEmptyBorder());
        return value;
    }

    @Override
    public void close() {
        closed = true;
        model.close();
    }

    private record EditorValues(String name, String address) {
    }

    /// Internal compare-and-write extension used by revision-aware storage implementations.
    @NotNullByDefault
    interface RevisionAwareServerCatalogAccess extends ServerCatalogAccess {
        /// Publishes one complete list only when the source still matches the preceding read.
        ///
        /// @param expectedServers entries observed before applying the mutation
        /// @param servers ordered entries to publish
        /// @throws IOException if the source changed, or publication fails
        void writeIfUnchanged(List<ServerCatalogItem> expectedServers, List<ServerCatalogItem> servers)
                throws IOException;
    }

    /// Serial asynchronous state owner for one server catalog.
    @NotNullByDefault
    static final class ServerCatalogModel implements AutoCloseable {
        private final ServerCatalogAccess access;
        private final Executor executor;
        private final Object lock = new Object();
        private ServerCatalogSnapshot snapshot = new ServerCatalogSnapshot(ServerCatalogStatus.LOADING, List.of(), "");
        private CompletableFuture<Void> operationTail = CompletableFuture.completedFuture(null);
        private boolean loaded;
        private boolean closed;

        /// Creates one serialized model over the supplied storage boundary.
        ServerCatalogModel(ServerCatalogAccess access, Executor executor) {
            this.access = Objects.requireNonNull(access, "access");
            this.executor = Objects.requireNonNull(executor, "executor");
        }

        /// Returns the latest immutable snapshot.
        ServerCatalogSnapshot snapshot() {
            synchronized (lock) {
                return snapshot;
            }
        }

        /// Queues a storage load behind all earlier operations.
        CompletableFuture<ServerCatalogSnapshot> load() {
            return enqueue(this::loadNow);
        }

        /// Appends one server after all earlier operations complete.
        CompletableFuture<ServerCatalogSnapshot> add(ServerCatalogItem server) {
            return mutate(current -> {
                List<ServerCatalogItem> result = new ArrayList<>(current);
                result.add(Objects.requireNonNull(server, "server"));
                return result;
            });
        }

        /// Replaces one indexed server after all earlier operations complete.
        CompletableFuture<ServerCatalogSnapshot> edit(int index, String name, String address) {
            return mutate(current -> {
                List<ServerCatalogItem> result = new ArrayList<>(current);
                result.set(index, result.get(index).withValues(name, address));
                return result;
            });
        }

        /// Removes one indexed server after all earlier operations complete.
        CompletableFuture<ServerCatalogSnapshot> remove(int index) {
            return mutate(current -> {
                List<ServerCatalogItem> result = new ArrayList<>(current);
                result.remove(index);
                return result;
            });
        }

        /// Moves one indexed server after all earlier operations complete.
        CompletableFuture<ServerCatalogSnapshot> move(int from, int to) {
            return mutate(current -> {
                List<ServerCatalogItem> result = new ArrayList<>(current);
                if (from < 0 || from >= result.size() || to < 0 || to >= result.size()) {
                    throw new IndexOutOfBoundsException("server reorder index out of range");
                }
                ServerCatalogItem item = result.remove(from);
                result.add(to, item);
                return result;
            });
        }

        private CompletableFuture<ServerCatalogSnapshot> mutate(UnaryOperator<List<ServerCatalogItem>> operation) {
            UnaryOperator<List<ServerCatalogItem>> checked = Objects.requireNonNull(operation, "operation");
            return enqueue(() -> mutateNow(checked));
        }

        /// Queues one operation on the caller-owned executor without allowing overlap.
        private CompletableFuture<ServerCatalogSnapshot> enqueue(Supplier<ServerCatalogSnapshot> operation) {
            synchronized (lock) {
                if (closed) {
                    return failedFuture(new IllegalStateException("Server catalog is closed"));
                }
                CompletableFuture<ServerCatalogSnapshot> result = operationTail
                        .handle((ignored, failure) -> null)
                        .thenApplyAsync(ignored -> operation.get(), executor);
                operationTail = result.handle((ignored, failure) -> null);
                return result;
            }
        }

        /// Performs one storage load on the serialized operation channel.
        private ServerCatalogSnapshot loadNow() {
            try {
                List<ServerCatalogItem> servers = access.read();
                synchronized (lock) {
                    if (closed) {
                        return snapshot;
                    }
                    loaded = true;
                    snapshot = ready(servers);
                    return snapshot;
                }
            } catch (IOException | RuntimeException failure) {
                synchronized (lock) {
                    if (closed) {
                        return snapshot;
                    }
                    loaded = false;
                    snapshot = failed(snapshot.servers(), failure);
                    return snapshot;
                }
            }
        }

        /// Performs one read-modify-write transaction on the serialized operation channel.
        private ServerCatalogSnapshot mutateNow(UnaryOperator<List<ServerCatalogItem>> operation) {
            try {
                List<ServerCatalogItem> current;
                synchronized (lock) {
                    if (closed) {
                        return snapshot;
                    }
                    current = loaded ? snapshot.servers() : null;
                }
                if (current == null) {
                    current = access.read();
                }
                List<ServerCatalogItem> next = List.copyOf(operation.apply(current));
                if (access instanceof RevisionAwareServerCatalogAccess revisionAwareAccess) {
                    revisionAwareAccess.writeIfUnchanged(current, next);
                } else {
                    access.write(next);
                }
                synchronized (lock) {
                    if (closed) {
                        return snapshot;
                    }
                    loaded = true;
                    snapshot = ready(next);
                    return snapshot;
                }
            } catch (IOException | RuntimeException failure) {
                return recoverAfterMutationFailure(failure);
            }
        }

        /// Reconciles the model with storage after a failed mutation without discarding the last known list.
        private ServerCatalogSnapshot recoverAfterMutationFailure(Throwable failure) {
            @Nullable List<ServerCatalogItem> refreshed = null;
            try {
                refreshed = access.read();
            } catch (IOException | RuntimeException refreshFailure) {
                failure.addSuppressed(refreshFailure);
            }
            synchronized (lock) {
                if (closed) {
                    return snapshot;
                }
                loaded = refreshed != null;
                List<ServerCatalogItem> retained = refreshed == null ? snapshot.servers() : refreshed;
                snapshot = failed(retained, failure);
                return snapshot;
            }
        }

        private static ServerCatalogSnapshot ready(List<ServerCatalogItem> servers) {
            return new ServerCatalogSnapshot(ServerCatalogStatus.READY, servers, "");
        }

        private static ServerCatalogSnapshot failed(List<ServerCatalogItem> servers, Throwable failure) {
            String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            return new ServerCatalogSnapshot(ServerCatalogStatus.FAILURE, servers, message);
        }

        /// Creates a future failed before scheduling.
        private static <T> CompletableFuture<T> failedFuture(Throwable failure) {
            CompletableFuture<T> result = new CompletableFuture<>();
            result.completeExceptionally(failure);
            return result;
        }

        @Override
        public void close() {
            synchronized (lock) {
                closed = true;
            }
        }
    }

    private static final class FileSystemServerCatalogAccess implements RevisionAwareServerCatalogAccess {
        private final Path file;
        private @Nullable FileRevision expectedRevision;

        private FileSystemServerCatalogAccess(GameRepository repository, GameInstanceID instanceId) {
            file = Objects.requireNonNull(repository, "repository").getRunDirectory(
                    Objects.requireNonNull(instanceId, "instanceId")).resolve("servers.dat");
        }

        @Override
        public synchronized List<ServerCatalogItem> read() throws IOException {
            FileRevision before = revision(file);
            if (!before.exists()) {
                expectedRevision = before;
                return List.of();
            }
            try (NBTFile<CompoundTag> nbt = NBTFile.openTag(file, TagType.COMPOUND)) {
                CompoundTag root = nbt.getEditor().getRootSnapshot();
                Tag rawServers = root.get("servers");
                if (rawServers == null) {
                    return List.of();
                }
                if (!(rawServers instanceof ListTag<?> servers)) {
                    throw new IOException("servers.dat contains a non-list servers tag");
                }
                List<ServerCatalogItem> result = new ArrayList<>();
                for (Tag rawServer : servers) {
                    if (!(rawServer instanceof CompoundTag server)) {
                        throw new IOException("servers.dat contains a non-compound server entry");
                    }
                    String name = server.getStringOrEmpty("name");
                    String address = server.getStringOrEmpty("ip");
                    if (name.isBlank() || address.isBlank()) {
                        throw new IOException("servers.dat contains a blank server field");
                    }
                    result.add(new ServerCatalogItem(name, address, server));
                }
                FileRevision after = revision(file);
                if (!before.equals(after)) {
                    throw new IOException("servers.dat changed while it was being read");
                }
                expectedRevision = after;
                return List.copyOf(result);
            }
        }

        @Override
        public synchronized void write(List<ServerCatalogItem> servers) throws IOException {
            if (expectedRevision == null) {
                read();
            }
            writeIfUnchanged(List.of(), servers);
        }

        @Override
        public synchronized void writeIfUnchanged(
                List<ServerCatalogItem> expectedServers,
                List<ServerCatalogItem> servers) throws IOException {
            FileRevision expected = Objects.requireNonNull(expectedRevision, "expectedRevision");
            requireRevision(expected);
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (NBTFile<CompoundTag> nbt = Files.isRegularFile(file)
                    ? NBTFile.openTag(file, TagType.COMPOUND)
                    : NBTFile.createTag(file)) {
                CompoundTag root = nbt.getEditor().getRootSnapshot();
                ListTag<CompoundTag> rawServers = new ListTag<>(TagType.COMPOUND).setName("servers");
                for (ServerCatalogItem server : servers) {
                    rawServers.addTag(server.toTag());
                }
                if (root.get("servers") == null) {
                    root.addTag(rawServers);
                } else {
                    root.replaceTag("servers", rawServers);
                }
                try {
                    nbt.getEditor().replaceContent(nbt.getEditor().rootNode(), root);
                } catch (space.minecraftstl.xyml.library.nbt.edit.NBTEditException failure) {
                    throw new IOException("Failed to update servers.dat", failure);
                }
                requireRevision(expected);
                nbt.save();
            }
            expectedRevision = revision(file);
        }

        /// Rejects publication when the source no longer matches the revision loaded by this access object.
        private void requireRevision(FileRevision expected) throws IOException {
            if (!expected.equals(revision(file))) {
                throw new IOException("servers.dat changed outside XYML; reload before editing");
            }
        }

        /// Captures a content-sensitive revision without following symbolic links.
        private static FileRevision revision(Path file) throws IOException {
            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException ignored) {
                return FileRevision.absent();
            }
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
                throw new IOException("servers.dat is not a regular file");
            }
            MessageDigest digest = sha256();
            try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (count > 0) {
                        digest.update(buffer, 0, count);
                    }
                }
            }
            return new FileRevision(true, attributes.size(), HexFormat.of().formatHex(digest.digest()));
        }

        /// Returns the required SHA-256 digest implementation.
        private static MessageDigest sha256() {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException failure) {
                throw new IllegalStateException("SHA-256 is unavailable", failure);
            }
        }

        /// Immutable content revision used to reject stale full-file writes.
        @NotNullByDefault
        private record FileRevision(boolean exists, long size, String digest) {
            /// Returns the stable revision for an absent source.
            private static FileRevision absent() {
                return new FileRevision(false, 0L, "");
            }
        }
    }
}

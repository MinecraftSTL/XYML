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

import org.jetbrains.annotations.NotNullByDefault;
import space.minecraftstl.xyml.game.GameInstanceID;
import space.minecraftstl.xyml.game.GameRepository;
import space.minecraftstl.xyml.library.nbt.io.NBTFile;
import space.minecraftstl.xyml.library.nbt.tag.CompoundTag;
import space.minecraftstl.xyml.library.nbt.tag.ListTag;
import space.minecraftstl.xyml.library.nbt.tag.Tag;
import space.minecraftstl.xyml.library.nbt.tag.TagType;
import space.minecraftstl.xyml.util.ServerAddress;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.UnaryOperator;

import static space.minecraftstl.xyml.util.i18n.I18n.i18n;

/// Edits one instance's ordered Minecraft `servers.dat` list.
@NotNullByDefault
public final class ServerCatalogPanel extends JPanel implements AutoCloseable {
    private final ServerCatalogModel model;
    private final DefaultTableModel tableModel;
    private final JTable table;
    private final JLabel statusLabel;
    private final JButton editButton;
    private final JButton removeButton;
    private final JButton upButton;
    private final JButton downButton;

    /// Creates a server-list panel backed by the supplied instance repository.
    public ServerCatalogPanel(GameRepository repository, GameInstanceID instanceId, Executor executor) {
        this(new FileSystemServerCatalogAccess(repository, instanceId), executor);
    }

    /// Creates a server-list panel with an injectable storage boundary.
    ServerCatalogPanel(ServerCatalogAccess access, Executor executor) {
        super(new BorderLayout(0, 8));
        model = new ServerCatalogModel(access, executor);
        tableModel = new DefaultTableModel(new Object[] {i18n("server.name"), i18n("server.address")}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        table = new JTable(tableModel);
        table.setName("serverCatalogTable");
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.getSelectionModel().addListSelectionListener(event -> updateButtonState());
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent event) {
                if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) {
                    editSelected();
                }
            }
        });
        add(new JScrollPane(table), BorderLayout.CENTER);
        statusLabel = new JLabel(i18n("server.loading"));
        statusLabel.setName("serverCatalogStatus");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
        add(statusLabel, BorderLayout.NORTH);
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
        JPanel commands = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
        commands.add(addButton);
        commands.add(editButton);
        commands.add(removeButton);
        commands.add(upButton);
        commands.add(downButton);
        add(commands, BorderLayout.SOUTH);
        updateButtonState();
        model.load().thenAccept(this::publishSnapshot);
    }

    private void publishSnapshot(ServerCatalogSnapshot snapshot) {
        Runnable update = () -> {
            if (snapshot.status() == ServerCatalogStatus.READY) {
                tableModel.setRowCount(0);
                for (ServerCatalogItem server : snapshot.servers()) {
                    tableModel.addRow(new Object[] {server.name(), server.address()});
                }
                statusLabel.setText(snapshot.servers().isEmpty() ? i18n("server.empty") : " ");
            } else if (snapshot.status() == ServerCatalogStatus.FAILURE) {
                statusLabel.setText(i18n("server.failure", snapshot.message()));
            }
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
        int index = table.getSelectedRow();
        if (index < 0) {
            return;
        }
        ServerCatalogItem selected = model.snapshot().servers().get(index);
        showEditor(selected).ifPresent(values -> model.edit(index, values.name(), values.address())
                .thenAccept(this::publishSnapshot).exceptionally(this::showFailure));
    }

    private void removeSelected() {
        int index = table.getSelectedRow();
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
        int index = table.getSelectedRow();
        if (index < 0) {
            return;
        }
        model.move(index, index + delta).thenAccept(snapshot -> {
            publishSnapshot(snapshot);
            SwingUtilities.invokeLater(() -> {
                int target = index + delta;
                if (target >= 0 && target < tableModel.getRowCount()) {
                    table.setRowSelectionInterval(target, target);
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

    private void updateButtonState() {
        int index = table.getSelectedRow();
        int size = tableModel.getRowCount();
        boolean selected = index >= 0 && index < size;
        editButton.setEnabled(selected);
        removeButton.setEnabled(selected);
        upButton.setEnabled(selected && index > 0);
        downButton.setEnabled(selected && index + 1 < size);
    }

    private Void showFailure(Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        SwingUtilities.invokeLater(() -> {
            String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            statusLabel.setText(i18n("server.failure", message));
            JOptionPane.showMessageDialog(this, statusLabel.getText(), i18n("server.edit"), JOptionPane.ERROR_MESSAGE);
        });
        return null;
    }

    /// Returns the table used to render the current server order.
    JTable table() {
        return table;
    }

    @Override
    public void close() {
        model.close();
    }

    private record EditorValues(String name, String address) {
    }

    private static final class ServerCatalogModel implements AutoCloseable {
        private final ServerCatalogAccess access;
        private final Executor executor;
        private final Object lock = new Object();
        private ServerCatalogSnapshot snapshot = new ServerCatalogSnapshot(ServerCatalogStatus.LOADING, List.of(), "");
        private boolean loaded;

        private ServerCatalogModel(ServerCatalogAccess access, Executor executor) {
            this.access = Objects.requireNonNull(access, "access");
            this.executor = Objects.requireNonNull(executor, "executor");
        }

        private ServerCatalogSnapshot snapshot() {
            synchronized (lock) {
                return snapshot;
            }
        }

        private CompletableFuture<ServerCatalogSnapshot> load() {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    List<ServerCatalogItem> servers = access.read();
                    synchronized (lock) {
                        loaded = true;
                        snapshot = ready(servers);
                        return snapshot;
                    }
                } catch (IOException | RuntimeException failure) {
                    synchronized (lock) {
                        snapshot = failed(failure);
                        return snapshot;
                    }
                }
            }, executor);
        }

        private CompletableFuture<ServerCatalogSnapshot> add(ServerCatalogItem server) {
            return mutate(current -> {
                List<ServerCatalogItem> result = new ArrayList<>(current);
                result.add(Objects.requireNonNull(server, "server"));
                return result;
            });
        }

        private CompletableFuture<ServerCatalogSnapshot> edit(int index, String name, String address) {
            return mutate(current -> {
                List<ServerCatalogItem> result = new ArrayList<>(current);
                result.set(index, result.get(index).withValues(name, address));
                return result;
            });
        }

        private CompletableFuture<ServerCatalogSnapshot> remove(int index) {
            return mutate(current -> {
                List<ServerCatalogItem> result = new ArrayList<>(current);
                result.remove(index);
                return result;
            });
        }

        private CompletableFuture<ServerCatalogSnapshot> move(int from, int to) {
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
            return CompletableFuture.supplyAsync(() -> {
                try {
                    List<ServerCatalogItem> current;
                    synchronized (lock) {
                        current = loaded ? snapshot.servers() : access.read();
                    }
                    List<ServerCatalogItem> next = List.copyOf(operation.apply(current));
                    access.write(next);
                    synchronized (lock) {
                        loaded = true;
                        snapshot = ready(next);
                        return snapshot;
                    }
                } catch (IOException | RuntimeException failure) {
                    synchronized (lock) {
                        snapshot = failed(failure);
                        return snapshot;
                    }
                }
            }, executor);
        }

        private static ServerCatalogSnapshot ready(List<ServerCatalogItem> servers) {
            return new ServerCatalogSnapshot(ServerCatalogStatus.READY, servers, "");
        }

        private static ServerCatalogSnapshot failed(Throwable failure) {
            String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            return new ServerCatalogSnapshot(ServerCatalogStatus.FAILURE, List.of(), message);
        }

        @Override
        public void close() {
        }
    }

    private static final class FileSystemServerCatalogAccess implements ServerCatalogAccess {
        private final Path file;

        private FileSystemServerCatalogAccess(GameRepository repository, GameInstanceID instanceId) {
            file = Objects.requireNonNull(repository, "repository").getRunDirectory(
                    Objects.requireNonNull(instanceId, "instanceId")).resolve("servers.dat");
        }

        @Override
        public List<ServerCatalogItem> read() throws IOException {
            if (!Files.isRegularFile(file)) {
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
                    if (!name.isBlank() && !address.isBlank()) {
                        result.add(new ServerCatalogItem(name, address, server));
                    }
                }
                return List.copyOf(result);
            }
        }

        @Override
        public void write(List<ServerCatalogItem> servers) throws IOException {
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
                nbt.save();
            }
        }
    }
}

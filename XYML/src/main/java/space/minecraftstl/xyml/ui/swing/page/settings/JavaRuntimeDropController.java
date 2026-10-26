// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL

package space.minecraftstl.xyml.ui.swing.page.settings;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.ui.swing.shell.ShellFileDropHandler;

import javax.swing.JComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

/// Routes Java-management file drops without owning runtime or acquisition operations.
@NotNullByDefault
final class JavaRuntimeDropController implements AutoCloseable {
    /// Current page-state gate supplied by the owning management panel.
    private final BooleanSupplier inputAvailable;

    /// Pure lexical predicate for locally supported runtime archives.
    private final Predicate<Path> archivePath;

    /// Existing local-runtime registration command.
    private final Consumer<Path> runtimeCommand;

    /// Existing managed-archive acquisition command.
    private final Consumer<Path> archiveCommand;

    /// Independently removable route installed on the Java management page.
    private final ShellFileDropHandler.RouteRegistration registration;

    /// Installs one page-scoped Java path router.
    ///
    /// @param target Java management page
    /// @param inputAvailable current writable and idle state
    /// @param archivePath supported runtime-archive predicate
    /// @param runtimeCommand local Java home or executable command
    /// @param archiveCommand managed runtime-archive command
    /// @return installed route controller
    static JavaRuntimeDropController install(
            JComponent target,
            BooleanSupplier inputAvailable,
            Predicate<Path> archivePath,
            Consumer<Path> runtimeCommand,
            Consumer<Path> archiveCommand) {
        return new JavaRuntimeDropController(
                target,
                inputAvailable,
                archivePath,
                runtimeCommand,
                archiveCommand);
    }

    /// Creates and installs one route on the supplied page.
    ///
    /// @param target Java management page
    /// @param inputAvailable current writable and idle state
    /// @param archivePath supported runtime-archive predicate
    /// @param runtimeCommand local Java home or executable command
    /// @param archiveCommand managed runtime-archive command
    private JavaRuntimeDropController(
            JComponent target,
            BooleanSupplier inputAvailable,
            Predicate<Path> archivePath,
            Consumer<Path> runtimeCommand,
            Consumer<Path> archiveCommand) {
        this.inputAvailable = Objects.requireNonNull(inputAvailable, "inputAvailable");
        this.archivePath = Objects.requireNonNull(archivePath, "archivePath");
        this.runtimeCommand = Objects.requireNonNull(runtimeCommand, "runtimeCommand");
        this.archiveCommand = Objects.requireNonNull(archiveCommand, "archiveCommand");
        registration = ShellFileDropHandler.register(
                Objects.requireNonNull(target, "target"),
                this::supports,
                this::open);
    }

    /// Removes this controller's route without disturbing sibling handlers.
    @Override
    public void close() {
        registration.close();
    }

    /// Returns whether one dropped path is accepted by the current Java page state.
    ///
    /// @param source normalized dropped path
    /// @return whether the path is a runtime archive, Java home, or Java executable
    private boolean supports(Path source) {
        if (!inputAvailable.getAsBoolean()) {
            return false;
        }
        Path candidate = Objects.requireNonNull(source, "source");
        @Nullable Path fileName = candidate.getFileName();
        return archivePath.test(candidate)
                || Files.isDirectory(candidate)
                || fileName != null && isJavaExecutableName(fileName.toString());
    }

    /// Delegates one accepted path to its existing page command.
    ///
    /// @param source normalized supported path
    private void open(Path source) {
        Path candidate = Objects.requireNonNull(source, "source");
        if (!supports(candidate)) {
            return;
        }
        if (archivePath.test(candidate)) {
            archiveCommand.accept(candidate);
        } else {
            runtimeCommand.accept(candidate);
        }
    }

    /// Returns whether one filename is a standard Java launcher executable.
    ///
    /// @param filename filename without directory context
    /// @return whether the filename is `java` or `java.exe`, ignoring case
    private static boolean isJavaExecutableName(String filename) {
        String normalized = Objects.requireNonNull(filename, "filename").toLowerCase(Locale.ROOT);
        return "java".equals(normalized) || "java.exe".equals(normalized);
    }
}

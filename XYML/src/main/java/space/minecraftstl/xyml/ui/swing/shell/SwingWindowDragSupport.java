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

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Frame;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Adds Java-side window movement to inert surfaces of an undecorated Swing shell.
@NotNullByDefault
final class SwingWindowDragSupport implements AutoCloseable {
    /// Native window moved by the supported drag surfaces.
    private final Window window;

    /// Inert title-bar surfaces that may begin a window drag.
    private final @Unmodifiable List<Component> dragSurfaces;

    /// Shared listener installed on every drag surface.
    private final MouseAdapter dragHandler = new DragHandler();

    /// Pointer offset inside the window while a drag is active, or `null` while idle.
    private @Nullable Point dragOffset;

    /// Whether listeners have been detached from all drag surfaces.
    private boolean closed;

    /// Installs drag handling for one undecorated window.
    ///
    /// @param window native window to move
    /// @param dragSurfaces inert title-bar components that may start a drag
    SwingWindowDragSupport(Window window, List<? extends Component> dragSurfaces) {
        EdtDispatcher.requireEventDispatchThread();
        this.window = Objects.requireNonNull(window, "window");
        List<Component> validatedSurfaces = new ArrayList<>();
        for (Component surface : Objects.requireNonNull(dragSurfaces, "dragSurfaces")) {
            validatedSurfaces.add(Objects.requireNonNull(surface, "drag surface"));
        }
        if (validatedSurfaces.isEmpty()) {
            throw new IllegalArgumentException("dragSurfaces must not be empty");
        }
        this.dragSurfaces = List.copyOf(validatedSurfaces);
        for (Component surface : this.dragSurfaces) {
            surface.addMouseListener(dragHandler);
            surface.addMouseMotionListener(dragHandler);
        }
    }

    /// Returns whether a drag gesture currently owns the pointer offset.
    ///
    /// @return whether a drag is active
    boolean dragging() {
        return dragOffset != null;
    }

    /// Detaches all drag listeners on the EDT.
    @Override
    public void close() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            return;
        }
        closed = true;
        dragOffset = null;
        for (Component surface : dragSurfaces) {
            surface.removeMouseListener(dragHandler);
            surface.removeMouseMotionListener(dragHandler);
        }
    }

    /// Converts a press on one inert surface into window movement while the primary button remains down.
    @NotNullByDefault
    private final class DragHandler extends MouseAdapter {
        /// Captures the pointer offset when a primary-button drag begins.
        ///
        /// @param event primary-button press event
        @Override
        public void mousePressed(MouseEvent event) {
            if (closed || !SwingUtilities.isLeftMouseButton(event)) {
                return;
            }
            dragOffset = SwingUtilities.convertPoint(event.getComponent(), event.getPoint(), window);
        }

        /// Moves the native window in response to the active primary-button drag.
        ///
        /// @param event primary-button drag event
        @Override
        public void mouseDragged(MouseEvent event) {
            @Nullable Point offset = dragOffset;
            if (closed || offset == null || !SwingUtilities.isLeftMouseButton(event)) {
                return;
            }
            restoreMaximizedWindow(offset);
            int newX = event.getXOnScreen() - offset.x;
            int newY = event.getYOnScreen() - offset.y;
            if (newX != window.getX() || newY != window.getY()) {
                window.setLocation(newX, newY);
            }
        }

        /// Clears the active offset after the primary button is released.
        ///
        /// @param event primary-button release event
        @Override
        public void mouseReleased(MouseEvent event) {
            if (SwingUtilities.isLeftMouseButton(event)) {
                dragOffset = null;
            }
        }

        /// Restores a maximized frame before moving it, preserving the pointer position under the title bar.
        ///
        /// @param offset mutable pointer offset captured at press time
        private void restoreMaximizedWindow(Point offset) {
            if (!(window instanceof Frame frame)) {
                return;
            }
            int state = frame.getExtendedState();
            if ((state & Frame.MAXIMIZED_BOTH) == 0) {
                return;
            }

            int maximizedWidth = window.getWidth();
            frame.setExtendedState(state & ~Frame.MAXIMIZED_BOTH);
            int restoredWidth = window.getWidth();
            int center = restoredWidth / 2;
            if (offset.x > center) {
                if (offset.x > maximizedWidth - center) {
                    offset.x = restoredWidth - (maximizedWidth - offset.x);
                } else {
                    offset.x = center;
                }
            }
        }
    }
}

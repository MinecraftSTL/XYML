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
import javax.swing.plaf.UIResource;
import java.awt.Component;
import java.awt.Container;
import java.awt.dnd.DropTarget;
import java.awt.event.ContainerEvent;
import java.awt.event.ContainerListener;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/// Lets page and shell drop routes receive native drops made over ordinary Swing child controls.
///
/// Swing installs Look-and-Feel-owned drop targets on text fields, lists, trees, and tables. Native
/// drag-and-drop selects those deepest targets and does not retry an ancestor after their generic
/// transfer handlers reject a launcher-specific payload. This lifecycle removes only paired
/// [UIResource] transfer targets, preserving clipboard/export behavior and every custom XYML target.
@NotNullByDefault
final class ShellDefaultDropTargetSuppressor
        implements AutoCloseable, ContainerListener, PropertyChangeListener {
    /// Swing property fired after a transfer handler and its native drop target are installed.
    private static final String TRANSFER_HANDLER_PROPERTY = "transferHandler";

    /// Components whose Look-and-Feel transfer-handler changes are currently observed.
    private final Set<JComponent> observedComponents = Collections.newSetFromMap(new IdentityHashMap<>());

    /// Containers whose dynamically added and removed descendants are currently observed.
    private final Set<Container> observedContainers = Collections.newSetFromMap(new IdentityHashMap<>());

    /// Whether every listener owned by this lifecycle has been removed.
    private boolean closed;

    /// Installs descendant observation and immediately reconciles the existing shell tree.
    ///
    /// @param root shell root owning global and page-scoped drop routes
    /// @return installed lifecycle
    static ShellDefaultDropTargetSuppressor install(JComponent root) {
        EdtDispatcher.requireEventDispatchThread();
        return new ShellDefaultDropTargetSuppressor(Objects.requireNonNull(root, "root"));
    }

    /// Creates an installed lifecycle for one shell tree.
    ///
    /// @param root shell root
    private ShellDefaultDropTargetSuppressor(JComponent root) {
        attach(root);
    }

    /// Stops observing the shell tree without restoring controls that are being disposed with it.
    @Override
    public void close() {
        EdtDispatcher.requireEventDispatchThread();
        if (closed) {
            return;
        }
        closed = true;
        for (JComponent component : List.copyOf(observedComponents)) {
            component.removePropertyChangeListener(TRANSFER_HANDLER_PROPERTY, this);
        }
        for (Container container : List.copyOf(observedContainers)) {
            container.removeContainerListener(this);
        }
        observedComponents.clear();
        observedContainers.clear();
    }

    /// Observes and reconciles a descendant added beneath the shell.
    ///
    /// @param event container addition event
    @Override
    public void componentAdded(ContainerEvent event) {
        Objects.requireNonNull(event, "event");
        if (!closed) {
            attach(event.getChild());
        }
    }

    /// Stops observing a detached descendant tree.
    ///
    /// @param event container removal event
    @Override
    public void componentRemoved(ContainerEvent event) {
        Objects.requireNonNull(event, "event");
        if (!closed) {
            detach(event.getChild());
        }
    }

    /// Reconciles a Look-and-Feel transfer handler after Swing installs its matching drop target.
    ///
    /// @param event transfer-handler property change
    @Override
    public void propertyChange(PropertyChangeEvent event) {
        PropertyChangeEvent change = Objects.requireNonNull(event, "event");
        if (!closed && change.getSource() instanceof JComponent component) {
            suppressDefaultDropTarget(component);
        }
    }

    /// Observes one component and all current descendants.
    ///
    /// @param component component entering the shell tree
    private void attach(Component component) {
        Component child = Objects.requireNonNull(component, "component");
        if (child instanceof JComponent swingComponent && observedComponents.add(swingComponent)) {
            swingComponent.addPropertyChangeListener(TRANSFER_HANDLER_PROPERTY, this);
            suppressDefaultDropTarget(swingComponent);
        }
        if (child instanceof Container container && observedContainers.add(container)) {
            container.addContainerListener(this);
            for (Component descendant : container.getComponents()) {
                attach(descendant);
            }
        }
    }

    /// Stops observing one component and all current descendants.
    ///
    /// @param component component leaving the shell tree
    private void detach(Component component) {
        Component child = Objects.requireNonNull(component, "component");
        if (child instanceof Container container && observedContainers.remove(container)) {
            container.removeContainerListener(this);
            for (Component descendant : container.getComponents()) {
                detach(descendant);
            }
        }
        if (child instanceof JComponent swingComponent && observedComponents.remove(swingComponent)) {
            swingComponent.removePropertyChangeListener(TRANSFER_HANDLER_PROPERTY, this);
        }
    }

    /// Removes only a paired Look-and-Feel import target while retaining its transfer handler.
    ///
    /// @param component observed Swing component
    private static void suppressDefaultDropTarget(JComponent component) {
        @Nullable TransferHandler transferHandler = component.getTransferHandler();
        @Nullable DropTarget dropTarget = component.getDropTarget();
        if (transferHandler instanceof UIResource && dropTarget instanceof UIResource) {
            component.setDropTarget(null);
        }
    }
}

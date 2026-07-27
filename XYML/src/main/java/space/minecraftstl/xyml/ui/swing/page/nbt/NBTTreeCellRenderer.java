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
package space.minecraftstl.xyml.ui.swing.page.nbt;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import space.minecraftstl.xyml.nbt.NBTNodeType;
import space.minecraftstl.xyml.nbt.NBTTreeNode;

import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultTreeCellRenderer;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import java.util.Map;
import java.util.Objects;

/// Theme-compatible tree renderer that reuses the launcher's bundled NBT type artwork.
@NotNullByDefault
final class NBTTreeCellRenderer extends DefaultTreeCellRenderer {
    /// Logical tree icon edge retained from the former NBT page.
    private static final int ICON_SIZE = 16;

    /// Process-wide immutable decoded icon cache populated only by a background caller.
    private static volatile @Unmodifiable Map<NBTNodeType, Icon> cachedIcons = Map.of();

    /// Bundled fixed-size icons indexed by toolkit-neutral node category.
    private @Unmodifiable Map<NBTNodeType, Icon> icons = Map.of();

    /// Stable localized count formatting.
    private final NBTEditorStrings strings;

    /// Creates a renderer without reading or decoding resources on the EDT.
    ///
    /// @param strings localized count formatting
    NBTTreeCellRenderer(NBTEditorStrings strings) {
        this.strings = Objects.requireNonNull(strings, "strings");
    }

    /// Loads and decodes the complete immutable icon table on a caller-owned background thread.
    ///
    /// The first successful caller populates a process-wide cache; later pages reuse the decoded
    /// `ImageIcon` instances without classpath reads. This method must not be called on the EDT.
    ///
    /// @return complete immutable icon table
    static @Unmodifiable Map<NBTNodeType, Icon> loadIcons() {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("NBT tree icons must be loaded outside the EDT");
        }
        @Unmodifiable Map<NBTNodeType, Icon> current = cachedIcons;
        if (!current.isEmpty()) {
            return current;
        }
        synchronized (NBTTreeCellRenderer.class) {
            current = cachedIcons;
            if (!current.isEmpty()) {
                return current;
            }
            Icon byteIcon = loadIcon("TAG_Byte.png");
            Icon shortIcon = loadIcon("TAG_Short.png");
            Icon intIcon = loadIcon("TAG_Int.png");
            Icon longIcon = loadIcon("TAG_Long.png");
            Icon floatIcon = loadIcon("TAG_Float.png");
            Icon doubleIcon = loadIcon("TAG_Double.png");
            Icon stringIcon = loadIcon("TAG_String.png");
            Icon byteArrayIcon = loadIcon("TAG_Byte_Array.png");
            Icon intArrayIcon = loadIcon("TAG_Int_Array.png");
            Icon longArrayIcon = loadIcon("TAG_Long_Array.png");
            Icon listIcon = loadIcon("TAG_List.png");
            Icon compoundIcon = loadIcon("TAG_Compound.png");
            current = Map.ofEntries(
                    Map.entry(NBTNodeType.BYTE, byteIcon),
                    Map.entry(NBTNodeType.SHORT, shortIcon),
                    Map.entry(NBTNodeType.INT, intIcon),
                    Map.entry(NBTNodeType.LONG, longIcon),
                    Map.entry(NBTNodeType.FLOAT, floatIcon),
                    Map.entry(NBTNodeType.DOUBLE, doubleIcon),
                    Map.entry(NBTNodeType.STRING, stringIcon),
                    Map.entry(NBTNodeType.BYTE_ARRAY, byteArrayIcon),
                    Map.entry(NBTNodeType.INT_ARRAY, intArrayIcon),
                    Map.entry(NBTNodeType.LONG_ARRAY, longArrayIcon),
                    Map.entry(NBTNodeType.LIST, listIcon),
                    Map.entry(NBTNodeType.COMPOUND, compoundIcon),
                    Map.entry(NBTNodeType.CHUNK, compoundIcon),
                    Map.entry(NBTNodeType.CHUNK_REGION, listIcon),
                    Map.entry(NBTNodeType.UNKNOWN, compoundIcon));
            cachedIcons = current;
            return current;
        }
    }

    /// Installs a completely decoded immutable icon table without performing resource access.
    ///
    /// @param replacement decoded icon table
    void installIcons(@Unmodifiable Map<NBTNodeType, Icon> replacement) {
        icons = Map.copyOf(Objects.requireNonNull(replacement, "replacement"));
    }

    /// Renders one lazy node using stable metadata and the current tree selection palette.
    ///
    /// @param tree owning tree
    /// @param value row value
    /// @param selected whether selected
    /// @param expanded whether expanded
    /// @param leaf whether structurally a leaf
    /// @param row visible row index
    /// @param hasFocus whether the row owns focus
    /// @return configured renderer component
    @Override
    public Component getTreeCellRendererComponent(
            JTree tree,
            @Nullable Object value,
            boolean selected,
            boolean expanded,
            boolean leaf,
            int row,
            boolean hasFocus) {
        super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
        if (!(value instanceof NBTEditorTreeNode node)) {
            return this;
        }
        NBTTreeNode presentation = node.presentation();
        @Nullable Icon icon = icons.get(presentation.type());
        if (icon != null) {
            setIcon(icon);
        }
        @Nullable String scalar = node.currentScalarValue();
        if (scalar != null) {
            setText(presentation.displayName() + " = " + scalar);
        } else if (presentation.childCount() > 0) {
            setText(presentation.displayName() + " (" + strings.entries(presentation.childCount()) + ")");
        } else {
            setText(presentation.displayName());
        }
        setToolTipText(presentation.type().name());
        return this;
    }

    /// Loads and fully resamples one required NBT icon from the packaged resources.
    ///
    /// @param fileName resource filename
    /// @return loaded icon
    private static Icon loadIcon(String fileName) {
        String resource = "/assets/img/nbt/" + Objects.requireNonNull(fileName, "fileName");
        URL url = Objects.requireNonNull(
                NBTTreeCellRenderer.class.getResource(resource),
                "Missing NBT icon: " + resource);
        final BufferedImage source;
        try {
            source = ImageIO.read(url);
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to decode NBT icon: " + resource, failure);
        }
        if (source == null) {
            throw new IllegalStateException("Unsupported NBT icon: " + resource);
        }
        BufferedImage scaled = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(
                    RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, ICON_SIZE, ICON_SIZE, null);
        } finally {
            graphics.dispose();
            source.flush();
        }
        return new ImageIcon(scaled);
    }
}

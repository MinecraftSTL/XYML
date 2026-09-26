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
package space.minecraftstl.xyml.ui.swing.page.accounts;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import space.minecraftstl.xyml.auth.yggdrasil.TextureModel;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Objects;

/// Dependency-free software renderer for the Minecraft player skin preview.
///
/// The renderer builds a small cuboid model, applies hierarchical pose transforms, projects the visible faces into
/// Java2D coordinates, and paints nearest-neighbor texture regions. It intentionally keeps the scope limited to the
/// preview: the model contains the player cuboids, modern outer layers, and an optional cape.
@NotNullByDefault
final class SoftwareSkinRenderer {
    /// Perspective distance in model pixels.
    private static final double VIEW_DISTANCE = 72.0;

    /// Empty space reserved around the projected model.
    private static final double PREVIEW_PADDING = 18.0;

    /// Smallest accepted wheel zoom.
    private static final double MIN_ZOOM = 0.55;

    /// Largest accepted wheel zoom.
    private static final double MAX_ZOOM = 2.4;

    /// Prevents utility instantiation.
    private SoftwareSkinRenderer() {
    }

    /// Renders one animated skin frame.
    ///
    /// @param graphics destination graphics
    /// @param width destination width
    /// @param height destination height
    /// @param skin decoded player texture
    /// @param cape decoded cape texture, or null
    /// @param model arm model
    /// @param motion movement cycle
    /// @param posture body posture
    /// @param yawDegrees horizontal view rotation
    /// @param pitchDegrees vertical view rotation
    /// @param zoom wheel zoom factor
    /// @param seconds animation time in seconds
    static void render(
            Graphics2D graphics,
            int width,
            int height,
            BufferedImage skin,
            @Nullable BufferedImage cape,
            TextureModel model,
            SkinPreviewMotion motion,
            SkinPreviewPosture posture,
            double yawDegrees,
            double pitchDegrees,
            double zoom,
            double seconds) {
        Objects.requireNonNull(graphics, "graphics");
        Objects.requireNonNull(skin, "skin");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(motion, "motion");
        Objects.requireNonNull(posture, "posture");
        if (width <= 0 || height <= 0) {
            return;
        }

        Graphics2D paint = (Graphics2D) graphics.create();
        try {
            paint.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            paint.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            paint.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            boolean modern = skin.getHeight() >= skin.getWidth();
            SkinPreviewTransform view = SkinPreviewTransform.rotateX(pitchDegrees)
                    .multiply(SkinPreviewTransform.rotateY(yawDegrees));
            Model scene = buildModel(model, motion, posture, seconds, modern, cape);
            List<RawFace> faces = transformFaces(scene, view, skin, cape);
            if (faces.isEmpty()) {
                return;
            }

            List<RawFace> referenceFaces = referenceFaces(model, motion, posture, modern, cape, view, skin);
            Bounds bounds = referenceFaces.isEmpty() ? bounds(faces) : bounds(referenceFaces);
            Projection projection = projection(width, height, bounds, zoom);
            paintShadow(paint, bounds, projection);
            faces.sort(Comparator.comparingDouble(RawFace::depth).reversed()
                    .thenComparingInt(RawFace::layer));
            Map<ShadedTexture, BufferedImage> shadedTextures = new HashMap<>();
            for (RawFace face : faces) {
                paintFace(paint, face, projection, shadedTextures);
            }
        } finally {
            paint.dispose();
        }
    }

    /// Builds stable projection samples for the selected posture and movement.
    ///
    /// @param model arm model
    /// @param motion movement cycle
    /// @param posture body posture
    /// @param modern whether the texture has modern lower layers
    /// @param cape decoded cape texture, or null
    /// @param view camera rotation
    /// @param skin decoded player texture
    /// @return reference faces independent of the current animation phase
    private static List<RawFace> referenceFaces(
            TextureModel model,
            SkinPreviewMotion motion,
            SkinPreviewPosture posture,
            boolean modern,
            @Nullable BufferedImage cape,
            SkinPreviewTransform view,
            BufferedImage skin) {
        List<RawFace> faces = new ArrayList<>();
        faces.addAll(transformFaces(buildModel(model, motion, posture, 0.0, modern, cape), view, skin, cape));
        double halfPeriod = halfPeriod(motion);
        if (halfPeriod > 0.0) {
            faces.addAll(transformFaces(buildModel(model, motion, posture, halfPeriod, modern, cape), view, skin, cape));
        }
        return faces;
    }

    /// Returns the 1.21 horizontal movement amount used by limb swing.
    ///
    /// @param motion movement cycle
    /// @return limb swing amount
    private static double limbAmount(SkinPreviewMotion motion) {
        return switch (motion) {
            case IDLE -> 0.0;
            case WALKING -> 0.4;
            case SPRINTING -> 0.52;
        };
    }

    /// Returns one half limb-swing period for stable framing samples.
    ///
    /// @param motion movement cycle
    /// @return half period in seconds, or zero for idle
    private static double halfPeriod(SkinPreviewMotion motion) {
        double amount = limbAmount(motion);
        return amount == 0.0 ? 0.0 : Math.PI / (20.0 * amount * 0.6662);
    }

    /// Builds the player model using Minecraft Java 1.21 limb and pose rules.
    ///
    /// @param model arm model
    /// @param motion requested movement cycle
    /// @param posture requested body posture
    /// @param seconds animation time in seconds
    /// @param modern whether the texture contains modern lower layers
    /// @param cape decoded cape texture, or null
    /// @return base cuboids and six-face outer shells
    private static Model buildModel(
            TextureModel model,
            SkinPreviewMotion motion,
            SkinPreviewPosture posture,
            double seconds,
            boolean modern,
            @Nullable BufferedImage cape) {
        SkinPreviewMotion effectiveMotion = motion == SkinPreviewMotion.SPRINTING
                && posture != SkinPreviewPosture.STANDING
                ? SkinPreviewMotion.WALKING
                : motion;
        double limbAmount = limbAmount(effectiveMotion);
        double limbPhase = seconds * 20.0 * limbAmount * 0.6662;
        double swing = Math.cos(limbPhase);
        double walkLegPitch = Math.toDegrees(1.4 * limbAmount * swing);
        double walkArmPitch = Math.toDegrees(-limbAmount * swing);

        double rightLegPitch = walkLegPitch;
        double leftLegPitch = -walkLegPitch;
        double rightArmPitch = walkArmPitch;
        double leftArmPitch = -walkArmPitch;
        double rightArmYaw = 0.0;
        double leftArmYaw = 0.0;
        double rightArmRoll = 0.0;
        double leftArmRoll = 0.0;
        if (effectiveMotion == SkinPreviewMotion.SPRINTING) {
            rightArmPitch -= 22.9183;
            leftArmPitch -= 22.9183;
            rightArmYaw = -11.4592;
            leftArmYaw = 11.4592;
        }

        double rootY = 0.0;
        double bodyPitch = 0.0;
        double headPitch = 0.0;
        double rightLegYaw = 0.0;
        double leftLegYaw = 0.0;
        double rightLegRoll = 0.0;
        double leftLegRoll = 0.0;
        double capePitch = 5.0;
        SkinPreviewTransform root = SkinPreviewTransform.identity();
        switch (posture) {
            case STANDING -> {
                // Minecraft 1.21 standing pose.
            }
            case SNEAKING -> {
                bodyPitch = 28.6479;
                rightArmPitch += 22.9183;
                leftArmPitch += 22.9183;
                rightLegPitch -= 22.9183;
                leftLegPitch -= 22.9183;
                capePitch += 10.0;
            }
            case RIDING -> {
                rightLegPitch = -81.0289;
                leftLegPitch = -81.0289;
                rightLegYaw = 18.0;
                leftLegYaw = -18.0;
                rightLegRoll = 4.5;
                leftLegRoll = -4.5;
                capePitch += 10.0;
            }
            case SWIMMING -> {
                root = SkinPreviewTransform.translate(0.0, 0.0, 0.0)
                        .multiply(SkinPreviewTransform.rotateX(90.0));
                headPitch = 0.0;
                rightArmPitch = 180.0;
                leftArmPitch = 180.0;
                rightArmYaw = -20.0;
                leftArmYaw = 20.0;
                rightLegPitch = 0.0;
                leftLegPitch = 0.0;
                capePitch = 18.0;
            }
            case SLEEPING -> {
                root = SkinPreviewTransform.translate(0.0, 0.0, 0.0)
                        .multiply(SkinPreviewTransform.rotateZ(90.0));
                rightArmPitch = 0.0;
                leftArmPitch = 0.0;
                rightLegPitch = 0.0;
                leftLegPitch = 0.0;
                capePitch = 0.0;
            }
            case FALL_FLYING -> {
                root = SkinPreviewTransform.translate(0.0, 0.0, 0.0)
                        .multiply(SkinPreviewTransform.rotateX(90.0));
                headPitch = 0.0;
                rightArmPitch = 0.0;
                leftArmPitch = 0.0;
                rightArmRoll = 90.0;
                leftArmRoll = -90.0;
                rightLegPitch = 0.0;
                leftLegPitch = 0.0;
                capePitch = 12.0;
            }
        }
        root = SkinPreviewTransform.translate(0.0, rootY, 0.0).multiply(root);

        SkinPreviewTransform torso = root.multiply(SkinPreviewTransform.around(
                new SkinPreviewTransform.Vector(0.0, 2.0, 0.0),
                SkinPreviewTransform.Axis.X,
                bodyPitch));
        SkinPreviewTransform head = torso.multiply(SkinPreviewTransform.around(
                new SkinPreviewTransform.Vector(0.0, 12.0, 0.0),
                SkinPreviewTransform.Axis.X,
                headPitch));

        int armWidth = model == TextureModel.SLIM ? 3 : 4;
        double armCenterX = 4.0 + armWidth / 2.0;
        SkinPreviewTransform rightArm = torso
                .multiply(SkinPreviewTransform.around(
                        new SkinPreviewTransform.Vector(armCenterX, 8.0, 0.0),
                        SkinPreviewTransform.Axis.X,
                        rightArmPitch))
                .multiply(SkinPreviewTransform.around(
                        new SkinPreviewTransform.Vector(armCenterX, 8.0, 0.0),
                        SkinPreviewTransform.Axis.Y,
                        rightArmYaw))
                .multiply(SkinPreviewTransform.around(
                        new SkinPreviewTransform.Vector(armCenterX, 8.0, 0.0),
                        SkinPreviewTransform.Axis.Z,
                        rightArmRoll));
        SkinPreviewTransform leftArm = torso
                .multiply(SkinPreviewTransform.around(
                        new SkinPreviewTransform.Vector(-armCenterX, 8.0, 0.0),
                        SkinPreviewTransform.Axis.X,
                        leftArmPitch))
                .multiply(SkinPreviewTransform.around(
                        new SkinPreviewTransform.Vector(-armCenterX, 8.0, 0.0),
                        SkinPreviewTransform.Axis.Y,
                        leftArmYaw))
                .multiply(SkinPreviewTransform.around(
                        new SkinPreviewTransform.Vector(-armCenterX, 8.0, 0.0),
                        SkinPreviewTransform.Axis.Z,
                        leftArmRoll));
        SkinPreviewTransform rightLeg = root
                .multiply(SkinPreviewTransform.around(
                        new SkinPreviewTransform.Vector(2.0, -4.0, 0.0),
                        SkinPreviewTransform.Axis.X,
                        rightLegPitch))
                .multiply(SkinPreviewTransform.around(
                        new SkinPreviewTransform.Vector(2.0, -4.0, 0.0),
                        SkinPreviewTransform.Axis.Y,
                        rightLegYaw))
                .multiply(SkinPreviewTransform.around(
                        new SkinPreviewTransform.Vector(2.0, -4.0, 0.0),
                        SkinPreviewTransform.Axis.Z,
                        rightLegRoll));
        SkinPreviewTransform leftLeg = root
                .multiply(SkinPreviewTransform.around(
                        new SkinPreviewTransform.Vector(-2.0, -4.0, 0.0),
                        SkinPreviewTransform.Axis.X,
                        leftLegPitch))
                .multiply(SkinPreviewTransform.around(
                        new SkinPreviewTransform.Vector(-2.0, -4.0, 0.0),
                        SkinPreviewTransform.Axis.Y,
                        leftLegYaw))
                .multiply(SkinPreviewTransform.around(
                        new SkinPreviewTransform.Vector(-2.0, -4.0, 0.0),
                        SkinPreviewTransform.Axis.Z,
                        leftLegRoll));

        List<BoxPart> parts = new ArrayList<>();
        parts.add(boxPart(
                box(0.0, 12.0, 0.0, 8.0, 8.0, 8.0, boxTexture(0, 0, 8, 8, 8)),
                head,
                0));
        parts.add(boxPart(
                box(0.0, 2.0, 0.0, 8.0, 12.0, 4.0, boxTexture(16, 16, 8, 12, 4)),
                torso,
                0));
        parts.add(boxPart(
                box(armCenterX, 2.0, 0.0, armWidth, 12.0, 4.0, boxTexture(40, 16, armWidth, 12, 4)),
                rightArm,
                0));
        parts.add(boxPart(
                box(-armCenterX, 2.0, 0.0, armWidth, 12.0, 4.0,
                        boxTexture(modern ? 32 : 40, modern ? 48 : 16, armWidth, 12, 4)),
                leftArm,
                0));
        parts.add(boxPart(
                box(2.0, -10.0, 0.0, 4.0, 12.0, 4.0, boxTexture(0, 16, 4, 12, 4)),
                rightLeg,
                0));
        parts.add(boxPart(
                box(-2.0, -10.0, 0.0, 4.0, 12.0, 4.0,
                        boxTexture(modern ? 16 : 0, modern ? 48 : 16, 4, 12, 4)),
                leftLeg,
                0));

        if (cape != null) {
            SkinPreviewTransform capeTransform = torso.multiply(SkinPreviewTransform.around(
                    new SkinPreviewTransform.Vector(0.0, 8.0, -4.0),
                    SkinPreviewTransform.Axis.X,
                    capePitch + bodyPitch * 0.2));
            parts.add(capePart(
                    box(0.0, 0.0, -4.5, 10.0, 16.0, 1.0, boxTexture(0, 0, 10, 16, 1)),
                    capeTransform));
        }

        List<OuterPart> outerParts = new ArrayList<>();
        outerParts.add(new OuterPart(
                boxTexture(32, 0, 8, 8, 8),
                4.5, 4.5, 4.5,
                head.multiply(SkinPreviewTransform.translate(0.0, 12.0, 0.0))));
        if (modern) {
            outerParts.add(new OuterPart(
                    boxTexture(16, 32, 8, 12, 4),
                    4.25, 6.25, 2.25,
                    torso.multiply(SkinPreviewTransform.translate(0.0, 2.0, 0.0))));
            outerParts.add(new OuterPart(
                    boxTexture(40, 32, armWidth, 12, 4),
                    (armWidth + 0.5) / 2.0, 6.25, 2.25,
                    rightArm.multiply(SkinPreviewTransform.translate(armCenterX, 2.0, 0.0))));
            outerParts.add(new OuterPart(
                    boxTexture(48, 48, armWidth, 12, 4),
                    (armWidth + 0.5) / 2.0, 6.25, 2.25,
                    leftArm.multiply(SkinPreviewTransform.translate(-armCenterX, 2.0, 0.0))));
            outerParts.add(new OuterPart(
                    boxTexture(0, 32, 4, 12, 4),
                    2.25, 6.25, 2.25,
                    rightLeg.multiply(SkinPreviewTransform.translate(2.0, -10.0, 0.0))));
            outerParts.add(new OuterPart(
                    boxTexture(0, 48, 4, 12, 4),
                    2.25, 6.25, 2.25,
                    leftLeg.multiply(SkinPreviewTransform.translate(-2.0, -10.0, 0.0))));
        }
        return new Model(List.copyOf(parts), List.copyOf(outerParts));
    }

    /// Transforms and back-face-culls all cuboid faces.
    ///
    /// @param parts model parts
    /// @param view camera rotation
    /// @return visible faces
    private static List<RawFace> transformFaces(
            Model scene,
            SkinPreviewTransform view,
            BufferedImage skin,
            @Nullable BufferedImage cape) {
        List<RawFace> faces = new ArrayList<>();
        for (BoxPart part : scene.parts()) {
            addBoxFaces(faces, part, view, skin, cape);
        }
        for (OuterPart part : scene.outerParts()) {
            addOuterFaces(faces, part, view, skin);
        }
        return faces;
    }

    /// Adds the six textured outer-layer planes used by double-layer skin textures.
    ///
    /// Every outer face uses the Minecraft 1.21 dilation of 0.25 on each side and is rendered as one textured quad,
    /// preserving alpha instead of converting transparent pixels into opaque solid tiles.
    ///
    /// @param faces destination faces
    /// @param part outer layer surface definition
    /// @param view camera rotation
    /// @param skin decoded player texture
    private static void addOuterFaces(
            List<RawFace> faces,
            OuterPart part,
            SkinPreviewTransform view,
            BufferedImage skin) {
        SkinPreviewTransform transform = view.multiply(part.transform());
        double x = part.halfWidth();
        double y = part.halfHeight();
        double z = part.halfDepth();
        double planeX = x;
        double planeY = y;
        double planeZ = z;
        BoxTexture texture = part.texture();

        addFace(
                faces,
                new SkinPreviewTransform.Vector(-x, y, planeZ),
                new SkinPreviewTransform.Vector(x, y, planeZ),
                new SkinPreviewTransform.Vector(x, -y, planeZ),
                new SkinPreviewTransform.Vector(-x, -y, planeZ),
                new SkinPreviewTransform.Vector(0.0, 0.0, 1.0),
                texture.front(),
                1.0,
                1,
                transform,
                skin);
        addFace(
                faces,
                new SkinPreviewTransform.Vector(x, y, -planeZ),
                new SkinPreviewTransform.Vector(-x, y, -planeZ),
                new SkinPreviewTransform.Vector(-x, -y, -planeZ),
                new SkinPreviewTransform.Vector(x, -y, -planeZ),
                new SkinPreviewTransform.Vector(0.0, 0.0, -1.0),
                texture.back(),
                0.78,
                1,
                transform,
                skin);
        addFace(
                faces,
                new SkinPreviewTransform.Vector(planeX, y, z),
                new SkinPreviewTransform.Vector(planeX, y, -z),
                new SkinPreviewTransform.Vector(planeX, -y, -z),
                new SkinPreviewTransform.Vector(planeX, -y, z),
                new SkinPreviewTransform.Vector(1.0, 0.0, 0.0),
                texture.right(),
                0.88,
                1,
                transform,
                skin);
        addFace(
                faces,
                new SkinPreviewTransform.Vector(-planeX, y, z),
                new SkinPreviewTransform.Vector(-planeX, y, -z),
                new SkinPreviewTransform.Vector(-planeX, -y, -z),
                new SkinPreviewTransform.Vector(-planeX, -y, z),
                new SkinPreviewTransform.Vector(-1.0, 0.0, 0.0),
                texture.left(),
                0.88,
                1,
                transform,
                skin);
        addFace(
                faces,
                new SkinPreviewTransform.Vector(-x, planeY, -z),
                new SkinPreviewTransform.Vector(x, planeY, -z),
                new SkinPreviewTransform.Vector(x, planeY, z),
                new SkinPreviewTransform.Vector(-x, planeY, z),
                new SkinPreviewTransform.Vector(0.0, 1.0, 0.0),
                texture.top(),
                1.05,
                1,
                transform,
                skin);
        addFace(
                faces,
                new SkinPreviewTransform.Vector(-x, -planeY, -z),
                new SkinPreviewTransform.Vector(x, -planeY, -z),
                new SkinPreviewTransform.Vector(x, -planeY, z),
                new SkinPreviewTransform.Vector(-x, -planeY, z),
                new SkinPreviewTransform.Vector(0.0, -1.0, 0.0),
                texture.bottom(),
                0.68,
                1,
                transform,
                skin);
    }

    /// Adds the visible faces of one transformed cuboid.
    ///
    /// @param faces destination list
    /// @param part model part
    /// @param view camera rotation
    private static void addBoxFaces(
            List<RawFace> faces,
            BoxPart part,
            SkinPreviewTransform view,
            BufferedImage skin,
            @Nullable BufferedImage cape) {
        Box box = part.box();
        BufferedImage image = part.cape() ? Objects.requireNonNull(cape, "cape") : skin;
        double x0 = box.x() - box.width() / 2.0;
        double x1 = box.x() + box.width() / 2.0;
        double y0 = box.y() - box.height() / 2.0;
        double y1 = box.y() + box.height() / 2.0;
        double z0 = box.z() - box.depth() / 2.0;
        double z1 = box.z() + box.depth() / 2.0;
        SkinPreviewTransform transform = view.multiply(part.transform());
        BoxTexture texture = box.texture();

        addFace(
                faces,
                new SkinPreviewTransform.Vector(x0, y1, z1),
                new SkinPreviewTransform.Vector(x1, y1, z1),
                new SkinPreviewTransform.Vector(x1, y0, z1),
                new SkinPreviewTransform.Vector(x0, y0, z1),
                new SkinPreviewTransform.Vector(0.0, 0.0, 1.0),
                texture.front(),
                1.0,
                part.layer(),
                transform,
                image);
        addFace(
                faces,
                new SkinPreviewTransform.Vector(x1, y1, z0),
                new SkinPreviewTransform.Vector(x0, y1, z0),
                new SkinPreviewTransform.Vector(x0, y0, z0),
                new SkinPreviewTransform.Vector(x1, y0, z0),
                new SkinPreviewTransform.Vector(0.0, 0.0, -1.0),
                texture.back(),
                0.78,
                part.layer(),
                transform,
                image);
        addFace(
                faces,
                new SkinPreviewTransform.Vector(x1, y1, z0),
                new SkinPreviewTransform.Vector(x1, y1, z1),
                new SkinPreviewTransform.Vector(x1, y0, z1),
                new SkinPreviewTransform.Vector(x1, y0, z0),
                new SkinPreviewTransform.Vector(1.0, 0.0, 0.0),
                texture.right(),
                0.88,
                part.layer(),
                transform,
                image);
        addFace(
                faces,
                new SkinPreviewTransform.Vector(x0, y1, z1),
                new SkinPreviewTransform.Vector(x0, y1, z0),
                new SkinPreviewTransform.Vector(x0, y0, z0),
                new SkinPreviewTransform.Vector(x0, y0, z1),
                new SkinPreviewTransform.Vector(-1.0, 0.0, 0.0),
                texture.left(),
                0.88,
                part.layer(),
                transform,
                image);
        addFace(
                faces,
                new SkinPreviewTransform.Vector(x1, y1, z0),
                new SkinPreviewTransform.Vector(x0, y1, z0),
                new SkinPreviewTransform.Vector(x0, y1, z1),
                new SkinPreviewTransform.Vector(x1, y1, z1),
                new SkinPreviewTransform.Vector(0.0, 1.0, 0.0),
                texture.top(),
                1.05,
                part.layer(),
                transform,
                image);
        addFace(
                faces,
                new SkinPreviewTransform.Vector(x1, y0, z0),
                new SkinPreviewTransform.Vector(x0, y0, z0),
                new SkinPreviewTransform.Vector(x0, y0, z1),
                new SkinPreviewTransform.Vector(x1, y0, z1),
                new SkinPreviewTransform.Vector(0.0, -1.0, 0.0),
                texture.bottom(),
                0.68,
                part.layer(),
                transform,
                image);
    }

    /// Adds one potentially visible textured face.
    ///
    /// @param faces destination list
    /// @param p0 texture top-left vertex
    /// @param p1 texture top-right vertex
    /// @param p2 texture bottom-right vertex
    /// @param p3 texture bottom-left vertex
    /// @param normal outward normal
    /// @param region source texture region
    /// @param shade face brightness multiplier
    /// @param layer equal-depth layer ordering
    /// @param image source texture image
    private static void addFace(
            List<RawFace> faces,
            SkinPreviewTransform.Vector p0,
            SkinPreviewTransform.Vector p1,
            SkinPreviewTransform.Vector p2,
            SkinPreviewTransform.Vector p3,
            SkinPreviewTransform.Vector normal,
            TextureRegion region,
            double shade,
            int layer,
            SkinPreviewTransform transform,
            BufferedImage image) {
        SkinPreviewTransform.Vector t0 = transform.apply(p0);
        SkinPreviewTransform.Vector t1 = transform.apply(p1);
        SkinPreviewTransform.Vector t2 = transform.apply(p2);
        SkinPreviewTransform.Vector t3 = transform.apply(p3);
        SkinPreviewTransform.Vector transformedNormal = transform.applyDirection(normal);
        SkinPreviewTransform.Vector centroid = t0.add(t1).add(t2).add(t3).multiply(0.25);
        SkinPreviewTransform.Vector toCamera = new SkinPreviewTransform.Vector(
                -centroid.x(), -centroid.y(), VIEW_DISTANCE - centroid.z());
        if (transformedNormal.dot(toCamera) <= 0.0) {
            return;
        }
        @Nullable RawFace projected = projectFace(t0, t1, t2, t3, region, shade, layer, image);
        if (projected != null) {
            faces.add(projected);
        }
    }

    /// Projects one face and rejects geometry that crosses the camera plane.
    ///
    /// @param p0 transformed top-left vertex
    /// @param p1 transformed top-right vertex
    /// @param p2 transformed bottom-right vertex
    /// @param p3 transformed bottom-left vertex
    /// @param region source texture region
    /// @param shade face brightness multiplier
    /// @param layer equal-depth layer ordering
    /// @param image source texture image
    /// @return projected face, or null when clipped
    private static @Nullable RawFace projectFace(
            SkinPreviewTransform.Vector p0,
            SkinPreviewTransform.Vector p1,
            SkinPreviewTransform.Vector p2,
            SkinPreviewTransform.Vector p3,
            TextureRegion region,
            double shade,
            int layer,
            BufferedImage image) {
        @Nullable RawPoint r0 = projectPoint(p0);
        @Nullable RawPoint r1 = projectPoint(p1);
        @Nullable RawPoint r2 = projectPoint(p2);
        @Nullable RawPoint r3 = projectPoint(p3);
        if (r0 == null || r1 == null || r2 == null || r3 == null) {
            return null;
        }
        double depth = (r0.depth() + r1.depth() + r2.depth() + r3.depth()) * 0.25;
        return new RawFace(r0, r1, r2, r3, depth, region, shade, layer, image);
    }

    /// Projects one model point to normalized camera coordinates.
    ///
    /// @param point camera-space point
    /// @return normalized projected point, or null when behind the camera
    private static @Nullable RawPoint projectPoint(SkinPreviewTransform.Vector point) {
        double depth = VIEW_DISTANCE - point.z();
        if (depth <= 1.0) {
            return null;
        }
        return new RawPoint(point.x() / depth, -point.y() / depth, depth);
    }

    /// Computes normalized projection bounds for stable model framing.
    ///
    /// @param faces visible faces
    /// @return projection bounds
    private static Bounds bounds(List<RawFace> faces) {
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (RawFace face : faces) {
            RawPoint[] points = {face.p0(), face.p1(), face.p2(), face.p3()};
            for (RawPoint point : points) {
                minX = Math.min(minX, point.x());
                maxX = Math.max(maxX, point.x());
                minY = Math.min(minY, point.y());
                maxY = Math.max(maxY, point.y());
            }
        }
        return new Bounds(minX, maxX, minY, maxY);
    }

    /// Resolves the screen projection from normalized bounds and zoom.
    ///
    /// @param width destination width
    /// @param height destination height
    /// @param bounds normalized projection bounds
    /// @param zoom wheel zoom factor
    /// @return screen projection
    private static Projection projection(int width, int height, Bounds bounds, double zoom) {
        double rangeX = Math.max(0.001, bounds.maxX() - bounds.minX());
        double rangeY = Math.max(0.001, bounds.maxY() - bounds.minY());
        double drawableWidth = Math.max(1.0, width - PREVIEW_PADDING * 2.0);
        double drawableHeight = Math.max(1.0, height - PREVIEW_PADDING * 2.0);
        double scale = Math.min(drawableWidth / rangeX, drawableHeight / rangeY)
                * Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
        double centerX = width / 2.0 - (bounds.minX() + bounds.maxX()) * scale / 2.0;
        double centerY = height / 2.0 - (bounds.minY() + bounds.maxY()) * scale / 2.0;
        return new Projection(scale, centerX, centerY);
    }

    /// Paints a soft ground shadow before the model faces.
    ///
    /// @param paint destination graphics
    /// @param bounds normalized projection bounds
    /// @param projection screen projection
    private static void paintShadow(Graphics2D paint, Bounds bounds, Projection projection) {
        double left = projection.x(bounds.minX());
        double right = projection.x(bounds.maxX());
        double bottom = projection.y(bounds.maxY());
        double width = Math.max(10.0, (right - left) * 0.68);
        double height = Math.max(4.0, width * 0.075);
        paint.setComposite(AlphaComposite.SrcOver);
        paint.setColor(new Color(0, 0, 0, 38));
        paint.fill(new Ellipse2D.Double(
                (left + right - width) / 2.0,
                bottom - height * 0.5,
                width,
                height));
    }

    /// Paints one projected textured face.
    ///
    /// @param paint destination graphics
    /// @param face projected face
    /// @param projection screen projection
    private static void paintFace(
            Graphics2D paint,
            RawFace face,
            Projection projection,
            Map<ShadedTexture, BufferedImage> shadedTextures) {
        double p0x = projection.x(face.p0().x());
        double p0y = projection.y(face.p0().y());
        double p1x = projection.x(face.p1().x());
        double p1y = projection.y(face.p1().y());
        double p2x = projection.x(face.p2().x());
        double p2y = projection.y(face.p2().y());
        double p3x = projection.x(face.p3().x());
        double p3y = projection.y(face.p3().y());
        BufferedImage image = Objects.requireNonNull(face.image(), "image");
        BufferedImage renderImage = shadedImage(image, face.shade(), shadedTextures);
        TextureRegion region = Objects.requireNonNull(face.region(), "region");
        ImageRegion imageRegion = imageRegion(image, region);
        drawTexturedTriangle(
                paint,
                renderImage,
                imageRegion,
                p0x, p0y,
                p1x, p1y,
                p2x, p2y,
                false);
        drawTexturedTriangle(
                paint,
                renderImage,
                imageRegion,
                p0x, p0y,
                p2x, p2y,
                p3x, p3y,
                true);
    }

    /// Returns one alpha-preserving shaded texture.
    ///
    /// @param source source texture
    /// @param shade face brightness multiplier
    /// @param cache per-frame shaded texture cache
    /// @return shaded texture, or the source texture for an identity shade
    private static BufferedImage shadedImage(
            BufferedImage source,
            double shade,
            Map<ShadedTexture, BufferedImage> cache) {
        if (Math.abs(shade - 1.0) < 0.000001) {
            return source;
        }
        return cache.computeIfAbsent(new ShadedTexture(source, shade), key -> {
            BufferedImage shaded = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < source.getHeight(); ++y) {
                for (int x = 0; x < source.getWidth(); ++x) {
                    int argb = source.getRGB(x, y);
                    int alpha = argb >>> 24;
                    if (alpha == 0) {
                        continue;
                    }
                    int red = Math.min(255, (int) Math.round(((argb >> 16) & 0xFF) * shade));
                    int green = Math.min(255, (int) Math.round(((argb >> 8) & 0xFF) * shade));
                    int blue = Math.min(255, (int) Math.round((argb & 0xFF) * shade));
                    shaded.setRGB(x, y, alpha << 24 | red << 16 | green << 8 | blue);
                }
            }
            return shaded;
        });
    }

    /// Paints one source triangle through an exact affine texture mapping.
    ///
    /// @param paint destination graphics
    /// @param image source image
    /// @param imageRegion scalar source rectangle
    /// @param x0 destination X0
    /// @param y0 destination Y0
    /// @param x1 destination X1
    /// @param y1 destination Y1
    /// @param x2 destination X2
    /// @param y2 destination Y2
    /// @param secondTriangle whether this is the second half of the quad
    private static void drawTexturedTriangle(
            Graphics2D paint,
            BufferedImage image,
            ImageRegion imageRegion,
            double x0,
            double y0,
            double x1,
            double y1,
            double x2,
            double y2,
            boolean secondTriangle) {
        double sx0 = imageRegion.x();
        double sy0 = imageRegion.y();
        double sx1 = imageRegion.x() + imageRegion.width();
        double sy1 = imageRegion.y() + imageRegion.height();
        double sourceX0 = sx0;
        double sourceY0 = sy0;
        double sourceX1;
        double sourceY1;
        double sourceX2;
        double sourceY2;
        if (secondTriangle) {
            sourceX1 = sx1;
            sourceY1 = sy1;
            sourceX2 = sx0;
            sourceY2 = sy1;
        } else {
            sourceX1 = sx1;
            sourceY1 = sy0;
            sourceX2 = sx1;
            sourceY2 = sy1;
        }

        double determinant = (sourceX1 - sourceX0) * (sourceY2 - sourceY0)
                - (sourceX2 - sourceX0) * (sourceY1 - sourceY0);
        if (Math.abs(determinant) < 0.000001) {
            return;
        }
        double m00 = ((x1 - x0) * (sourceY2 - sourceY0) - (x2 - x0) * (sourceY1 - sourceY0))
                / determinant;
        double m10 = ((y1 - y0) * (sourceY2 - sourceY0) - (y2 - y0) * (sourceY1 - sourceY0))
                / determinant;
        double m01 = ((x2 - x0) * (sourceX1 - sourceX0) - (x1 - x0) * (sourceX2 - sourceX0))
                / determinant;
        double m11 = ((y2 - y0) * (sourceX1 - sourceX0) - (y1 - y0) * (sourceX2 - sourceX0))
                / determinant;
        double m02 = x0 - m00 * sourceX0 - m01 * sourceY0;
        double m12 = y0 - m10 * sourceX0 - m11 * sourceY0;
        Path2D.Double clip = new Path2D.Double();
        clip.moveTo(x0, y0);
        clip.lineTo(x1, y1);
        clip.lineTo(x2, y2);
        clip.closePath();

        Shape oldClip = paint.getClip();
        AffineTransform oldTransform = paint.getTransform();
        Area expandedClip = new Area(clip);
        expandedClip.add(new Area(new BasicStroke(
                1.0f,
                BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND).createStrokedShape(clip)));
        try {
            paint.clip(expandedClip);
            paint.transform(new AffineTransform(m00, m10, m01, m11, m02, m12));
            paint.drawImage(
                    image,
                    (int) Math.floor(imageRegion.x()),
                    (int) Math.floor(imageRegion.y()),
                    (int) Math.ceil(imageRegion.x() + imageRegion.width()),
                    (int) Math.ceil(imageRegion.y() + imageRegion.height()),
                    (int) Math.floor(imageRegion.x()),
                    (int) Math.floor(imageRegion.y()),
                    (int) Math.ceil(imageRegion.x() + imageRegion.width()),
                    (int) Math.ceil(imageRegion.y() + imageRegion.height()),
                    null);
        } finally {
            paint.setTransform(oldTransform);
            paint.setClip(oldClip);
        }
    }

    /// Converts a canonical 64-pixel texture region into actual image pixels.
    ///
    /// @param image source image
    /// @param region canonical texture region
    /// @return non-empty scalar source rectangle
    private static ImageRegion imageRegion(BufferedImage image, TextureRegion region) {
        boolean modern = image.getHeight() >= image.getWidth();
        double scaleX = image.getWidth() / 64.0;
        double scaleY = image.getHeight() / (modern ? 64.0 : 32.0);
        double x = Math.max(0.0, region.x() * scaleX);
        double y = Math.max(0.0, region.y() * scaleY);
        double x2 = Math.min(image.getWidth(), (region.x() + region.width()) * scaleX);
        double y2 = Math.min(image.getHeight(), (region.y() + region.height()) * scaleY);
        return new ImageRegion(x, y, Math.max(1.0, x2 - x), Math.max(1.0, y2 - y));
    }

    /// Creates one cuboid definition.
    ///
    /// @param x center X
    /// @param y center Y
    /// @param z center Z
    /// @param width X size
    /// @param height Y size
    /// @param depth Z size
    /// @param texture six-face texture mapping
    /// @return cuboid definition
    private static Box box(
            double x,
            double y,
            double z,
            double width,
            double height,
            double depth,
            BoxTexture texture) {
        return new Box(x, y, z, width, height, depth, texture);
    }

    /// Creates one model part.
    ///
    /// @param box cuboid geometry
    /// @param transform model transform
    /// @param layer equal-depth layer ordering
    /// @return model part
    private static BoxPart boxPart(Box box, SkinPreviewTransform transform, int layer) {
        return new BoxPart(box, transform, layer, false);
    }

    /// Creates one optional cape model part.
    ///
    /// @param box cuboid geometry
    /// @param transform model transform
    /// @return cape model part
    private static BoxPart capePart(Box box, SkinPreviewTransform transform) {
        return new BoxPart(box, transform, 2, true);
    }

    /// Creates one standard Minecraft cuboid texture mapping.
    ///
    /// @param x texture origin X
    /// @param y texture origin Y
    /// @param width cuboid width in texture pixels
    /// @param height cuboid height in texture pixels
    /// @param depth cuboid depth in texture pixels
    /// @return six-face texture mapping
    private static BoxTexture boxTexture(
            int x,
            int y,
            int width,
            int height,
            int depth) {
        return new BoxTexture(
                new TextureRegion(x + depth, y, width, depth),
                new TextureRegion(x + depth + width, y, width, depth),
                new TextureRegion(x, y + depth, depth, height),
                new TextureRegion(x + depth, y + depth, width, height),
                new TextureRegion(x + depth + width, y + depth, depth, height),
                new TextureRegion(x + depth + width + depth, y + depth, width, height));
    }

    /// One textured cuboid definition.
    ///
    /// @param x center X
    /// @param y center Y
    /// @param z center Z
    /// @param width X size
    /// @param height Y size
    /// @param depth Z size
    /// @param texture six-face texture mapping
    @NotNullByDefault
    private record Box(
            double x,
            double y,
            double z,
            double width,
            double height,
            double depth,
            BoxTexture texture) {
    }

    /// One transformed cuboid part.
    ///
    /// @param box cuboid geometry
    /// @param transform model transform
    /// @param layer equal-depth layer ordering
    /// @param cape whether this part uses the cape texture
    @NotNullByDefault
    private record BoxPart(
            Box box,
            SkinPreviewTransform transform,
            int layer,
            boolean cape) {
    }

    /// Complete renderable model.
    ///
    /// @param parts textured base and cape cuboids
    /// @param outerParts expanded outer-shell surfaces
    @NotNullByDefault
    private record Model(
            List<BoxPart> parts,
            List<OuterPart> outerParts) {
        /// Validates and freezes the complete model.
        private Model {
            parts = List.copyOf(parts);
            outerParts = List.copyOf(outerParts);
        }
    }

    /// One outer-layer plane definition.
    ///
    /// @param texture six face texture regions
    /// @param halfWidth half width of the expanded outer plane
    /// @param halfHeight half height of the expanded outer plane
    /// @param halfDepth half depth of the expanded outer plane
    /// @param transform model transform
    @NotNullByDefault
    private record OuterPart(
            BoxTexture texture,
            double halfWidth,
            double halfHeight,
            double halfDepth,
            SkinPreviewTransform transform) {
    }

    /// Six texture regions arranged in Minecraft box order.
    ///
    /// @param top top face
    /// @param bottom bottom face
    /// @param right positive-X face
    /// @param front positive-Z face
    /// @param left negative-X face
    /// @param back negative-Z face
    @NotNullByDefault
    private record BoxTexture(
            TextureRegion top,
            TextureRegion bottom,
            TextureRegion right,
            TextureRegion front,
            TextureRegion left,
            TextureRegion back) {
    }

    /// Canonical texture region in 64-pixel coordinates.
    ///
    /// @param x source X
    /// @param y source Y
    /// @param width source width
    /// @param height source height
    @NotNullByDefault
    private record TextureRegion(int x, int y, int width, int height) {
    }

    /// Scalar source rectangle in actual texture pixels.
    ///
    /// @param x source X
    /// @param y source Y
    /// @param width source width
    /// @param height source height
    @NotNullByDefault
    private record ImageRegion(double x, double y, double width, double height) {
    }

    /// One normalized projected point.
    ///
    /// @param x normalized horizontal coordinate
    /// @param y normalized vertical coordinate
    /// @param depth camera depth
    @NotNullByDefault
    private record RawPoint(double x, double y, double depth) {
    }

    /// One per-frame shaded texture cache key.
    ///
    /// @param source source texture
    /// @param shade face brightness multiplier
    @NotNullByDefault
    private record ShadedTexture(BufferedImage source, double shade) {
    }

    /// One visible projected face.
    ///
    /// @param p0 texture top-left point
    /// @param p1 texture top-right point
    /// @param p2 texture bottom-right point
    /// @param p3 texture bottom-left point
    /// @param depth average camera depth
    /// @param region source texture region
    /// @param shade face brightness multiplier
    /// @param layer equal-depth layer ordering
    /// @param image source texture image
    @NotNullByDefault
    private record RawFace(
            RawPoint p0,
            RawPoint p1,
            RawPoint p2,
            RawPoint p3,
            double depth,
            TextureRegion region,
            double shade,
            int layer,
            BufferedImage image) {
    }

    /// Normalized model bounds used for automatic fitting.
    ///
    /// @param minX minimum normalized X
    /// @param maxX maximum normalized X
    /// @param minY minimum normalized Y
    /// @param maxY maximum normalized Y
    @NotNullByDefault
    private record Bounds(double minX, double maxX, double minY, double maxY) {
    }

    /// Screen projection parameters.
    ///
    /// @param scale normalized-to-screen scale
    /// @param centerX X origin
    /// @param centerY Y origin
    @NotNullByDefault
    private record Projection(double scale, double centerX, double centerY) {
        /// Projects one normalized X coordinate.
        ///
        /// @param value normalized coordinate
        /// @return screen X coordinate
        double x(double value) {
            return centerX + value * scale;
        }

        /// Projects one normalized Y coordinate.
        ///
        /// @param value normalized coordinate
        /// @return screen Y coordinate
        double y(double value) {
            return centerY + value * scale;
        }
    }
}

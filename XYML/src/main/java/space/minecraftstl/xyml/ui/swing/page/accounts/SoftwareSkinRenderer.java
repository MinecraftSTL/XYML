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
            List<BoxPart> parts = buildModel(model, motion, posture, seconds, modern, cape);
            SkinPreviewTransform view = SkinPreviewTransform.rotateX(pitchDegrees)
                    .multiply(SkinPreviewTransform.rotateY(yawDegrees));
            List<RawFace> faces = transformFaces(parts, view, skin, cape);
            if (faces.isEmpty()) {
                return;
            }

            Bounds bounds = bounds(faces);
            Projection projection = projection(width, height, bounds, zoom);
            paintShadow(paint, bounds, projection);
            faces.sort(Comparator.comparingDouble(RawFace::depth).reversed()
                    .thenComparingInt(RawFace::layer));
            for (RawFace face : faces) {
                paintFace(paint, face, projection);
            }
        } finally {
            paint.dispose();
        }
    }

    /// Builds the player model and its postured animation transforms.
    ///
    /// @param model arm model
    /// @param motion requested movement cycle
    /// @param posture requested body posture
    /// @param seconds animation time in seconds
    /// @param modern whether the texture contains modern lower layers
    /// @return model parts ready for view transformation
    private static List<BoxPart> buildModel(
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
        double phase = seconds * switch (effectiveMotion) {
            case IDLE -> 1.1;
            case WALKING -> 2.2;
            case SPRINTING -> 3.0;
        } * Math.PI * 2.0;

        double legSwing = 0.0;
        double armSwing = 0.0;
        double bodyLean = 0.0;
        double bodyBob = 0.0;
        if (effectiveMotion == SkinPreviewMotion.IDLE) {
            armSwing = Math.sin(phase) * 1.8;
            bodyBob = Math.sin(phase * 2.0) * 0.12;
        } else if (effectiveMotion == SkinPreviewMotion.WALKING) {
            legSwing = Math.sin(phase) * 34.0;
            armSwing = -Math.sin(phase) * 31.0;
            bodyLean = 3.0;
            bodyBob = Math.abs(Math.sin(phase)) * 0.55;
        } else {
            legSwing = Math.sin(phase) * 50.0;
            armSwing = -Math.sin(phase) * 54.0;
            bodyLean = 15.0 + Math.sin(phase * 2.0) * 2.0;
            bodyBob = Math.abs(Math.sin(phase)) * 0.9;
        }

        double rootY = bodyBob;
        double torsoLean = bodyLean;
        double leftLegOffset = 0.0;
        double rightLegOffset = 0.0;
        double leftArmOffset = 0.0;
        double rightArmOffset = 0.0;
        double headPitch = 0.0;
        double capeSwing = 5.0;
        SkinPreviewTransform root = SkinPreviewTransform.identity();

        switch (posture) {
            case STANDING -> {
                // The default pose uses the movement values unchanged.
            }
            case SNEAKING -> {
                rootY -= 2.6;
                torsoLean += 23.0;
                headPitch = -5.0;
                leftLegOffset = 13.0;
                rightLegOffset = 13.0;
                leftArmOffset = -5.0;
                rightArmOffset = -5.0;
                capeSwing = 12.0;
            }
            case RIDING -> {
                rootY -= 1.0;
                torsoLean = 4.0 + bodyLean * 0.25;
                headPitch = -3.0;
                leftLegOffset = -78.0;
                rightLegOffset = -78.0;
                leftArmOffset = -48.0;
                rightArmOffset = -48.0;
                capeSwing = 18.0;
            }
            case PRONE -> {
                rootY -= 1.0;
                root = root.multiply(SkinPreviewTransform.rotateZ(88.0));
                torsoLean = 6.0;
                headPitch = 8.0;
                leftLegOffset = Math.sin(phase) * 12.0;
                rightLegOffset = -Math.sin(phase) * 12.0;
                leftArmOffset = -Math.sin(phase) * 18.0;
                rightArmOffset = Math.sin(phase) * 18.0;
                capeSwing = 26.0;
            }
        }
        root = SkinPreviewTransform.translate(0.0, rootY, 0.0).multiply(root);

        SkinPreviewTransform torso = root.multiply(SkinPreviewTransform.around(
                new SkinPreviewTransform.Vector(0.0, 2.0, 0.0),
                SkinPreviewTransform.Axis.X,
                torsoLean));
        double headTurn = Math.sin(phase * 0.5) * 4.0;
        SkinPreviewTransform head = torso
                .multiply(SkinPreviewTransform.around(
                        new SkinPreviewTransform.Vector(0.0, 12.0, 0.0),
                        SkinPreviewTransform.Axis.X,
                        headPitch))
                .multiply(SkinPreviewTransform.around(
                        new SkinPreviewTransform.Vector(0.0, 12.0, 0.0),
                        SkinPreviewTransform.Axis.Y,
                        headTurn));

        int armWidth = model == TextureModel.SLIM ? 3 : 4;
        double armCenterX = 4.0 + armWidth / 2.0;
        SkinPreviewTransform.Axis limbAxis = posture == SkinPreviewPosture.PRONE
                ? SkinPreviewTransform.Axis.Z
                : SkinPreviewTransform.Axis.X;
        SkinPreviewTransform rightArm = torso.multiply(SkinPreviewTransform.around(
                new SkinPreviewTransform.Vector(armCenterX, 8.0, 0.0),
                limbAxis,
                armSwing + rightArmOffset + torsoLean * 0.35));
        SkinPreviewTransform leftArm = torso.multiply(SkinPreviewTransform.around(
                new SkinPreviewTransform.Vector(-armCenterX, 8.0, 0.0),
                limbAxis,
                -armSwing + leftArmOffset + torsoLean * 0.35));
        SkinPreviewTransform rightLeg = root.multiply(SkinPreviewTransform.around(
                new SkinPreviewTransform.Vector(2.0, -4.0, 0.0),
                limbAxis,
                legSwing + rightLegOffset));
        SkinPreviewTransform leftLeg = root.multiply(SkinPreviewTransform.around(
                new SkinPreviewTransform.Vector(-2.0, -4.0, 0.0),
                limbAxis,
                -legSwing + leftLegOffset));

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
                box(-2.0, -10.0, 0.0, 4.0, 12.0, 4.0, boxTexture(modern ? 16 : 0, modern ? 48 : 16, 4, 12, 4)),
                leftLeg,
                0));

        if (cape != null) {
            SkinPreviewTransform capeTransform = torso.multiply(SkinPreviewTransform.around(
                    new SkinPreviewTransform.Vector(0.0, 8.0, -4.0),
                    SkinPreviewTransform.Axis.X,
                    capeSwing + torsoLean * 0.2));
            parts.add(capePart(
                    box(0.0, 0.0, -4.5, 10.0, 16.0, 1.0, boxTexture(0, 0, 10, 16, 1)),
                    capeTransform));
        }
        if (modern) {
            parts.add(boxPart(
                    box(0.0, 12.0, 0.0, 9.0, 9.0, 9.0, boxTexture(32, 0, 8, 8, 8)),
                    head,
                    1));
            parts.add(boxPart(
                    box(0.0, 2.0, 0.0, 8.5, 12.5, 4.5, boxTexture(16, 32, 8, 12, 4)),
                    torso,
                    1));
            parts.add(boxPart(
                    box(armCenterX, 2.0, 0.0, armWidth + 0.5, 12.5, 4.5,
                            boxTexture(40, 32, armWidth, 12, 4)),
                    rightArm,
                    1));
            parts.add(boxPart(
                    box(-armCenterX, 2.0, 0.0, armWidth + 0.5, 12.5, 4.5,
                            boxTexture(48, 48, armWidth, 12, 4)),
                    leftArm,
                    1));
            parts.add(boxPart(
                    box(2.0, -10.0, 0.0, 4.5, 12.5, 4.5, boxTexture(0, 32, 4, 12, 4)),
                    rightLeg,
                    1));
            parts.add(boxPart(
                    box(-2.0, -10.0, 0.0, 4.5, 12.5, 4.5, boxTexture(16, 48, 4, 12, 4)),
                    leftLeg,
                    1));
        }
        return parts;
    }

    /// Transforms and back-face-culls all cuboid faces.
    ///
    /// @param parts model parts
    /// @param view camera rotation
    /// @return visible faces
    private static List<RawFace> transformFaces(
            List<BoxPart> parts,
            SkinPreviewTransform view,
            BufferedImage skin,
            @Nullable BufferedImage cape) {
        List<RawFace> faces = new ArrayList<>();
        for (BoxPart part : parts) {
            addBoxFaces(faces, part, view, skin, cape);
        }
        return faces;
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
    /// @param transform model-to-view transform
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

    /// Computes normalized projection bounds for automatic model fitting.
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
    private static void paintFace(Graphics2D paint, RawFace face, Projection projection) {
        double p0x = projection.x(face.p0().x());
        double p0y = projection.y(face.p0().y());
        double p1x = projection.x(face.p1().x());
        double p1y = projection.y(face.p1().y());
        double p2x = projection.x(face.p2().x());
        double p2y = projection.y(face.p2().y());
        double p3x = projection.x(face.p3().x());
        double p3y = projection.y(face.p3().y());
        Path2D.Double outline = new Path2D.Double();
        outline.moveTo(p0x, p0y);
        outline.lineTo(p1x, p1y);
        outline.lineTo(p2x, p2y);
        outline.lineTo(p3x, p3y);
        outline.closePath();

        ImageRegion imageRegion = imageRegion(face.image(), face.region());
        drawTexturedTriangle(
                paint,
                face.image(),
                imageRegion,
                p0x, p0y,
                p1x, p1y,
                p2x, p2y,
                false);
        drawTexturedTriangle(
                paint,
                face.image(),
                imageRegion,
                p0x, p0y,
                p2x, p2y,
                p3x, p3y,
                true);
        if (face.shade() < 1.0) {
            int alpha = (int) Math.round((1.0 - face.shade()) * 255.0);
            paint.setColor(new Color(0, 0, 0, Math.min(180, alpha)));
            paint.fill(outline);
        } else if (face.shade() > 1.0) {
            int alpha = (int) Math.round((face.shade() - 1.0) * 180.0);
            paint.setColor(new Color(255, 255, 255, Math.min(80, alpha)));
            paint.fill(outline);
        }
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

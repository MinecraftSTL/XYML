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
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
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

    /// Converts vanilla entity model space into this renderer's view space.
    ///
    /// Every model cuboid, pivot and offset is written in Minecraft's `ModelPart` space: positive Y points down, the
    /// player faces negative Z, the player's right side lies on negative X, the head top sits at Y -8 and the feet at
    /// Y 24. The view keeps positive Y up and positive Z toward the camera, so the conversion is the proper 180 degree
    /// turn about X that the vanilla entity renderer also applies, followed by centring the body on the origin. It
    /// never mirrors the model.
    private static final SkinPreviewTransform VANILLA_TO_VIEW = SkinPreviewTransform.translate(0.0, 8.0, 0.0)
            .multiply(SkinPreviewTransform.rotateX(180.0));

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
            if (posture == SkinPreviewPosture.STANDING || posture == SkinPreviewPosture.SNEAKING) {
                paintShadow(paint, bounds, projection);
            }
            rasterize(paint, faces, projection, width, height);
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
        double halfPeriod = halfPeriod(posture, motion);
        if (halfPeriod > 0.0) {
            faces.addAll(transformFaces(buildModel(model, motion, posture, halfPeriod, modern, cape), view, skin, cape));
        }
        return faces;
    }

    /// Resolves the exact 1.21 three-stage swimming animation, converted to this renderer's Y-up axes.
    ///
    /// @param phase limb swing modulo 26, as used by HumanoidModel
    /// @param limbSwing continuous limb swing used by the leg kick
    /// @return coordinate-converted swim joint angles
    private static SwimPose swimPose(double phase, double limbSwing) {
        double normalized = Math.floorMod((int) Math.floor(phase), 26) + phase - Math.floor(phase);
        double rightArmPitch;
        double leftArmPitch;
        double rightArmYaw = 180.0;
        double leftArmYaw = 180.0;
        double rightArmRoll;
        double leftArmRoll;
        if (normalized < 14.0) {
            rightArmPitch = 0.0;
            leftArmPitch = 0.0;
            double progress = normalized / 14.0;
            leftArmRoll = Math.toDegrees(Math.PI + 1.8707964 * progress);
            rightArmRoll = Math.toDegrees(Math.PI - 1.8707964 * progress);
        } else if (normalized < 22.0) {
            double progress = (normalized - 14.0) / 8.0;
            rightArmPitch = 90.0 * progress;
            leftArmPitch = 90.0 * progress;
            leftArmRoll = Math.toDegrees(5.012389 - 1.8707964 * progress);
            rightArmRoll = Math.toDegrees(1.2707963 + 1.8707964 * progress);
        } else {
            double progress = (normalized - 22.0) / 4.0;
            rightArmPitch = 90.0 * (1.0 - progress);
            leftArmPitch = 90.0 * (1.0 - progress);
            rightArmRoll = 180.0;
            leftArmRoll = 180.0;
        }
        double legSwing = 0.3;
        double rightLegPitch = Math.toDegrees(legSwing * Math.cos(limbSwing * 0.33333334));
        double leftLegPitch = Math.toDegrees(legSwing * Math.cos(limbSwing * 0.33333334 + Math.PI));
        return new SwimPose(
                rightArmPitch,
                leftArmPitch,
                rightArmYaw,
                leftArmYaw,
                rightArmRoll,
                leftArmRoll,
                rightLegPitch,
                leftLegPitch);
    }

    /// Returns the 1.21 horizontal movement amount used by limb swing.
    ///
    /// @param posture body posture
    /// @param motion effective movement cycle
    /// @return limb swing amount
    private static double limbAmount(SkinPreviewPosture posture, SkinPreviewMotion motion) {
        if (posture == SkinPreviewPosture.SNEAKING && motion != SkinPreviewMotion.IDLE) {
            return 0.12;
        }
        return switch (motion) {
            case IDLE -> 0.0;
            case WALKING -> 0.4;
            case SPRINTING -> 0.52;
        };
    }

    /// Returns one half limb-swing period for stable framing samples.
    ///
    /// @param posture body posture
    /// @param motion requested movement cycle
    /// @return half period in seconds, or zero for idle
    private static double halfPeriod(SkinPreviewPosture posture, SkinPreviewMotion motion) {
        SkinPreviewMotion effectiveMotion = motion == SkinPreviewMotion.SPRINTING
                && posture != SkinPreviewPosture.STANDING
                ? SkinPreviewMotion.WALKING
                : motion;
        double amount = limbAmount(posture, effectiveMotion);
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
        double limbAmount = limbAmount(posture, effectiveMotion);
        double limbSwing = seconds * 20.0 * limbAmount;
        double limbPhase = limbSwing * 0.6662;
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

        double bodyPitch = 0.0;
        double bodyYOffset = 0.0;
        double headPitch = 0.0;
        double headYOffset = 0.0;
        double armYOffset = 0.0;
        double legYOffset = 0.0;
        double legZOffset = 0.0;
        double armPitchOffset = 0.0;
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
                bodyYOffset = 3.2;
                headYOffset = 4.2;
                armYOffset = 3.2;
                legYOffset = 0.2;
                legZOffset = 4.0;
                armPitchOffset = 22.9183;
                capePitch += 10.0;
            }
            case RIDING -> {
                rightLegPitch = -81.0289;
                leftLegPitch = -81.0289;
                rightLegYaw = 18.0;
                leftLegYaw = -18.0;
                rightLegRoll = 4.5;
                leftLegRoll = -4.5;
                armPitchOffset = -36.0;
                capePitch += 10.0;
            }
            case SWIMMING -> {
                root = SkinPreviewTransform.around(
                        new SkinPreviewTransform.Vector(0.0, 8.0, 0.0),
                        SkinPreviewTransform.Axis.X,
                        90.0);
                SwimPose swim = swimPose(limbSwing % 26.0, limbSwing);
                rightArmPitch = swim.rightArmPitch();
                leftArmPitch = swim.leftArmPitch();
                rightArmYaw = swim.rightArmYaw();
                leftArmYaw = swim.leftArmYaw();
                rightArmRoll = swim.rightArmRoll();
                leftArmRoll = swim.leftArmRoll();
                rightLegPitch = swim.rightLegPitch();
                leftLegPitch = swim.leftLegPitch();
                capePitch = 18.0;
            }
        }
        rightArmPitch += armPitchOffset;
        leftArmPitch += armPitchOffset;

        // Joint pivots and cuboids below are the vanilla PlayerModel values, in vanilla model space.
        SkinPreviewTransform torso = joint(root, 0.0, bodyYOffset, 0.0, bodyPitch, 0.0, 0.0);
        SkinPreviewTransform head = joint(root, 0.0, headYOffset, 0.0, headPitch, 0.0, 0.0);
        int armWidth = model == TextureModel.SLIM ? 3 : 4;
        double armOffset = model == TextureModel.SLIM ? 0.5 : 0.0;
        SkinPreviewTransform rightArm = joint(
                root, -5.0, 2.0 + armYOffset, 0.0, rightArmPitch, rightArmYaw, rightArmRoll);
        SkinPreviewTransform leftArm = joint(
                root, 5.0, 2.0 + armYOffset, 0.0, leftArmPitch, leftArmYaw, leftArmRoll);
        SkinPreviewTransform rightLeg = joint(
                root, -1.9, 12.0 + legYOffset, legZOffset, rightLegPitch, rightLegYaw, rightLegRoll);
        SkinPreviewTransform leftLeg = joint(
                root, 1.9, 12.0 + legYOffset, legZOffset, leftLegPitch, leftLegYaw, leftLegRoll);

        List<BoxPart> parts = new ArrayList<>();
        parts.add(boxPart(
                box(-4.0, -8.0, -4.0, 8.0, 8.0, 8.0, 0.0, false, boxTexture(0, 0, 8, 8, 8)),
                head,
                0));
        parts.add(boxPart(
                box(-4.0, 0.0, -2.0, 8.0, 12.0, 4.0, 0.0, false, boxTexture(16, 16, 8, 12, 4)),
                torso,
                0));
        parts.add(boxPart(
                box(-3.0 - armOffset, -2.0, -2.0, armWidth, 12.0, 4.0, 0.0, false,
                        boxTexture(40, 16, armWidth, 12, 4)),
                rightArm,
                0));
        // Legacy 64x32 skins reuse the right limb artwork, mirrored, for the left limbs.
        parts.add(boxPart(
                box(-1.0, -2.0, -2.0, armWidth, 12.0, 4.0, 0.0, !modern,
                        boxTexture(modern ? 32 : 40, modern ? 48 : 16, armWidth, 12, 4)),
                leftArm,
                0));
        parts.add(boxPart(
                box(-2.0, 0.0, -2.0, 4.0, 12.0, 4.0, 0.0, false, boxTexture(0, 16, 4, 12, 4)),
                rightLeg,
                0));
        parts.add(boxPart(
                box(-2.0, 0.0, -2.0, 4.0, 12.0, 4.0, 0.0, !modern,
                        boxTexture(modern ? 16 : 0, modern ? 48 : 16, 4, 12, 4)),
                leftLeg,
                0));
        parts.add(boxPart(
                box(-4.0, -8.0, -4.0, 8.0, 8.0, 8.0, 0.5, false, boxTexture(32, 0, 8, 8, 8)),
                head,
                1));
        if (modern) {
            parts.add(boxPart(
                    box(-4.0, 0.0, -2.0, 8.0, 12.0, 4.0, 0.25, false, boxTexture(16, 32, 8, 12, 4)),
                    torso,
                    1));
            parts.add(boxPart(
                    box(-3.0 - armOffset, -2.0, -2.0, armWidth, 12.0, 4.0, 0.25, false,
                            boxTexture(40, 32, armWidth, 12, 4)),
                    rightArm,
                    1));
            parts.add(boxPart(
                    box(-1.0, -2.0, -2.0, armWidth, 12.0, 4.0, 0.25, false, boxTexture(48, 48, armWidth, 12, 4)),
                    leftArm,
                    1));
            parts.add(boxPart(
                    box(-2.0, 0.0, -2.0, 4.0, 12.0, 4.0, 0.25, false, boxTexture(0, 32, 4, 12, 4)),
                    rightLeg,
                    1));
            parts.add(boxPart(
                    box(-2.0, 0.0, -2.0, 4.0, 12.0, 4.0, 0.25, false, boxTexture(0, 48, 4, 12, 4)),
                    leftLeg,
                    1));
        }

        if (cape != null) {
            // CapeLayer: hang from the shoulders two pixels behind the body, then turn the cloak 180 degrees about Y.
            SkinPreviewTransform capeTransform = torso
                    .multiply(SkinPreviewTransform.translate(0.0, 0.0, 2.0))
                    .multiply(SkinPreviewTransform.rotateX(capePitch + bodyPitch * 0.2))
                    .multiply(SkinPreviewTransform.rotateY(180.0));
            parts.add(capePart(
                    box(-5.0, 0.0, -1.0, 10.0, 16.0, 1.0, 0.0, false, boxTexture(0, 0, 10, 16, 1)),
                    capeTransform));
        }
        return new Model(List.copyOf(parts));
    }

    /// Builds one vanilla `ModelPart` joint transform.
    ///
    /// The cuboids of the part are given relative to its pivot, which is translated in the parent space and then
    /// rotated in the `ModelPart#translateAndRotate` order: roll about Z, yaw about Y and pitch about X, with pitch
    /// applied to the geometry first.
    ///
    /// @param parent parent transform
    /// @param pivotX pivot X in parent space
    /// @param pivotY pivot Y in parent space
    /// @param pivotZ pivot Z in parent space
    /// @param pitch X rotation in degrees
    /// @param yaw Y rotation in degrees
    /// @param roll Z rotation in degrees
    /// @return joint transform
    private static SkinPreviewTransform joint(
            SkinPreviewTransform parent,
            double pivotX,
            double pivotY,
            double pivotZ,
            double pitch,
            double yaw,
            double roll) {
        return parent
                .multiply(SkinPreviewTransform.translate(pivotX, pivotY, pivotZ))
                .multiply(SkinPreviewTransform.rotateZ(roll))
                .multiply(SkinPreviewTransform.rotateY(yaw))
                .multiply(SkinPreviewTransform.rotateX(pitch));
    }

    /// Transforms and back-face-culls all cuboid faces.
    ///
    /// @param scene vanilla-space model
    /// @param view camera rotation
    /// @param skin decoded player texture
    /// @param cape decoded cape texture, or null
    /// @return visible faces
    private static List<RawFace> transformFaces(
            Model scene,
            SkinPreviewTransform view,
            BufferedImage skin,
            @Nullable BufferedImage cape) {
        SkinPreviewTransform modelView = view.multiply(VANILLA_TO_VIEW);
        List<RawFace> faces = new ArrayList<>();
        for (BoxPart part : scene.parts()) {
            addBoxFaces(faces, part, modelView, skin, cape);
        }
        return faces;
    }

    /// Adds the visible faces of one transformed cuboid.
    ///
    /// Follows `ModelPart.Cube`: the cuboid is dilated by its grow amount without changing its texture size, and a
    /// mirrored cuboid swaps its X extents so each face samples its atlas region horizontally reversed. Face names
    /// follow the player, so the front face lies on negative Z and the right face on negative X.
    ///
    /// @param faces destination list
    /// @param part model part
    /// @param modelView vanilla model space to camera transform
    /// @param skin decoded player texture
    /// @param cape decoded cape texture, or null
    private static void addBoxFaces(
            List<RawFace> faces,
            BoxPart part,
            SkinPreviewTransform modelView,
            BufferedImage skin,
            @Nullable BufferedImage cape) {
        Box box = part.box();
        BufferedImage image = part.cape() ? Objects.requireNonNull(cape, "cape") : skin;
        double minX = box.x() - box.grow();
        double maxX = box.x() + box.width() + box.grow();
        double minY = box.y() - box.grow();
        double maxY = box.y() + box.height() + box.grow();
        double minZ = box.z() - box.grow();
        double maxZ = box.z() + box.depth() + box.grow();
        double rightX = box.mirror() ? maxX : minX;
        double leftX = box.mirror() ? minX : maxX;
        double rightNormal = box.mirror() ? 1.0 : -1.0;
        SkinPreviewTransform transform = modelView.multiply(part.transform());
        BoxTexture texture = box.texture();
        int layer = part.layer();

        addFace(
                faces,
                new SkinPreviewTransform.Vector(rightX, minY, minZ),
                new SkinPreviewTransform.Vector(leftX, minY, minZ),
                new SkinPreviewTransform.Vector(leftX, maxY, minZ),
                new SkinPreviewTransform.Vector(rightX, maxY, minZ),
                new SkinPreviewTransform.Vector(0.0, 0.0, -1.0),
                texture.front(),
                1.0,
                layer,
                transform,
                image);
        addFace(
                faces,
                new SkinPreviewTransform.Vector(leftX, minY, maxZ),
                new SkinPreviewTransform.Vector(rightX, minY, maxZ),
                new SkinPreviewTransform.Vector(rightX, maxY, maxZ),
                new SkinPreviewTransform.Vector(leftX, maxY, maxZ),
                new SkinPreviewTransform.Vector(0.0, 0.0, 1.0),
                texture.back(),
                0.78,
                layer,
                transform,
                image);
        addFace(
                faces,
                new SkinPreviewTransform.Vector(rightX, minY, maxZ),
                new SkinPreviewTransform.Vector(rightX, minY, minZ),
                new SkinPreviewTransform.Vector(rightX, maxY, minZ),
                new SkinPreviewTransform.Vector(rightX, maxY, maxZ),
                new SkinPreviewTransform.Vector(rightNormal, 0.0, 0.0),
                texture.right(),
                0.88,
                layer,
                transform,
                image);
        addFace(
                faces,
                new SkinPreviewTransform.Vector(leftX, minY, minZ),
                new SkinPreviewTransform.Vector(leftX, minY, maxZ),
                new SkinPreviewTransform.Vector(leftX, maxY, maxZ),
                new SkinPreviewTransform.Vector(leftX, maxY, minZ),
                new SkinPreviewTransform.Vector(-rightNormal, 0.0, 0.0),
                texture.left(),
                0.88,
                layer,
                transform,
                image);
        addFace(
                faces,
                new SkinPreviewTransform.Vector(rightX, minY, maxZ),
                new SkinPreviewTransform.Vector(leftX, minY, maxZ),
                new SkinPreviewTransform.Vector(leftX, minY, minZ),
                new SkinPreviewTransform.Vector(rightX, minY, minZ),
                new SkinPreviewTransform.Vector(0.0, -1.0, 0.0),
                texture.top(),
                1.05,
                layer,
                transform,
                image);
        addFace(
                faces,
                new SkinPreviewTransform.Vector(rightX, maxY, maxZ),
                new SkinPreviewTransform.Vector(leftX, maxY, maxZ),
                new SkinPreviewTransform.Vector(leftX, maxY, minZ),
                new SkinPreviewTransform.Vector(rightX, maxY, minZ),
                new SkinPreviewTransform.Vector(0.0, 1.0, 0.0),
                texture.bottom(),
                0.68,
                layer,
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

    /// Rasterizes base geometry and outer layers through a CPU depth buffer.
    ///
    /// @param paint destination graphics
    /// @param faces projected faces
    /// @param projection screen projection
    /// @param width destination width
    /// @param height destination height
    private static void rasterize(
            Graphics2D paint,
            List<RawFace> faces,
            Projection projection,
            int width,
            int height) {
        int[] pixels = new int[width * height];
        double[] depth = new double[pixels.length];
        Arrays.fill(depth, Double.POSITIVE_INFINITY);

        List<RawFace> baseFaces = new ArrayList<>();
        List<RawFace> outerFaces = new ArrayList<>();
        for (RawFace face : faces) {
            (face.layer() == 1 ? outerFaces : baseFaces).add(face);
        }
        baseFaces.sort(Comparator.comparingDouble(RawFace::depth).reversed());
        outerFaces.sort(Comparator.comparingDouble(RawFace::depth).reversed());
        for (RawFace face : baseFaces) {
            rasterizeFace(face, projection, pixels, depth, width, height);
        }
        for (RawFace face : outerFaces) {
            rasterizeFace(face, projection, pixels, depth, width, height);
        }

        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        output.setRGB(0, 0, width, height, pixels, 0, width);
        paint.drawImage(output, 0, 0, null);
    }

    /// Rasterizes both triangles of one projected face.
    ///
    /// @param face projected face
    /// @param projection screen projection
    /// @param pixels destination ARGB pixels
    /// @param depth destination depth buffer
    /// @param width destination width
    /// @param height destination height
    private static void rasterizeFace(
            RawFace face,
            Projection projection,
            int[] pixels,
            double[] depth,
            int width,
            int height) {
        BufferedImage image = Objects.requireNonNull(face.image(), "image");
        TextureRegion region = Objects.requireNonNull(face.region(), "region");
        ImageRegion source = imageRegion(image, region);
        ScreenVertex v0 = screenVertex(face.p0(), projection, 0.0, 0.0);
        ScreenVertex v1 = screenVertex(face.p1(), projection, 1.0, 0.0);
        ScreenVertex v2 = screenVertex(face.p2(), projection, 1.0, 1.0);
        ScreenVertex v3 = screenVertex(face.p3(), projection, 0.0, 1.0);
        rasterizeTriangle(v0, v1, v2, image, source, face.shade(), pixels, depth, width, height);
        rasterizeTriangle(v0, v2, v3, image, source, face.shade(), pixels, depth, width, height);
    }

    /// Creates one screen-space vertex with UV coordinates.
    ///
    /// @param point projected point
    /// @param projection screen projection
    /// @param u texture U
    /// @param v texture V
    /// @return screen vertex
    private static ScreenVertex screenVertex(RawPoint point, Projection projection, double u, double v) {
        return new ScreenVertex(projection.x(point.x()), projection.y(point.y()), point.depth(), u, v);
    }

    /// Rasterizes one textured triangle with perspective-correct UV interpolation.
    ///
    /// @param v0 first vertex
    /// @param v1 second vertex
    /// @param v2 third vertex
    /// @param image source texture
    /// @param source source image region
    /// @param shade face brightness multiplier
    /// @param pixels destination ARGB pixels
    /// @param depth destination depth buffer
    /// @param width destination width
    /// @param height destination height
    private static void rasterizeTriangle(
            ScreenVertex v0,
            ScreenVertex v1,
            ScreenVertex v2,
            BufferedImage image,
            ImageRegion source,
            double shade,
            int[] pixels,
            double[] depth,
            int width,
            int height) {
        double area = edge(v0.x(), v0.y(), v1.x(), v1.y(), v2.x(), v2.y());
        if (Math.abs(area) < 0.000001) {
            return;
        }
        int minX = Math.max(0, (int) Math.floor(Math.min(v0.x(), Math.min(v1.x(), v2.x()))));
        int maxX = Math.min(width - 1, (int) Math.ceil(Math.max(v0.x(), Math.max(v1.x(), v2.x()))));
        int minY = Math.max(0, (int) Math.floor(Math.min(v0.y(), Math.min(v1.y(), v2.y()))));
        int maxY = Math.min(height - 1, (int) Math.ceil(Math.max(v0.y(), Math.max(v1.y(), v2.y()))));
        double invW0 = 1.0 / v0.depth();
        double invW1 = 1.0 / v1.depth();
        double invW2 = 1.0 / v2.depth();
        for (int y = minY; y <= maxY; ++y) {
            double py = y + 0.5;
            for (int x = minX; x <= maxX; ++x) {
                double px = x + 0.5;
                double w0 = edge(v1.x(), v1.y(), v2.x(), v2.y(), px, py) / area;
                double w1 = edge(v2.x(), v2.y(), v0.x(), v0.y(), px, py) / area;
                double w2 = edge(v0.x(), v0.y(), v1.x(), v1.y(), px, py) / area;
                if (w0 < -0.000001 || w1 < -0.000001 || w2 < -0.000001) {
                    continue;
                }
                double oneOverW = w0 * invW0 + w1 * invW1 + w2 * invW2;
                if (oneOverW <= 0.0) {
                    continue;
                }
                double pixelDepth = 1.0 / oneOverW;
                int index = y * width + x;
                if (pixelDepth >= depth[index]) {
                    continue;
                }
                double u = (w0 * v0.u() * invW0 + w1 * v1.u() * invW1 + w2 * v2.u() * invW2) / oneOverW;
                double v = (w0 * v0.v() * invW0 + w1 * v1.v() * invW1 + w2 * v2.v() * invW2) / oneOverW;
                double sourceU = source.mirrored() ? 1.0 - u : u;
                int sourceX = Math.min(image.getWidth() - 1, Math.max(0, (int) (source.x() + sourceU * source.width())));
                int sourceY = Math.min(image.getHeight() - 1, Math.max(0, (int) (source.y() + v * source.height())));
                int argb = image.getRGB(sourceX, sourceY);
                int alpha = argb >>> 24;
                if (alpha == 0) {
                    continue;
                }
                int red = Math.min(255, (int) Math.round(((argb >> 16) & 0xFF) * shade));
                int green = Math.min(255, (int) Math.round(((argb >> 8) & 0xFF) * shade));
                int blue = Math.min(255, (int) Math.round((argb & 0xFF) * shade));
                pixels[index] = blend(pixels[index], alpha, red, green, blue);
                depth[index] = pixelDepth;
            }
        }
    }

    /// Computes a triangle edge function.
    ///
    /// @param ax first point X
    /// @param ay first point Y
    /// @param bx second point X
    /// @param by second point Y
    /// @param px test point X
    /// @param py test point Y
    /// @return signed edge value
    private static double edge(double ax, double ay, double bx, double by, double px, double py) {
        return (px - ax) * (by - ay) - (py - ay) * (bx - ax);
    }

    /// Alpha-blends one source pixel over one destination pixel.
    ///
    /// @param destination destination ARGB
    /// @param sourceAlpha source alpha
    /// @param sourceRed source red
    /// @param sourceGreen source green
    /// @param sourceBlue source blue
    /// @return blended ARGB
    private static int blend(int destination, int sourceAlpha, int sourceRed, int sourceGreen, int sourceBlue) {
        int destinationAlpha = destination >>> 24;
        int inverseAlpha = 255 - sourceAlpha;
        int outputAlpha = sourceAlpha + destinationAlpha * inverseAlpha / 255;
        if (outputAlpha == 0) {
            return 0;
        }
        int destinationRed = (destination >> 16) & 0xFF;
        int destinationGreen = (destination >> 8) & 0xFF;
        int destinationBlue = destination & 0xFF;
        int outputRed = (sourceRed * sourceAlpha + destinationRed * destinationAlpha * inverseAlpha / 255) / outputAlpha;
        int outputGreen = (sourceGreen * sourceAlpha + destinationGreen * destinationAlpha * inverseAlpha / 255) / outputAlpha;
        int outputBlue = (sourceBlue * sourceAlpha + destinationBlue * destinationAlpha * inverseAlpha / 255) / outputAlpha;
        return outputAlpha << 24 | outputRed << 16 | outputGreen << 8 | outputBlue;
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
        return new ImageRegion(x, y, Math.max(1.0, x2 - x), Math.max(1.0, y2 - y), region.mirrored());
    }

    /// Creates one cuboid definition.
    ///
    /// @param x minimum X
    /// @param y minimum Y
    /// @param z minimum Z
    /// @param width X size
    /// @param height Y size
    /// @param depth Z size
    /// @param grow uniform dilation applied before projection
    /// @param mirror whether the X extent is mirrored for legacy skin artwork
    /// @param texture six-face texture mapping
    /// @return cuboid definition
    private static Box box(
            double x,
            double y,
            double z,
            double width,
            double height,
            double depth,
            double grow,
            boolean mirror,
            BoxTexture texture) {
        return new Box(x, y, z, width, height, depth, grow, mirror, texture);
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

    /// Creates the atlas mapping used by the official cape texture.
    ///
    /// The cape cuboid is one pixel deep, so only the two broad faces carry the 10x16 cape artwork and the remaining
    /// faces sample the thin perimeter strips of the same region.
    ///
    /// @return official cape six-face texture mapping
    private static BoxTexture capeTexture() {
        TextureRegion face = new TextureRegion(1, 1, 10, 16);
        return new BoxTexture(
                new TextureRegion(1, 1, 10, 1),
                new TextureRegion(1, 16, 10, 1),
                new TextureRegion(10, 1, 1, 16),
                face,
                new TextureRegion(1, 1, 1, 16),
                face);
    }

    /// One textured cuboid definition.
    ///
    /// @param x minimum X
    /// @param y minimum Y
    /// @param z minimum Z
    /// @param width X size
    /// @param height Y size
    /// @param depth Z size
    /// @param grow uniform dilation applied before projection
    /// @param mirror whether the X extent is mirrored for legacy skin artwork
    /// @param texture six-face texture mapping
    @NotNullByDefault
    private record Box(
            double x,
            double y,
            double z,
            double width,
            double height,
            double depth,
            double grow,
            boolean mirror,
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
    /// @param parts textured base, outer-layer, and cape cuboids
    @NotNullByDefault
    private record Model(
            List<BoxPart> parts) {
        /// Validates and freezes the complete model.
        private Model {
            parts = List.copyOf(parts);
        }
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
    /// @param mirrored whether horizontal sampling is reversed
    @NotNullByDefault
    private record TextureRegion(int x, int y, int width, int height, boolean mirrored) {
        /// Creates one non-mirrored texture region.
        ///
        /// @param x source X
        /// @param y source Y
        /// @param width source width
        /// @param height source height
        private TextureRegion(int x, int y, int width, int height) {
            this(x, y, width, height, false);
        }

        /// Returns a horizontally mirrored copy of this region.
        ///
        /// @return mirrored region
        private TextureRegion mirroredCopy() {
            return new TextureRegion(x, y, width, height, !mirrored);
        }
    }

    /// Scalar source rectangle in actual texture pixels.
    ///
    /// @param x source X
    /// @param y source Y
    /// @param width source width
    /// @param height source height
    /// @param mirrored whether horizontal sampling is reversed
    @NotNullByDefault
    private record ImageRegion(double x, double y, double width, double height, boolean mirrored) {
    }

    /// One normalized projected point.
    ///
    /// @param x normalized horizontal coordinate
    /// @param y normalized vertical coordinate
    /// @param depth camera depth
    @NotNullByDefault
    private record RawPoint(double x, double y, double depth) {
    }

    /// Coordinate-converted 1.21 swimming pose.
    ///
    /// @param rightArmPitch right arm X rotation
    /// @param leftArmPitch left arm X rotation
    /// @param rightArmYaw right arm Y rotation
    /// @param leftArmYaw left arm Y rotation
    /// @param rightArmRoll right arm Z rotation
    /// @param leftArmRoll left arm Z rotation
    /// @param rightLegPitch right leg X rotation
    /// @param leftLegPitch left leg X rotation
    @NotNullByDefault
    private record SwimPose(
            double rightArmPitch,
            double leftArmPitch,
            double rightArmYaw,
            double leftArmYaw,
            double rightArmRoll,
            double leftArmRoll,
            double rightLegPitch,
            double leftLegPitch) {
    }

    /// One screen-space vertex used by the software rasterizer.
    ///
    /// @param x screen X
    /// @param y screen Y
    /// @param depth camera depth
    /// @param u texture U
    /// @param v texture V
    @NotNullByDefault
    private record ScreenVertex(double x, double y, double depth, double u, double v) {
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

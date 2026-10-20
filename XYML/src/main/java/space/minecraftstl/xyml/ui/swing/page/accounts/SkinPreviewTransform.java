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

import java.util.Objects;

/// Immutable affine transform used by the dependency-free skin preview renderer.
/// The matrix uses column vectors and a right-handed coordinate system with positive X to the right, positive Y up,
/// and positive Z toward the viewer.
@NotNullByDefault
final class SkinPreviewTransform {
    /// Identity matrix element at row 0, column 0.
    private final double m00;

    /// Matrix element at row 0, column 1.
    private final double m01;

    /// Matrix element at row 0, column 2.
    private final double m02;

    /// X translation.
    private final double tx;

    /// Matrix element at row 1, column 0.
    private final double m10;

    /// Matrix element at row 1, column 1.
    private final double m11;

    /// Matrix element at row 1, column 2.
    private final double m12;

    /// Y translation.
    private final double ty;

    /// Matrix element at row 2, column 0.
    private final double m20;

    /// Matrix element at row 2, column 1.
    private final double m21;

    /// Matrix element at row 2, column 2.
    private final double m22;

    /// Z translation.
    private final double tz;

    /// Creates one immutable affine transform.
    private SkinPreviewTransform(
            double m00,
            double m01,
            double m02,
            double tx,
            double m10,
            double m11,
            double m12,
            double ty,
            double m20,
            double m21,
            double m22,
            double tz) {
        this.m00 = m00;
        this.m01 = m01;
        this.m02 = m02;
        this.tx = tx;
        this.m10 = m10;
        this.m11 = m11;
        this.m12 = m12;
        this.ty = ty;
        this.m20 = m20;
        this.m21 = m21;
        this.m22 = m22;
        this.tz = tz;
    }

    /// Returns the identity transform.
    ///
    /// @return identity transform
    static SkinPreviewTransform identity() {
        return new SkinPreviewTransform(
                1.0, 0.0, 0.0, 0.0,
                0.0, 1.0, 0.0, 0.0,
                0.0, 0.0, 1.0, 0.0);
    }

    /// Creates a translation transform.
    ///
    /// @param x X offset
    /// @param y Y offset
    /// @param z Z offset
    /// @return translation transform
    static SkinPreviewTransform translate(double x, double y, double z) {
        return new SkinPreviewTransform(
                1.0, 0.0, 0.0, x,
                0.0, 1.0, 0.0, y,
                0.0, 0.0, 1.0, z);
    }

    /// Creates a rotation about the X axis.
    ///
    /// @param degrees rotation in degrees
    /// @return rotation transform
    static SkinPreviewTransform rotateX(double degrees) {
        double radians = Math.toRadians(degrees);
        double cosine = Math.cos(radians);
        double sine = Math.sin(radians);
        return new SkinPreviewTransform(
                1.0, 0.0, 0.0, 0.0,
                0.0, cosine, -sine, 0.0,
                0.0, sine, cosine, 0.0);
    }

    /// Creates a rotation about the Y axis.
    ///
    /// @param degrees rotation in degrees
    /// @return rotation transform
    static SkinPreviewTransform rotateY(double degrees) {
        double radians = Math.toRadians(degrees);
        double cosine = Math.cos(radians);
        double sine = Math.sin(radians);
        return new SkinPreviewTransform(
                cosine, 0.0, sine, 0.0,
                0.0, 1.0, 0.0, 0.0,
                -sine, 0.0, cosine, 0.0);
    }

    /// Creates a rotation about the Z axis.
    ///
    /// @param degrees rotation in degrees
    /// @return rotation transform
    static SkinPreviewTransform rotateZ(double degrees) {
        double radians = Math.toRadians(degrees);
        double cosine = Math.cos(radians);
        double sine = Math.sin(radians);
        return new SkinPreviewTransform(
                cosine, -sine, 0.0, 0.0,
                sine, cosine, 0.0, 0.0,
                0.0, 0.0, 1.0, 0.0);
    }

    /// Creates a rotation about one local pivot.
    ///
    /// @param pivot rotation center
    /// @param axis rotation axis
    /// @param degrees rotation in degrees
    /// @return pivot rotation transform
    static SkinPreviewTransform around(
            Vector pivot,
            Axis axis,
            double degrees) {
        Objects.requireNonNull(pivot, "pivot");
        Objects.requireNonNull(axis, "axis");
        SkinPreviewTransform rotation = switch (axis) {
            case X -> rotateX(degrees);
            case Y -> rotateY(degrees);
            case Z -> rotateZ(degrees);
        };
        return translate(pivot.x(), pivot.y(), pivot.z())
                .multiply(rotation)
                .multiply(translate(-pivot.x(), -pivot.y(), -pivot.z()));
    }

    /// Composes this transform with another transform.
    ///
    /// The returned transform applies the argument first and this transform second.
    ///
    /// @param other transform applied first
    /// @return composed transform
    SkinPreviewTransform multiply(SkinPreviewTransform other) {
        Objects.requireNonNull(other, "other");
        return new SkinPreviewTransform(
                m00 * other.m00 + m01 * other.m10 + m02 * other.m20,
                m00 * other.m01 + m01 * other.m11 + m02 * other.m21,
                m00 * other.m02 + m01 * other.m12 + m02 * other.m22,
                m00 * other.tx + m01 * other.ty + m02 * other.tz + tx,
                m10 * other.m00 + m11 * other.m10 + m12 * other.m20,
                m10 * other.m01 + m11 * other.m11 + m12 * other.m21,
                m10 * other.m02 + m11 * other.m12 + m12 * other.m22,
                m10 * other.tx + m11 * other.ty + m12 * other.tz + ty,
                m20 * other.m00 + m21 * other.m10 + m22 * other.m20,
                m20 * other.m01 + m21 * other.m11 + m22 * other.m21,
                m20 * other.m02 + m21 * other.m12 + m22 * other.m22,
                m20 * other.tx + m21 * other.ty + m22 * other.tz + tz);
    }

    /// Transforms one position.
    ///
    /// @param point source position
    /// @return transformed position
    Vector apply(Vector point) {
        Objects.requireNonNull(point, "point");
        return new Vector(
                m00 * point.x() + m01 * point.y() + m02 * point.z() + tx,
                m10 * point.x() + m11 * point.y() + m12 * point.z() + ty,
                m20 * point.x() + m21 * point.y() + m22 * point.z() + tz);
    }

    /// Transforms one direction without translation.
    ///
    /// @param direction source direction
    /// @return transformed direction
    Vector applyDirection(Vector direction) {
        Objects.requireNonNull(direction, "direction");
        return new Vector(
                m00 * direction.x() + m01 * direction.y() + m02 * direction.z(),
                m10 * direction.x() + m11 * direction.y() + m12 * direction.z(),
                m20 * direction.x() + m21 * direction.y() + m22 * direction.z());
    }

    /// Cartesian position or direction used by the software renderer.
    ///
    /// @param x X component
    /// @param y Y component
    /// @param z Z component
    @NotNullByDefault
    record Vector(double x, double y, double z) {
        /// Adds two vectors.
        ///
        /// @param other vector to add
        /// @return sum
        Vector add(Vector other) {
            Objects.requireNonNull(other, "other");
            return new Vector(x + other.x, y + other.y, z + other.z);
        }

        /// Subtracts one vector from this vector.
        ///
        /// @param other vector to subtract
        /// @return difference
        Vector subtract(Vector other) {
            Objects.requireNonNull(other, "other");
            return new Vector(x - other.x, y - other.y, z - other.z);
        }

        /// Multiplies all components by one factor.
        ///
        /// @param factor scalar factor
        /// @return scaled vector
        Vector multiply(double factor) {
            return new Vector(x * factor, y * factor, z * factor);
        }

        /// Computes the dot product with another vector.
        ///
        /// @param other other vector
        /// @return dot product
        double dot(Vector other) {
            Objects.requireNonNull(other, "other");
            return x * other.x + y * other.y + z * other.z;
        }

        /// Computes the length of this vector.
        ///
        /// @return Euclidean length
        double length() {
            return Math.sqrt(x * x + y * y + z * z);
        }
    }

    /// Rotation axis used by [#around(Vector, Axis, double)].
    @NotNullByDefault
    enum Axis {
        /// X axis.
        X,

        /// Y axis.
        Y,

        /// Z axis.
        Z
    }
}

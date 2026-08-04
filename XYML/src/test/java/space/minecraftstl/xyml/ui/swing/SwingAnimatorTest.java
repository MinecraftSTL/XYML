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
package space.minecraftstl.xyml.ui.swing;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import space.minecraftstl.xyml.setting.AnimationSpeedSettings;

import javax.swing.SwingUtilities;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests policy, cancellation, easing, monotonic progress, and scheduled EDT frame delivery.
@NotNullByDefault
public final class SwingAnimatorTest {
    /// Off policy applies only the final frame and reports normal completion.
    @Test
    public void offPolicyCompletesImmediately() {
        SwingAnimator animator = new SwingAnimator(MotionPolicy.OFF, 10_000);
        List<Double> frames = new ArrayList<>();
        AtomicBoolean completionCalled = new AtomicBoolean();

        AnimationHandle handle = animator.animate(
                Duration.ofSeconds(2),
                MotionPurpose.ESSENTIAL,
                Easing.LINEAR,
                frames::add,
                () -> completionCalled.set(true));

        assertAll(
                () -> assertEquals(List.of(1.0), frames),
                () -> assertTrue(completionCalled.get()),
                () -> assertTrue(handle.isFinished()),
                () -> assertFalse(handle.isRunning()),
                () -> assertFalse(handle.isCancelled()));
    }

    /// Reduced motion suppresses decorative animation but leaves essential animation cancelable.
    @Test
    public void reducedPolicyDistinguishesMotionPurpose() {
        SwingAnimator animator = new SwingAnimator(MotionPolicy.REDUCED, 10_000);
        List<Double> decorativeFrames = new ArrayList<>();
        List<Double> essentialFrames = new ArrayList<>();

        AnimationHandle decorative = animator.animate(
                Duration.ofSeconds(2),
                MotionPurpose.DECORATIVE,
                Easing.LINEAR,
                decorativeFrames::add,
                () -> { });
        AnimationHandle essential = animator.animate(
                Duration.ofSeconds(2),
                MotionPurpose.ESSENTIAL,
                Easing.LINEAR,
                essentialFrames::add,
                () -> { });
        essential.cancel();

        assertAll(
                () -> assertEquals(List.of(1.0), decorativeFrames),
                () -> assertTrue(decorative.isFinished()),
                () -> assertEquals(List.of(0.0), essentialFrames),
                () -> assertTrue(essential.isCancelled()),
                () -> assertFalse(essential.isRunning()));
    }

    /// Disabling motion while an animation is active moves it to the final state instead of leaving a partial visual value.
    @Test
    public void policyChangeFinishesDisallowedActiveAnimation() {
        SwingAnimator animator = new SwingAnimator(MotionPolicy.FULL, 10_000);
        List<Double> frames = new ArrayList<>();
        AtomicBoolean completionCalled = new AtomicBoolean();

        AnimationHandle handle = animator.animate(
                Duration.ofSeconds(2),
                MotionPurpose.DECORATIVE,
                Easing.LINEAR,
                frames::add,
                () -> completionCalled.set(true));
        animator.setMotionPolicy(MotionPolicy.OFF);

        assertAll(
                () -> assertEquals(List.of(0.0, 1.0), frames),
                () -> assertTrue(completionCalled.get()),
                () -> assertTrue(handle.isFinished()));
    }

    /// Scheduled delivery keeps every frame and completion callback on the EDT.
    ///
    /// @throws InterruptedException when the test thread is interrupted while awaiting completion
    @Test
    public void schedulerDeliversFramesOnEventDispatchThread() throws InterruptedException {
        SwingAnimator animator = new SwingAnimator(MotionPolicy.FULL, 4);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicBoolean callbacksOnEventDispatchThread = new AtomicBoolean(true);
        AtomicInteger frameCount = new AtomicInteger();

        AnimationHandle handle = animator.animate(
                Duration.ofMillis(80L),
                MotionPurpose.ESSENTIAL,
                Easing.LINEAR,
                value -> {
                    frameCount.incrementAndGet();
                    if (!SwingUtilities.isEventDispatchThread()) {
                        callbacksOnEventDispatchThread.set(false);
                    }
                },
                () -> {
                    if (!SwingUtilities.isEventDispatchThread()) {
                        callbacksOnEventDispatchThread.set(false);
                    }
                    completed.countDown();
                });

        try {
            assertTrue(completed.await(2L, TimeUnit.SECONDS));
            assertAll(
                    () -> assertTrue(frameCount.get() >= 2),
                    () -> assertTrue(callbacksOnEventDispatchThread.get()),
                    () -> assertTrue(handle.isFinished()));
        } finally {
            animator.cancelAll();
        }
    }

    /// Elapsed-time progress is clamped and independent of delivered frame counts.
    @Test
    public void normalizedProgressIsBounded() {
        assertAll(
                () -> assertEquals(0.0, SwingAnimator.normalizedProgress(-1L, 100L)),
                () -> assertEquals(0.0, SwingAnimator.normalizedProgress(0L, 100L)),
                () -> assertEquals(0.5, SwingAnimator.normalizedProgress(50L, 100L)),
                () -> assertEquals(1.0, SwingAnimator.normalizedProgress(100L, 100L)),
                () -> assertEquals(1.0, SwingAnimator.normalizedProgress(150L, 100L)));
    }

    /// Finite speed percentages scale authored durations while the dedicated endpoint represents infinity.
    @Test
    public void scalesAuthoredDurationByAnimationSpeed() {
        SwingAnimator animator = new SwingAnimator(MotionPolicy.FULL, 16, 160);

        assertAll(
                () -> assertEquals(160, animator.animationSpeedPercentage()),
                () -> assertEquals(2_000L, SwingAnimator.scaledDurationNanos(1_000L, 50)),
                () -> assertEquals(1_000L, SwingAnimator.scaledDurationNanos(1_000L, 100)),
                () -> assertEquals(556L, SwingAnimator.scaledDurationNanos(1_000L, 180)),
                () -> assertEquals(200L, SwingAnimator.scaledDurationNanos(1_000L, 500)),
                () -> assertEquals(0L, SwingAnimator.scaledDurationNanos(
                        1_000L, AnimationSpeedSettings.INSTANT_PERCENTAGE)),
                () -> assertEquals(0L, SwingAnimator.scaledDurationNanos(
                        1L, AnimationSpeedSettings.INSTANT_PERCENTAGE)),
                () -> assertEquals(0L, SwingAnimator.scaledDurationNanos(
                        0L, AnimationSpeedSettings.INSTANT_PERCENTAGE)));

        animator.setAnimationSpeedPercentage(80);
        assertEquals(80, animator.animationSpeedPercentage());
        assertThrows(IllegalArgumentException.class, () -> animator.setAnimationSpeedPercentage(0));
        assertThrows(IllegalArgumentException.class, () -> animator.setAnimationSpeedPercentage(110));
    }

    /// Selecting infinite speed finishes every active animation and suppresses intermediate frames thereafter.
    @Test
    public void infiniteSpeedFinishesActiveAndFutureAnimationsImmediately() {
        SwingAnimator animator = new SwingAnimator(MotionPolicy.FULL, 10_000);
        List<Double> activeFrames = new ArrayList<>();

        AnimationHandle active = animator.animate(
                Duration.ofSeconds(2L),
                MotionPurpose.DECORATIVE,
                Easing.LINEAR,
                activeFrames::add,
                () -> { });
        animator.setAnimationSpeedPercentage(AnimationSpeedSettings.INSTANT_PERCENTAGE);

        List<Double> futureFrames = new ArrayList<>();
        AnimationHandle future = animator.animate(
                Duration.ofSeconds(2L),
                MotionPurpose.DECORATIVE,
                Easing.LINEAR,
                futureFrames::add,
                () -> { });

        assertAll(
                () -> assertEquals(List.of(0.0, 1.0), activeFrames),
                () -> assertTrue(active.isFinished()),
                animator::animationsCompleteImmediately,
                () -> assertEquals(List.of(1.0), futureFrames),
                () -> assertTrue(future.isFinished()));
    }

    /// Every easing curve preserves exact start and end values after clamping.
    @Test
    public void easingCurvesPreserveBoundaries() {
        for (Easing easing : Easing.values()) {
            assertEquals(0.0, easing.apply(-1.0));
            assertEquals(1.0, easing.apply(2.0));
        }
    }
}

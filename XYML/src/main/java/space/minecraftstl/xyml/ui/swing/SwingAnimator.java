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
import org.jetbrains.annotations.Nullable;

import javax.swing.Timer;
import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleConsumer;

/// Runs small, cancelable Swing animations from elapsed monotonic time rather than accumulated timer ticks.
@NotNullByDefault
public final class SwingAnimator {
    /// Timer delay supplied by the application according to its frame-rate setting.
    private final int frameDelayMillis;

    /// Animations currently owned by this animator; accessed only on the EDT.
    private final Set<RunningAnimation> activeAnimations = new HashSet<>();

    /// Policy applied to new and currently active animations.
    private volatile MotionPolicy motionPolicy;

    /// Creates an animator without choosing an implicit frame rate.
    ///
    /// @param initialMotionPolicy the initial motion-accessibility policy
    /// @param frameDelayMillis the positive delay between Swing timer events
    public SwingAnimator(MotionPolicy initialMotionPolicy, int frameDelayMillis) {
        motionPolicy = Objects.requireNonNull(initialMotionPolicy);
        if (frameDelayMillis <= 0) {
            throw new IllegalArgumentException("frameDelayMillis must be positive");
        }
        this.frameDelayMillis = frameDelayMillis;
    }

    /// Returns the current motion-accessibility policy.
    ///
    /// @return the policy used for animations
    public MotionPolicy motionPolicy() {
        return motionPolicy;
    }

    /// Changes the motion policy and immediately completes active animations no longer allowed by it.
    ///
    /// @param newMotionPolicy the new motion-accessibility policy
    public void setMotionPolicy(MotionPolicy newMotionPolicy) {
        Objects.requireNonNull(newMotionPolicy);

        EdtDispatcher.executeAndWait(() -> {
            motionPolicy = newMotionPolicy;
            RunningAnimation[] snapshot = activeAnimations.toArray(RunningAnimation[]::new);
            for (RunningAnimation animation : snapshot) {
                if (!newMotionPolicy.allows(animation.purpose)) {
                    animation.finishOnEventDispatchThread();
                }
            }
        });
    }

    /// Starts an animation on the EDT or applies its final state immediately when disabled by policy.
    ///
    /// The frame callback receives eased values between zero and one. A timed animation emits an initial zero frame; a suppressed or
    /// zero-duration animation emits only the final frame. Both frame and completion callbacks always run on the EDT.
    ///
    /// @param duration the non-negative requested duration
    /// @param purpose whether the motion is essential or decorative
    /// @param easing the curve applied to normalized elapsed time
    /// @param frameConsumer the callback that applies each visual frame
    /// @param completion the callback invoked exactly once after reaching the final frame
    /// @return a handle that can cancel or inspect this animation
    public AnimationHandle animate(
            Duration duration,
            MotionPurpose purpose,
            Easing easing,
            DoubleConsumer frameConsumer,
            Runnable completion) {
        Objects.requireNonNull(duration);
        Objects.requireNonNull(purpose);
        Objects.requireNonNull(easing);
        Objects.requireNonNull(frameConsumer);
        Objects.requireNonNull(completion);

        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }

        AtomicReference<@Nullable RunningAnimation> result = new AtomicReference<>();
        EdtDispatcher.executeAndWait(() -> {
            RunningAnimation animation = new RunningAnimation(
                    duration.toNanos(), purpose, easing, frameConsumer, completion);
            result.set(animation);
            if (duration.isZero() || !motionPolicy.allows(purpose)) {
                animation.completeImmediately();
            } else {
                animation.start();
            }
        });
        return Objects.requireNonNull(result.get());
    }

    /// Cancels every animation currently owned by this animator.
    ///
    /// Completion callbacks are not invoked.
    public void cancelAll() {
        EdtDispatcher.executeAndWait(() -> {
            RunningAnimation[] snapshot = activeAnimations.toArray(RunningAnimation[]::new);
            for (RunningAnimation animation : snapshot) {
                animation.cancelOnEventDispatchThread();
            }
        });
    }

    /// Converts elapsed nanoseconds to bounded linear progress.
    ///
    /// @param elapsedNanos the elapsed monotonic time
    /// @param durationNanos the positive total duration
    /// @return a value between zero and one
    static double normalizedProgress(long elapsedNanos, long durationNanos) {
        if (durationNanos <= 0L) {
            throw new IllegalArgumentException("durationNanos must be positive");
        }
        if (elapsedNanos <= 0L) {
            return 0.0;
        }
        if (elapsedNanos >= durationNanos) {
            return 1.0;
        }
        return (double) elapsedNanos / durationNanos;
    }

    /// Represents the internal lifecycle state of a running or completed animation.
    @NotNullByDefault
    private enum AnimationState {
        /// The animation exists but has not started.
        CREATED,

        /// The Swing timer is advancing the animation.
        RUNNING,

        /// The caller stopped the animation before completion.
        CANCELLED,

        /// The final frame and completion callback have run.
        FINISHED
    }

    /// Owns one Swing timer and keeps all callback transitions on the EDT.
    @NotNullByDefault
    private final class RunningAnimation implements AnimationHandle {
        /// Total requested duration in nanoseconds.
        private final long durationNanos;

        /// Semantic purpose used when the global motion policy changes.
        private final MotionPurpose purpose;

        /// Easing curve used for frame progress.
        private final Easing easing;

        /// Applies each eased visual frame.
        private final DoubleConsumer frameConsumer;

        /// Runs after the final frame during successful completion.
        private final Runnable completion;

        /// Coalescing Swing timer that schedules frame checks on the EDT.
        private final Timer timer;

        /// Monotonic start instant captured immediately before the initial frame.
        private long startedAtNanos;

        /// Lifecycle state visible to callers on any thread.
        private volatile AnimationState state = AnimationState.CREATED;

        /// Creates one animation that has not started yet.
        ///
        /// @param durationNanos total requested duration in nanoseconds
        /// @param purpose semantic purpose used by the motion policy
        /// @param easing easing curve used for frame progress
        /// @param frameConsumer callback that applies visual frames
        /// @param completion callback invoked after the final frame
        private RunningAnimation(
                long durationNanos,
                MotionPurpose purpose,
                Easing easing,
                DoubleConsumer frameConsumer,
                Runnable completion) {
            this.durationNanos = durationNanos;
            this.purpose = purpose;
            this.easing = easing;
            this.frameConsumer = frameConsumer;
            this.completion = completion;
            timer = new Timer(frameDelayMillis, event -> tick());
            timer.setCoalesce(true);
        }

        /// Emits the initial frame and starts timer delivery.
        private void start() {
            EdtDispatcher.requireEventDispatchThread();

            state = AnimationState.RUNNING;
            activeAnimations.add(this);
            startedAtNanos = System.nanoTime();
            try {
                frameConsumer.accept(easing.apply(0.0));
                timer.start();
            } catch (RuntimeException | Error e) {
                cancelOnEventDispatchThread();
                throw e;
            }
        }

        /// Applies the final state without starting a timer.
        private void completeImmediately() {
            EdtDispatcher.requireEventDispatchThread();

            try {
                frameConsumer.accept(easing.apply(1.0));
                state = AnimationState.FINISHED;
                completion.run();
            } catch (RuntimeException | Error e) {
                state = AnimationState.CANCELLED;
                throw e;
            }
        }

        /// Recomputes progress from monotonic elapsed time for one timer event.
        private void tick() {
            EdtDispatcher.requireEventDispatchThread();

            if (state != AnimationState.RUNNING) {
                timer.stop();
                return;
            }

            double progress = normalizedProgress(System.nanoTime() - startedAtNanos, durationNanos);
            if (progress >= 1.0) {
                finishOnEventDispatchThread();
                return;
            }

            try {
                frameConsumer.accept(easing.apply(progress));
            } catch (RuntimeException | Error e) {
                cancelOnEventDispatchThread();
                throw e;
            }
        }

        /// Stops timer delivery, applies the final frame, and invokes completion.
        private void finishOnEventDispatchThread() {
            EdtDispatcher.requireEventDispatchThread();

            if (state != AnimationState.RUNNING) {
                return;
            }

            timer.stop();
            activeAnimations.remove(this);
            try {
                frameConsumer.accept(easing.apply(1.0));
                state = AnimationState.FINISHED;
                completion.run();
            } catch (RuntimeException | Error e) {
                state = AnimationState.CANCELLED;
                throw e;
            }
        }

        /// Cancels this animation and synchronously stops its timer on the EDT.
        @Override
        public void cancel() {
            EdtDispatcher.executeAndWait(this::cancelOnEventDispatchThread);
        }

        /// Stops timer delivery without applying another frame or invoking completion.
        private void cancelOnEventDispatchThread() {
            EdtDispatcher.requireEventDispatchThread();

            if (state != AnimationState.RUNNING) {
                return;
            }

            timer.stop();
            activeAnimations.remove(this);
            state = AnimationState.CANCELLED;
        }

        /// Returns whether timer frames are currently enabled for this animation.
        ///
        /// @return `true` only while the animation is running
        @Override
        public boolean isRunning() {
            return state == AnimationState.RUNNING;
        }

        /// Returns whether this animation was explicitly cancelled or failed in a callback.
        ///
        /// @return `true` after cancellation
        @Override
        public boolean isCancelled() {
            return state == AnimationState.CANCELLED;
        }

        /// Returns whether this animation reached and applied its final frame.
        ///
        /// @return `true` after successful completion
        @Override
        public boolean isFinished() {
            return state == AnimationState.FINISHED;
        }
    }
}

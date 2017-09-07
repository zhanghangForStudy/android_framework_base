/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.view;

import android.hardware.display.DisplayManagerGlobal;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.util.Log;
import android.util.TimeUtils;
import android.view.animation.AnimationUtils;

import java.io.PrintWriter;

/**
 * Coordinates the timing of animations, input and drawing.
 * 动画、输入事件以及绘制的时间(点)映射
 * <p>
 * The choreographer receives timing pulses (such as vertical synchronization)
 * from the display subsystem then schedules work to occur as part of rendering
 * the next display frame.
 * 绘制导演接收一个来至于显示子系统的时间脉冲（例如垂直同步，目前Android的垂直同步一般为16ms?）；
 * 然后调度，作为渲染下一显示帧的某一环节而出现的工作。
 * </p><p>
 * Applications typically（通常） interact with the choreographer indirectly using
 * higher level abstractions in the animation framework or the view hierarchy.
 * Here are some examples of things you can do using the higher-level APIs.
 * 应用通常使用动画框架或者视图树中的高级别抽象，与此绘制导演直接交互。
 * 这儿有一些高级别抽象的使用例子。
 * </p>
 * <ul>
 * <li>To post an animation to be processed on a regular time basis synchronized with
 * display frame rendering, use {@link android.animation.ValueAnimator#start}.
 * 为了开始一个执行时间频率与显示帧频率相同的动画，使用{@link android.animation.ValueAnimator#start}方法
 * </li>
 * <li>To post a {@link Runnable} to be invoked once at the beginning of the next display
 * frame, use {@link View#postOnAnimation}.
 * 为了执行一个在下一次显示帧开始渲染时，执行的runnable，使用{@link View#postOnAnimation}
 * </li>
 * <li>To post a {@link Runnable} to be invoked once at the beginning of the next display
 * frame after a delay, use {@link View#postOnAnimationDelayed}.
 * 为了执行一个在下一次显示帧开始渲染后多少时间，执行的runnable，使用{@link View#postOnAnimationDelayed}
 * </li>
 * <li>To post a call to {@link View#invalidate()} to occur once at the beginning of the
 * next display frame, use {@link View#postInvalidateOnAnimation()} or
 * {@link View#postInvalidateOnAnimation(int, int, int, int)}.
 * 为了让{@link View#invalidate()}方法在下一次显示帧开始渲染时，生效，使用{@link View#postInvalidateOnAnimation()}方法
 * </li>
 * <li>To ensure that the contents of a {@link View} scroll smoothly and are drawn in
 * sync with display frame rendering, do nothing.  This already happens automatically.
 * {@link View#onDraw} will be called at the appropriate time.
 * 为了确保视图的内容光滑的滚动，且与显示帧的渲染同步绘制，无需做任何事情。
 * 这将会自动发生。将会在合适的时间调用{@link View#onDraw}方法
 * </li>
 * </ul>
 * <p>
 * However, there are a few cases where you might want to use the functions of the
 * choreographer directly in your application.
 * 然而，在少数情况下，应用也可以直接使用绘制导演类中的相关方法，
 * 例如：
 * Here are some examples.
 * </p>
 * <ul>
 * <li>If your application does its rendering in a different thread, possibly using GL,
 * or does not use the animation framework or view hierarchy at all
 * and you want to ensure that it is appropriately synchronized with the display, then use
 * {@link Choreographer#postFrameCallback}.
 * 如果应用在一个不同的线程之中运行渲染工作，或者完全不使用动画框架或者视图树，
 * 则为了确保同步，可以使用{@link Choreographer#postFrameCallback}方法
 * </li>
 * <li>... and that's about it.</li>
 * </ul>
 * <p>
 * Each {@link Looper} thread has its own choreographer.  Other threads can
 * post callbacks to run on the choreographer but they will run on the {@link Looper}
 * to which the choreographer belongs.
 * 每一个Looper线程都拥有它自己的绘制导演，
 * 其他线程传递回调，用以在绘制导演上运行，但是这些会调将运行在绘制导演所属的Looper之上
 * </p>
 */
public final class Choreographer {
    private static final String TAG = "Choreographer";

    // Prints debug messages about jank which was detected (low volume).
    // 打印检测到的关于jank的调试消息
    private static final boolean DEBUG_JANK = false;

    // Prints debug messages about every frame and callback registered (high volume).
    // 打印每一帧及其注册的回调相关的调试信息
    private static final boolean DEBUG_FRAMES = false;

    // The default amount of time in ms between animation frames.
    // When vsync is not enabled, we want to have some idea of how long we should
    // wait before posting the next animation message.  It is important that the
    // default value be less than the true inter-frame delay on all devices to avoid
    // situations where we might skip frames by waiting too long (we must compensate
    // for jitter and hardware variations).  Regardless of this value, the animation
    // and display loop is ultimately rate-limited by how fast new graphics buffers can
    // be dequeued.
    // 动画帧之间默认的时间(毫秒)。
    // 当垂直同步不启用的时候，我们期望有一些方法，来表示在传递下一个动画消息之前，我们应该等待多长时间
    // 默认的值小于所有设备上的真正的帧间延迟是重要的，因为这将避免因等待太长而跳过动画帧的情况（
    // 我们必须补偿震动以及硬件引起的变化）。
    // 忽略这个值的话，动画和展示循环，将最终被新的图片缓存入队的频率限制
    private static final long DEFAULT_FRAME_DELAY = 10;

    // The number of milliseconds between animation frames.
    // 动画帧之间的毫秒事件
    private static volatile long sFrameDelay = DEFAULT_FRAME_DELAY;

    // Thread local storage for the choreographer.
    // 线程级的绘制导演
    private static final ThreadLocal<Choreographer> sThreadInstance =
            new ThreadLocal<Choreographer>() {
                @Override
                protected Choreographer initialValue() {
                    Looper looper = Looper.myLooper();
                    if (looper == null) {
                        throw new IllegalStateException("The current thread must have a looper!");
                    }
                    return new Choreographer(looper);
                }
            };

    // Enable/disable vsync for animations and drawing.
    // 启动或者禁止，通过垂直同步的方法来动画和绘制
    private static final boolean USE_VSYNC = SystemProperties.getBoolean(
            "debug.choreographer.vsync", true);

    // Enable/disable using the frame time instead of returning now.
    // 启动或者禁止使用帧时间，来代替立即返回

    private static final boolean USE_FRAME_TIME = SystemProperties.getBoolean(
            "debug.choreographer.frametime", true);

    // Set a limit to warn about skipped frames.
    // Skipped frames imply jank.
    // 设置一个限制用来警告跳过的帧
    // 跳过的帧意味这一个卡顿
    private static final int SKIPPED_FRAME_WARNING_LIMIT = SystemProperties.getInt(
            "debug.choreographer.skipwarning", 30);

    private static final int MSG_DO_FRAME = 0;
    private static final int MSG_DO_SCHEDULE_VSYNC = 1;
    private static final int MSG_DO_SCHEDULE_CALLBACK = 2;

    // All frame callbacks posted by applications have this token.
    // 所有的通过应用发送的帧回调，都有此引用
    private static final Object FRAME_CALLBACK_TOKEN = new Object() {
        public String toString() {
            return "FRAME_CALLBACK_TOKEN";
        }
    };

    private final Object mLock = new Object();

    private final Looper mLooper;
    private final FrameHandler mHandler;

    // The display event receiver can only be accessed by the looper thread to which
    // it is attached.  We take care to ensure that we post message to the looper
    // if appropriate when interacting with the display event receiver.
    // 显示屏幕事件接受者只能被拥有looper的线程访问。
    // 我们需要小心的保证我们将消息传递给了此looper，当我们正在和此接受者交互的时候
    private final FrameDisplayEventReceiver mDisplayEventReceiver;

    // 回调记录器？回调记录池？
    private CallbackRecord mCallbackPool;

    // 回调队列
    private final CallbackQueue[] mCallbackQueues;

    // 是否正在进行帧调度
    private boolean mFrameScheduled;
    // 是否回调方法正在运行
    private boolean mCallbacksRunning;
    // 上一次的帧开始被渲染的时间，纳秒级
    private long mLastFrameTimeNanos;
    // 帧之间间隔，纳秒级
    private long mFrameIntervalNanos;
    private boolean mDebugPrintNextFrameTimeDelta;

    /**
     * Contains information about the current frame for jank-tracking,
     * mainly timings of key events along with a bit of metadata about
     * view tree state
     * <p>
     * <p>
     * 包含当前帧的相关信息，用以进行卡顿追踪。
     * 主要包含按键事件的计时，以及一个包含视图树状态的元数据bit
     * </p>
     * TODO: Is there a better home for this? Currently Choreographer
     * is the only one with CALLBACK_ANIMATION start time, hence why this
     * resides here.
     *
     * @hide
     */
    FrameInfo mFrameInfo = new FrameInfo();

    /**
     * Must be kept in sync with CALLBACK_* ints below, used to index into this array.
     *
     * @hide
     */
    private static final String[] CALLBACK_TRACE_TITLES = {
            "input", "animation", "traversal", "commit"
    };

    /**
     * Callback type: Input callback.  Runs first.
     * 回调类型，输入事件首先运行
     *
     * @hide
     */
    public static final int CALLBACK_INPUT = 0;

    /**
     * Callback type: Animation callback.  Runs before traversals.
     * 动画回调，在遍历之前运行
     *
     * @hide
     */
    public static final int CALLBACK_ANIMATION = 1;

    /**
     * Callback type: Traversal callback.  Handles layout and draw.  Runs
     * after all other asynchronous messages have been handled.
     * 遍历回调。处理布局和绘制。
     * 在所有其他异步消息被处理后运行
     *
     * @hide
     */
    public static final int CALLBACK_TRAVERSAL = 2;

    /**
     * Callback type: Commit callback.  Handles post-draw operations for the frame.
     * Runs after traversal completes.  The {@link #getFrameTime() frame time} reported
     * during this callback may be updated to reflect delays that occurred while
     * traversals were in progress in case heavy layout operations caused some frames
     * to be skipped.  The frame time reported during this callback provides a better
     * estimate of the start time of the frame in which animations (and other updates
     * to the view hierarchy state) actually took effect.
     * 提交回调。处理关于此帧的绘制之后的操作。
     * 在遍历结束之后完成。此回调之间被报告的帧事件可能被更新用以表现遍历过程中的延迟，以防止
     * 重量级的布局操作引起一些帧被跳过。
     * 在此回调之中的报告的帧事件提供了一个更好的，使得动画及其他关于更新视图树状态生效的，帧开始时间的评估
     *
     * @hide
     */
    public static final int CALLBACK_COMMIT = 3;

    private static final int CALLBACK_LAST = CALLBACK_COMMIT;

    private Choreographer(Looper looper) {
        mLooper = looper;
        mHandler = new FrameHandler(looper);
        mDisplayEventReceiver = USE_VSYNC ? new FrameDisplayEventReceiver(looper) : null;
        mLastFrameTimeNanos = Long.MIN_VALUE;

        //一般为16毫秒
        mFrameIntervalNanos = (long) (1000000000 / getRefreshRate());

        mCallbackQueues = new CallbackQueue[CALLBACK_LAST + 1];
        for (int i = 0; i <= CALLBACK_LAST; i++) {
            mCallbackQueues[i] = new CallbackQueue();
        }
    }

    // 一般而言，显示屏幕的刷新速率是一秒60次？
    private static float getRefreshRate() {
        DisplayInfo di = DisplayManagerGlobal.getInstance().getDisplayInfo(
                Display.DEFAULT_DISPLAY);
        return di.getMode().getRefreshRate();
    }

    /**
     * Gets the choreographer for the calling thread.  Must be called from
     * a thread that already has a {@link android.os.Looper} associated with it.
     * 必须由一个粘贴了looper对象的线程调用
     *
     * @return The choreographer for this thread.
     * @throws IllegalStateException if the thread does not have a looper.
     */
    public static Choreographer getInstance() {
        return sThreadInstance.get();
    }

    /**
     * Destroys the calling thread's choreographer
     *
     * @hide
     */
    public static void releaseInstance() {
        Choreographer old = sThreadInstance.get();
        sThreadInstance.remove();
        old.dispose();
    }

    private void dispose() {
        mDisplayEventReceiver.dispose();
    }

    /**
     * The amount of time, in milliseconds, between each frame of the animation.
     * <p>
     * This is a requested time that the animation will attempt to honor, but the actual delay
     * between frames may be different, depending on system load and capabilities. This is a static
     * function because the same delay will be applied to all animations, since they are all
     * run off of a single timing loop.
     * </p>
     * <p>
     * The frame delay may be ignored when the animation system uses an external timing
     * source, such as the display refresh rate (vsync), to govern animations.
     * </p>
     * <p>
     * 在每一个动画帧之间的时间量，毫秒级
     * 这是一个被要求的，动画将尝试信任的时间，但是，依赖于系统导入性能，帧之间的实际延迟可能会不同。
     * 这是一个静态的方法，因为相同的延迟将被应用到所有的动画之上，因为它们所有都在一个单独的时间循环之中运行。
     * 当动画系统使用一个外部的时间源（例如垂直同步信号）来管理动画时，帧延迟可能被忽略
     * </p>
     *
     * @return the requested time between frames, in milliseconds
     * @hide
     */
    public static long getFrameDelay() {
        return sFrameDelay;
    }

    /**
     * The amount of time, in milliseconds, between each frame of the animation.
     * <p>
     * This is a requested time that the animation will attempt to honor, but the actual delay
     * between frames may be different, depending on system load and capabilities. This is a static
     * function because the same delay will be applied to all animations, since they are all
     * run off of a single timing loop.
     * </p><p>
     * The frame delay may be ignored when the animation system uses an external timing
     * source, such as the display refresh rate (vsync), to govern animations.
     * </p>
     *
     * @param frameDelay the requested time between frames, in milliseconds
     * @hide
     */
    public static void setFrameDelay(long frameDelay) {
        sFrameDelay = frameDelay;
    }

    /**
     * Subtracts typical frame delay time from a delay interval in milliseconds.
     * <p>
     * This method can be used to compensate for animation delay times that have baked
     * in assumptions about the frame delay.  For example, it's quite common for code to
     * assume a 60Hz frame time and bake in a 16ms delay.  When we call
     * {@link #postAnimationCallbackDelayed} we want to know how long to wait before
     * posting the animation callback but let the animation timer take care of the remaining
     * frame delay time.
     * </p><p>
     * This method is somewhat conservative about how much of the frame delay it
     * subtracts.  It uses the same value returned by {@link #getFrameDelay} which by
     * default is 10ms even though many parts of the system assume 16ms.  Consequently,
     * we might still wait 6ms before posting an animation callback that we want to run
     * on the next frame, but this is much better than waiting a whole 16ms and likely
     * missing the deadline.
     * </p>
     * <p>
     * 从一个延迟间隔之中减去特有的帧延迟时间。
     * 此方法被用来补偿，假定帧延迟的，动画延迟时间。
     * 例如，对于60HZ的帧时间，嘉定16毫秒的延迟是很常见的。
     * 当我们调用postAnimationCallbackDelayed方法时，我们期望知道，
     * 在发送动画回调并让动画计时器关系剩下的帧延迟时间之前，我们应该等待
     * 多长的时间。
     * 此方法关于它应该减去多少帧延迟，有点保守。
     * 它使用getFrameDelay返回的值，尽管系统许多部分都使用16毫秒。
     * 因此，在发送一个在下一帧运行的动画之前，我们将可能等待6秒，但是这比等待整个16毫秒而看起来像是失去了截止日期，要好一些
     * </p>
     *
     * @param delayMillis The original delay time including an assumed frame delay.
     * @return The adjusted delay time with the assumed frame delay subtracted out.
     * @hide
     */
    public static long subtractFrameDelay(long delayMillis) {
        final long frameDelay = sFrameDelay;
        return delayMillis <= frameDelay ? 0 : delayMillis - frameDelay;
    }

    /**
     * @return The refresh rate as the nanoseconds between frames
     * @hide
     */
    public long getFrameIntervalNanos() {
        return mFrameIntervalNanos;
    }

    void dump(String prefix, PrintWriter writer) {
        String innerPrefix = prefix + "  ";
        writer.print(prefix);
        writer.println("Choreographer:");
        writer.print(innerPrefix);
        writer.print("mFrameScheduled=");
        writer.println(mFrameScheduled);
        writer.print(innerPrefix);
        writer.print("mLastFrameTime=");
        writer.println(TimeUtils.formatUptime(mLastFrameTimeNanos / 1000000));
    }

    /**
     * Posts a callback to run on the next frame.
     * 运行在下一帧上的回调
     * <p>
     * The callback runs once then is automatically removed.
     * 回调一旦运行就会被自动删除
     * </p>
     *
     * @param callbackType The callback type.
     * @param action       The callback action to run during the next frame.
     * @param token        The callback token, or null if none.
     * @hide
     * @see #removeCallbacks
     */
    public void postCallback(int callbackType, Runnable action, Object token) {
        postCallbackDelayed(callbackType, action, token, 0);
    }

    /**
     * Posts a callback to run on the next frame after the specified delay.
     * 传递一个回调，此回调将在下一帧，延迟指定的时间后，被调用
     * <p>
     * The callback runs once then is automatically removed.
     * </p>
     *
     * @param callbackType The callback type.
     * @param action       The callback action to run during the next frame after the specified delay.
     * @param token        The callback token, or null if none.
     * @param delayMillis  The delay time in milliseconds.
     * @hide
     * @see #removeCallback
     */
    public void postCallbackDelayed(int callbackType,
                                    Runnable action, Object token, long delayMillis) {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        if (callbackType < 0 || callbackType > CALLBACK_LAST) {
            throw new IllegalArgumentException("callbackType is invalid");
        }

        postCallbackDelayedInternal(callbackType, action, token, delayMillis);
    }

    private void postCallbackDelayedInternal(int callbackType,
                                             Object action, Object token, long delayMillis) {
        if (DEBUG_FRAMES) {
            Log.d(TAG, "PostCallback: type=" + callbackType
                    + ", action=" + action + ", token=" + token
                    + ", delayMillis=" + delayMillis);
        }

        synchronized (mLock) {
            final long now = SystemClock.uptimeMillis();
            final long dueTime = now + delayMillis;
            mCallbackQueues[callbackType].addCallbackLocked(dueTime, action, token);

            if (dueTime <= now) {
                scheduleFrameLocked(now);
            } else {
                Message msg = mHandler.obtainMessage(MSG_DO_SCHEDULE_CALLBACK, action);
                msg.arg1 = callbackType;
                msg.setAsynchronous(true);
                mHandler.sendMessageAtTime(msg, dueTime);
            }
        }
    }

    /**
     * Removes callbacks that have the specified action and token.
     * 移除回调
     *
     * @param callbackType The callback type.
     * @param action       The action property of the callbacks to remove, or null to remove
     *                     callbacks with any action.
     * @param token        The token property of the callbacks to remove, or null to remove
     *                     callbacks with any token.
     * @hide
     * @see #postCallback
     * @see #postCallbackDelayed
     */
    public void removeCallbacks(int callbackType, Runnable action, Object token) {
        if (callbackType < 0 || callbackType > CALLBACK_LAST) {
            throw new IllegalArgumentException("callbackType is invalid");
        }

        removeCallbacksInternal(callbackType, action, token);
    }

    private void removeCallbacksInternal(int callbackType, Object action, Object token) {
        if (DEBUG_FRAMES) {
            Log.d(TAG, "RemoveCallbacks: type=" + callbackType
                    + ", action=" + action + ", token=" + token);
        }

        synchronized (mLock) {
            mCallbackQueues[callbackType].removeCallbacksLocked(action, token);
            if (action != null && token == null) {
                mHandler.removeMessages(MSG_DO_SCHEDULE_CALLBACK, action);
            }
        }
    }

    /**
     * Posts a frame callback to run on the next frame.
     * 传递一个在下一帧运行的帧回调
     * <p>
     * The callback runs once then is automatically removed.
     * 此回调只会运行一次，然后就会自动被删掉
     * </p>
     *
     * @param callback The frame callback to run during the next frame.
     * @see #postFrameCallbackDelayed
     * @see #removeFrameCallback
     */
    public void postFrameCallback(FrameCallback callback) {
        postFrameCallbackDelayed(callback, 0);
    }

    /**
     * Posts a frame callback to run on the next frame after the specified delay.
     * 传递一个帧回调，此回调将在下一帧，延迟指定的时间后，被调用
     * <p>
     * The callback runs once then is automatically removed.
     * </p>
     *
     * @param callback    The frame callback to run during the next frame.
     * @param delayMillis The delay time in milliseconds.
     * @see #postFrameCallback
     * @see #removeFrameCallback
     */
    public void postFrameCallbackDelayed(FrameCallback callback, long delayMillis) {
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }

        postCallbackDelayedInternal(CALLBACK_ANIMATION,
                callback, FRAME_CALLBACK_TOKEN, delayMillis);
    }

    /**
     * Removes a previously posted frame callback.
     *
     * @param callback The frame callback to remove.
     * @see #postFrameCallback
     * @see #postFrameCallbackDelayed
     */
    public void removeFrameCallback(FrameCallback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }

        removeCallbacksInternal(CALLBACK_ANIMATION, callback, FRAME_CALLBACK_TOKEN);
    }

    /**
     * Gets the time when the current frame started.
     * <p>
     * This method provides the time in milliseconds when the frame started being rendered.
     * The frame time provides a stable time base for synchronizing animations
     * and drawing.  It should be used instead of {@link SystemClock#uptimeMillis()}
     * or {@link System#nanoTime()} for animations and drawing in the UI.  Using the frame
     * time helps to reduce inter-frame jitter because the frame time is fixed at the time
     * the frame was scheduled to start, regardless of when the animations or drawing
     * callback actually runs.  All callbacks that run as part of rendering a frame will
     * observe the same frame time so using the frame time also helps to synchronize effects
     * that are performed by different callbacks.
     * </p><p>
     * Please note that the framework already takes care to process animations and
     * drawing using the frame time as a stable time base.  Most applications should
     * not need to use the frame time information directly.
     * </p><p>
     * This method should only be called from within a callback.
     * </p>
     * <p>
     * 获取当前帧开始的时间
     * 此方法提供当前帧开始被渲染的时间，此时间为毫秒级别的精度。
     * 此帧时间为同步动画与绘制，提供了一种稳定的时间依据。
     * 因此，当在UI上动画或者绘制时，应该使用此方法来代替使用{@link SystemClock#uptimeMillis()}.
     * 使用帧时间可以帮助减少帧间的卡顿，因为这个帧时间被修复为当前帧开始被渲染的时间，从而忽略了动画和
     * 绘制回调实际运行的时候。
     * 所有的，作为渲染一个帧的一部分，的回调将遵循相同的帧时间，所以使用此帧时间也将帮助
     * 同步不同回调的执行所带来的影响.
     * 请注意,framework已经很小心的，通过使用帧时间，作为一个稳定的时间依据，来处理动画和绘制了。
     * 所以绝大部分的应用应该不需要直接使用帧时间。
     * 这个方法应该只在一个回调之中被调用
     * </p>
     *
     * @return The frame start time, in the {@link SystemClock#uptimeMillis()} time base.
     * @throws IllegalStateException if no frame is in progress.
     * @hide
     */
    public long getFrameTime() {
        return getFrameTimeNanos() / TimeUtils.NANOS_PER_MS;
    }

    /**
     * Same as {@link #getFrameTime()} but with nanosecond precision.
     *
     * @return The frame start time, in the {@link System#nanoTime()} time base.
     * @throws IllegalStateException if no frame is in progress.
     * @hide
     */
    public long getFrameTimeNanos() {
        synchronized (mLock) {
            if (!mCallbacksRunning) {
                throw new IllegalStateException("This method must only be called as "
                        + "part of a callback while a frame is in progress.");
            }
            return USE_FRAME_TIME ? mLastFrameTimeNanos : System.nanoTime();
        }
    }

    // 请求一次垂直同步信号？
    private void scheduleFrameLocked(long now) {
        if (!mFrameScheduled) {
            mFrameScheduled = true;
            if (USE_VSYNC) {
                if (DEBUG_FRAMES) {
                    Log.d(TAG, "Scheduling next frame on vsync.");
                }

                // If running on the Looper thread, then schedule the vsync immediately,
                // otherwise post a message to schedule the vsync from the UI thread
                // as soon as possible.
                // 如果运行在一个looper线程上，则立即调度垂直同步信号，
                // 否者发送一个消息，来调度垂直同步信号
                if (isRunningOnLooperThreadLocked()) {
                    scheduleVsyncLocked();
                } else {
                    Message msg = mHandler.obtainMessage(MSG_DO_SCHEDULE_VSYNC);
                    msg.setAsynchronous(true);
                    mHandler.sendMessageAtFrontOfQueue(msg);
                }
            } else {
                final long nextFrameTime = Math.max(
                        mLastFrameTimeNanos / TimeUtils.NANOS_PER_MS + sFrameDelay, now);
                if (DEBUG_FRAMES) {
                    Log.d(TAG, "Scheduling next frame in " + (nextFrameTime - now) + " ms.");
                }
                Message msg = mHandler.obtainMessage(MSG_DO_FRAME);
                msg.setAsynchronous(true);
                mHandler.sendMessageAtTime(msg, nextFrameTime);
            }
        }
    }

    // 同步信号来临时，执行相关的回调？
    void doFrame(long frameTimeNanos, int frame) {
        final long startNanos;
        synchronized (mLock) {
            if (!mFrameScheduled) {
                return; // no work to do
            }

            if (DEBUG_JANK && mDebugPrintNextFrameTimeDelta) {
                mDebugPrintNextFrameTimeDelta = false;
                Log.d(TAG, "Frame time delta: "
                        + ((frameTimeNanos - mLastFrameTimeNanos) * 0.000001f) + " ms");
            }

            long intendedFrameTimeNanos = frameTimeNanos;
            startNanos = System.nanoTime();
            final long jitterNanos = startNanos - frameTimeNanos;
            // 如果出现了卡顿，就爆出一些警告日志，并且调整垂直信号发送的时间
            if (jitterNanos >= mFrameIntervalNanos) {
                // 一般而言，如果垂直信号发送时间，与当前时间之差，超过了16毫秒
                // 超过的时间转换为超过的帧数
                final long skippedFrames = jitterNanos / mFrameIntervalNanos;
                if (skippedFrames >= SKIPPED_FRAME_WARNING_LIMIT) {
                    Log.i(TAG, "Skipped " + skippedFrames + " frames!  "
                            + "The application may be doing too much work on its main thread.");
                }
                final long lastFrameOffset = jitterNanos % mFrameIntervalNanos;
                if (DEBUG_JANK) {
                    Log.d(TAG, "Missed vsync by " + (jitterNanos * 0.000001f) + " ms "
                            + "which is more than the frame interval of "
                            + (mFrameIntervalNanos * 0.000001f) + " ms!  "
                            + "Skipping " + skippedFrames + " frames and setting frame "
                            + "time to " + (lastFrameOffset * 0.000001f) + " ms in the past.");
                }
                frameTimeNanos = startNanos - lastFrameOffset;
            }

            if (frameTimeNanos < mLastFrameTimeNanos) {
                if (DEBUG_JANK) {
                    Log.d(TAG, "Frame time appears to be going backwards.  May be due to a "
                            + "previously skipped frame.  Waiting for next vsync.");
                }
                scheduleVsyncLocked();
                return;
            }

            mFrameInfo.setVsync(intendedFrameTimeNanos, frameTimeNanos);
            mFrameScheduled = false;
            mLastFrameTimeNanos = frameTimeNanos;
        }

        try {
            Trace.traceBegin(Trace.TRACE_TAG_VIEW, "Choreographer#doFrame");
            AnimationUtils.lockAnimationClock(frameTimeNanos / TimeUtils.NANOS_PER_MS);

            mFrameInfo.markInputHandlingStart();
            doCallbacks(Choreographer.CALLBACK_INPUT, frameTimeNanos);

            mFrameInfo.markAnimationsStart();
            doCallbacks(Choreographer.CALLBACK_ANIMATION, frameTimeNanos);

            mFrameInfo.markPerformTraversalsStart();
            doCallbacks(Choreographer.CALLBACK_TRAVERSAL, frameTimeNanos);

            doCallbacks(Choreographer.CALLBACK_COMMIT, frameTimeNanos);
        } finally {
            AnimationUtils.unlockAnimationClock();
            Trace.traceEnd(Trace.TRACE_TAG_VIEW);
        }

        if (DEBUG_FRAMES) {
            final long endNanos = System.nanoTime();
            Log.d(TAG, "Frame " + frame + ": Finished, took "
                    + (endNanos - startNanos) * 0.000001f + " ms, latency "
                    + (startNanos - frameTimeNanos) * 0.000001f + " ms.");
        }
    }

    /**
     * 处理不同类型的回调
     *
     * @param callbackType   回调的类型
     * @param frameTimeNanos 当前的垂直同步信号时间
     */
    void doCallbacks(int callbackType, long frameTimeNanos) {
        CallbackRecord callbacks;
        synchronized (mLock) {
            // We use "now" to determine when callbacks become due because it's possible
            // for earlier processing phases in a frame to post callbacks that should run
            // in a following phase, such as an input event that causes an animation to start.
            // 我们使用now来决定回调到期的时间，因为对于在一个帧之中，在较早处理阶段中传递一个应该在较晚处理阶段处理
            // 的回调是可能，例如一个能引起一个动画开始的输入时间
            final long now = System.nanoTime();
            callbacks = mCallbackQueues[callbackType].extractDueCallbacksLocked(
                    now / TimeUtils.NANOS_PER_MS);
            if (callbacks == null) {
                return;
            }
            mCallbacksRunning = true;

            // Update the frame time if necessary when committing the frame.
            // We only update the frame time if we are more than 2 frames late reaching
            // the commit phase.  This ensures that the frame time which is observed by the
            // callbacks will always increase from one frame to the next and never repeat.
            // We never want the next frame's starting frame time to end up being less than
            // or equal to the previous frame's commit frame time.  Keep in mind that the
            // next frame has most likely already been scheduled by now so we play it
            // safe by ensuring the commit time is always at least one frame behind.
            // 在提交此帧的时候，按需更新帧时间。
            // 如果在此时，超时多于2帧，则更新帧时间。
            // 这将保证被回调观察的帧时间将总是从这一帧增加到下一帧，如此边永远不会重复。
            // 我们从不期望下一帧的开始时间，小于或者等于上一帧的提交时间。
            // 记住下一帧此刻最有可能已经被调度了，所以我们通过确保提交时间总是至少落后于一个帧，来假定下一帧
            // 是安全的
            if (callbackType == Choreographer.CALLBACK_COMMIT) {
                final long jitterNanos = now - frameTimeNanos;
                Trace.traceCounter(Trace.TRACE_TAG_VIEW, "jitterNanos", (int) jitterNanos);
                if (jitterNanos >= 2 * mFrameIntervalNanos) {
                    final long lastFrameOffset = jitterNanos % mFrameIntervalNanos
                            + mFrameIntervalNanos;
                    if (DEBUG_JANK) {
                        Log.d(TAG, "Commit callback delayed by " + (jitterNanos * 0.000001f)
                                + " ms which is more than twice the frame interval of "
                                + (mFrameIntervalNanos * 0.000001f) + " ms!  "
                                + "Setting frame time to " + (lastFrameOffset * 0.000001f)
                                + " ms in the past.");
                        mDebugPrintNextFrameTimeDelta = true;
                    }
                    // 一般而言frameTimeNanos执行下条命令前的值，与执行后的值总是相差16ms
                    frameTimeNanos = now - lastFrameOffset;
                    mLastFrameTimeNanos = frameTimeNanos;
                }
            }
        }
        try {
            Trace.traceBegin(Trace.TRACE_TAG_VIEW, CALLBACK_TRACE_TITLES[callbackType]);
            for (CallbackRecord c = callbacks; c != null; c = c.next) {
                if (DEBUG_FRAMES) {
                    Log.d(TAG, "RunCallback: type=" + callbackType
                            + ", action=" + c.action + ", token=" + c.token
                            + ", latencyMillis=" + (SystemClock.uptimeMillis() - c.dueTime));
                }
                c.run(frameTimeNanos);
            }
        } finally {
            // 清空所有的回调
            synchronized (mLock) {
                mCallbacksRunning = false;
                do {
                    final CallbackRecord next = callbacks.next;
                    recycleCallbackLocked(callbacks);
                    callbacks = next;
                } while (callbacks != null);
            }
            Trace.traceEnd(Trace.TRACE_TAG_VIEW);
        }
    }

    void doScheduleVsync() {
        synchronized (mLock) {
            if (mFrameScheduled) {
                scheduleVsyncLocked();
            }
        }
    }

    void doScheduleCallback(int callbackType) {
        synchronized (mLock) {
            if (!mFrameScheduled) {
                final long now = SystemClock.uptimeMillis();
                if (mCallbackQueues[callbackType].hasDueCallbacksLocked(now)) {
                    scheduleFrameLocked(now);
                }
            }
        }
    }

    private void scheduleVsyncLocked() {
        mDisplayEventReceiver.scheduleVsync();
    }

    private boolean isRunningOnLooperThreadLocked() {
        return Looper.myLooper() == mLooper;
    }

    /**
     * 从回调对象池中，获取回调
     *
     * @param dueTime 截止时间
     * @param action
     * @param token
     * @return
     */
    private CallbackRecord obtainCallbackLocked(long dueTime, Object action, Object token) {
        CallbackRecord callback = mCallbackPool;
        if (callback == null) {
            callback = new CallbackRecord();
        } else {
            mCallbackPool = callback.next;
            callback.next = null;
        }
        callback.dueTime = dueTime;
        callback.action = action;
        callback.token = token;
        return callback;
    }

    /**
     * 回收回调对象
     *
     * @param callback
     */
    private void recycleCallbackLocked(CallbackRecord callback) {
        callback.action = null;
        callback.token = null;
        callback.next = mCallbackPool;
        mCallbackPool = callback;
    }

    /**
     * Implement this interface to receive a callback when a new display frame is
     * being rendered.  The callback is invoked on the {@link Looper} thread to
     * which the {@link Choreographer} is attached.
     * 实现此接口，用以在一个新的显示屏幕帧被渲染的时候，接受一个回调
     * 此回调将会在{@link Choreographer}创建时所粘合的Looper对象对应的线程之中执行
     */
    public interface FrameCallback {
        /**
         * Called when a new display frame is being rendered.
         * <p>
         * This method provides the time in nanoseconds when the frame started being rendered.
         * The frame time provides a stable time base for synchronizing animations
         * and drawing.  It should be used instead of {@link SystemClock#uptimeMillis()}
         * or {@link System#nanoTime()} for animations and drawing in the UI.  Using the frame
         * time helps to reduce inter-frame jitter because the frame time is fixed at the time
         * the frame was scheduled to start, regardless of when the animations or drawing
         * callback actually runs.  All callbacks that run as part of rendering a frame will
         * observe the same frame time so using the frame time also helps to synchronize effects
         * that are performed by different callbacks.
         * </p><p>
         * Please note that the framework already takes care to process animations and
         * drawing using the frame time as a stable time base.  Most applications should
         * not need to use the frame time information directly.
         * </p>
         * <p>
         * <p>
         * 当新的显示屏幕帧开始渲染的时候，调用此方法
         * 此方法提供了显示屏幕帧被渲染时的一个纳秒级别的时间。
         * 此时间为同步动画和绘制，提供一个稳定的时间依据。
         * 此时间应该被UI线程的动画和绘制操作，用来替换对{@link SystemClock#uptimeMillis()}
         * 或者{@link System#nanoTime()}方法的调用。
         * 使用此方法将被用来减少帧与帧之间的卡顿，因为时间被修复至帧开始的时间，并忽略了动画和绘制的实际
         * 运行时间。
         * 所有的作为渲染帧流程中的一部分的回调，以一个相同的帧开始时间为基准，所以使用相同的帧时间
         * 也将协助被不同回调引起的效果的同步。
         * framework已经关注了通过帧时间作为一个稳定的时间依据，来处理动画与绘制。
         * 所以应用最好不要直接修改帧信息
         * </p>
         *
         * @param frameTimeNanos The time in nanoseconds when the frame started being rendered,
         *                       in the {@link System#nanoTime()} timebase.  Divide this value by {@code 1000000}
         *                       to convert it to the {@link SystemClock#uptimeMillis()} time base.
         */
        public void doFrame(long frameTimeNanos);
    }

    private final class FrameHandler extends Handler {
        public FrameHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_DO_FRAME:
                    doFrame(System.nanoTime(), 0);
                    break;
                case MSG_DO_SCHEDULE_VSYNC:
                    doScheduleVsync();
                    break;
                case MSG_DO_SCHEDULE_CALLBACK:
                    doScheduleCallback(msg.arg1);
                    break;
            }
        }
    }

    private final class FrameDisplayEventReceiver extends DisplayEventReceiver
            implements Runnable {
        private boolean mHavePendingVsync;
        private long mTimestampNanos;
        private int mFrame;

        public FrameDisplayEventReceiver(Looper looper) {
            super(looper);
        }

        @Override
        public void onVsync(long timestampNanos, int builtInDisplayId, int frame) {
            // Ignore vsync from secondary display.
            // This can be problematic because the call to scheduleVsync() is a one-shot.
            // We need to ensure that we will still receive the vsync from the primary
            // display which is the one we really care about.  Ideally we should schedule
            // vsync for a particular display.
            // At this time Surface Flinger won't send us vsyncs for secondary displays
            // but that could change in the future so let's log a message to help us remember
            // that we need to fix this.
            // 忽略来自于第二显示屏幕上的垂直同步信号。
            // 这可能是有问题的，因为对于scheduleVsync()方法的调用是只有一次的。
            // 我们需要确保我们将接收到来自于最初的、我们已经关注的显示频幕的垂直同步信号。
            // 理想中，我们应该从一个指定的显示屏幕之中调度垂直同步信号。
            // 在这个时候，Surface Fling将不会发送第二个显示屏幕的垂直同步信号，
            // 但是这可能会在未来改变，所以让一个日志来记录我们需要在此处修复这个问题
            if (builtInDisplayId != SurfaceControl.BUILT_IN_DISPLAY_ID_MAIN) {
                Log.d(TAG, "Received vsync from secondary display, but we don't support "
                        + "this case yet.  Choreographer needs a way to explicitly request "
                        + "vsync for a specific display to ensure it doesn't lose track "
                        + "of its scheduled vsync.");
                scheduleVsync();
                return;
            }

            // Post the vsync event to the Handler.
            // The idea is to prevent incoming vsync events from completely starving
            // the message queue.  If there are no messages in the queue with timestamps
            // earlier than the frame time, then the vsync event will be processed immediately.
            // Otherwise, messages that predate the vsync event will be handled first.
            // 将垂直同步事件传递给Handler对象。
            // 如果在这个带有时间戳的队列之中，没有遭遇帧时间的消息，
            // 则垂直同步事件将会被立即处理。
            // 否者，先于此垂直同步事件的消息将被优先处理
            long now = System.nanoTime();
            if (timestampNanos > now) {
                Log.w(TAG, "Frame time is " + ((timestampNanos - now) * 0.000001f)
                        + " ms in the future!  Check that graphics HAL is generating vsync "
                        + "timestamps using the correct timebase.");
                timestampNanos = now;
            }

            if (mHavePendingVsync) {
                Log.w(TAG, "Already have a pending vsync event.  There should only be "
                        + "one at a time.");
            } else {
                mHavePendingVsync = true;
            }

            mTimestampNanos = timestampNanos;
            mFrame = frame;
            Message msg = Message.obtain(mHandler, this);
            msg.setAsynchronous(true);
            mHandler.sendMessageAtTime(msg, timestampNanos / TimeUtils.NANOS_PER_MS);
        }

        @Override
        public void run() {
            mHavePendingVsync = false;
            doFrame(mTimestampNanos, mFrame);
        }
    }

    private static final class CallbackRecord {
        public CallbackRecord next;
        public long dueTime;
        public Object action; // Runnable or FrameCallback
        public Object token;

        public void run(long frameTimeNanos) {
            if (token == FRAME_CALLBACK_TOKEN) {
                ((FrameCallback) action).doFrame(frameTimeNanos);
            } else {
                ((Runnable) action).run();
            }
        }
    }

    // 按照时间顺序排序的回调队列
    private final class CallbackQueue {
        private CallbackRecord mHead;

        public boolean hasDueCallbacksLocked(long now) {
            return mHead != null && mHead.dueTime <= now;
        }

        /**
         * 提取指定时间之前的所有回调
         *
         * @param now
         * @return
         */
        public CallbackRecord extractDueCallbacksLocked(long now) {
            CallbackRecord callbacks = mHead;
            if (callbacks == null || callbacks.dueTime > now) {
                return null;
            }

            CallbackRecord last = callbacks;
            CallbackRecord next = last.next;
            while (next != null) {
                if (next.dueTime > now) {
                    last.next = null;
                    break;
                }
                last = next;
                next = next.next;
            }
            mHead = next;
            return callbacks;
        }

        public void addCallbackLocked(long dueTime, Object action, Object token) {
            CallbackRecord callback = obtainCallbackLocked(dueTime, action, token);
            CallbackRecord entry = mHead;
            if (entry == null) {
                mHead = callback;
                return;
            }
            if (dueTime < entry.dueTime) {
                callback.next = entry;
                mHead = callback;
                return;
            }
            while (entry.next != null) {
                if (dueTime < entry.next.dueTime) {
                    callback.next = entry.next;
                    break;
                }
                entry = entry.next;
            }
            entry.next = callback;
        }

        public void removeCallbacksLocked(Object action, Object token) {
            CallbackRecord predecessor = null;
            for (CallbackRecord callback = mHead; callback != null; ) {
                final CallbackRecord next = callback.next;
                if ((action == null || callback.action == action)
                        && (token == null || callback.token == token)) {
                    if (predecessor != null) {
                        predecessor.next = next;
                    } else {
                        mHead = next;
                    }
                    recycleCallbackLocked(callback);
                } else {
                    predecessor = callback;
                }
                callback = next;
            }
        }
    }
}

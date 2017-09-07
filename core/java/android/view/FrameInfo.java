/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Class that contains all the timing information for the current frame. This
 * is used in conjunction with the hardware renderer to provide
 * continous-monitoring jank events
 * <p>
 * All times in nanoseconds from CLOCK_MONOTONIC/System.nanoTime()
 * <p>
 * To minimize overhead from System.nanoTime() calls we infer durations of
 * things by knowing the ordering of the events. For example, to know how
 * long layout & measure took it's displayListRecordStart - performTraversalsStart.
 * <p>
 * These constants must be kept in sync with FrameInfo.h in libhwui and are
 * used for indexing into AttachInfo's mFrameInfo long[], which is intended
 * to be quick to pass down to native via JNI, hence a pre-packed format
 * <p>
 * 包含了当前帧的所有计时信息以及一些额外的与视图树相关的元数据的类。
 * 此类与硬件渲染结合使用，用来提供持续监视的卡顿事件
 * 所有的时间都是纳秒级的
 * 为了最小化调用System.nanoTime()的开销，我们，通过知晓事件的属性，推断持续时间的事情
 * 例如,为了知道布局和测量花费了多长时间，我们将displayListRecordStart减去performTraversalsStart。
 * 这些常量必须同步保存在FrameInfo.h文件之中，并且被用来索引AttachInfo's mFrameInfo long[],
 * AttachInfo's mFrameInfo long[]被用来快速的访问native代码
 * <p>
 * </p>
 *
 * @hide
 */
final class FrameInfo {

    long[] mFrameInfo = new long[9];

    // Various flags set to provide extra metadata about the current frame
    // 多种的标识，标识当前帧的一些额外的元数据
    private static final int FLAGS = 0;

    // Is this the first-draw following a window layout?
    // 是否是第一次伴随着窗口布局而进行的绘制
    public static final long FLAG_WINDOW_LAYOUT_CHANGED = 1;

    @IntDef(flag = true, value = {
            FLAG_WINDOW_LAYOUT_CHANGED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FrameInfoFlags {
    }

    // The intended vsync time, unadjusted by jitter
    // 预期的垂直同步事件，没有被jitter调整
    private static final int INTENDED_VSYNC = 1;

    // Jitter-adjusted vsync time, this is what was used as input into the
    // animation & drawing system
    // jitter调整的垂直同步时间，被用来输入给动画和绘制系统
    private static final int VSYNC = 2;

    // The time of the oldest input event
    // 最老的输入事件的时间
    private static final int OLDEST_INPUT_EVENT = 3;

    // The time of the newest input event
    // 最新的输入事件时间
    private static final int NEWEST_INPUT_EVENT = 4;

    // When input event handling started
    // 输入事件开始处理的时间
    private static final int HANDLE_INPUT_START = 5;

    // When animation evaluations started
    // 动画预估开始的时间
    private static final int ANIMATION_START = 6;

    // When ViewRootImpl#performTraversals() started
    // 开始执行遍历的时间
    private static final int PERFORM_TRAVERSALS_START = 7;

    // When View:draw() started
    // 视图绘制的时间
    private static final int DRAW_START = 8;

    /**
     * @param intendedVsync 预期的垂直同步信号时间
     * @param usedVsync     可能经过调整的垂直同步信号时间
     */
    public void setVsync(long intendedVsync, long usedVsync) {
        mFrameInfo[INTENDED_VSYNC] = intendedVsync;
        mFrameInfo[VSYNC] = usedVsync;
        mFrameInfo[OLDEST_INPUT_EVENT] = Long.MAX_VALUE;
        mFrameInfo[NEWEST_INPUT_EVENT] = 0;
        mFrameInfo[FLAGS] = 0;
    }

    public void updateInputEventTime(long inputEventTime, long inputEventOldestTime) {
        if (inputEventOldestTime < mFrameInfo[OLDEST_INPUT_EVENT]) {
            mFrameInfo[OLDEST_INPUT_EVENT] = inputEventOldestTime;
        }
        if (inputEventTime > mFrameInfo[NEWEST_INPUT_EVENT]) {
            mFrameInfo[NEWEST_INPUT_EVENT] = inputEventTime;
        }
    }

    public void markInputHandlingStart() {
        mFrameInfo[HANDLE_INPUT_START] = System.nanoTime();
    }

    public void markAnimationsStart() {
        mFrameInfo[ANIMATION_START] = System.nanoTime();
    }

    public void markPerformTraversalsStart() {
        mFrameInfo[PERFORM_TRAVERSALS_START] = System.nanoTime();
    }

    public void markDrawStart() {
        mFrameInfo[DRAW_START] = System.nanoTime();
    }

    public void addFlags(@FrameInfoFlags long flags) {
        mFrameInfo[FLAGS] |= flags;
    }

}

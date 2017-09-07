/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.Manifest;
import android.animation.LayoutTransition;
import android.annotation.NonNull;
import android.app.ActivityManagerNative;
import android.app.ResourcesManager;
import android.content.ClipDescription;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManager.DisplayListener;
import android.hardware.input.InputManager;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.util.AndroidRuntimeException;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Slog;
import android.util.TimeUtils;
import android.util.TypedValue;
import android.view.Surface.OutOfResourcesException;
import android.view.View.AttachInfo;
import android.view.View.MeasureSpec;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityManager.AccessibilityStateChangeListener;
import android.view.accessibility.AccessibilityManager.HighTextContrastChangeListener;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.accessibility.IAccessibilityInteractionConnection;
import android.view.accessibility.IAccessibilityInteractionConnectionCallback;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.Scroller;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.IResultReceiver;
import com.android.internal.os.SomeArgs;
import com.android.internal.policy.PhoneFallbackEventHandler;
import com.android.internal.view.BaseSurfaceHolder;
import com.android.internal.view.RootViewSurfaceTaker;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;

import static android.view.WindowCallbacks.RESIZE_MODE_DOCKED_DIVIDER;
import static android.view.WindowCallbacks.RESIZE_MODE_FREEFORM;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_DECOR_VIEW_VISIBILITY;
import static android.view.WindowManager.LayoutParams.TYPE_DOCK_DIVIDER;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY;

/**
 * The top of a view hierarchy, implementing the needed protocol between View
 * and the WindowManager.  This is for the most part an internal implementation
 * detail of {@link WindowManagerGlobal}.
 * 视图树的顶部，实现了在视图与窗口管理器之间需要的约定。
 * 内部使用的类；
 * 1、与WMS通信；
 * 2、构建遍历视图树的框架与流程；
 * 3、构建输入事件的接受及处理框架；
 * {@hide}
 */
@SuppressWarnings({"EmptyCatchBlock", "PointlessBooleanExpression"})
public final class ViewRootImpl implements ViewParent,
        View.AttachInfo.Callbacks, ThreadedRenderer.HardwareDrawCallbacks {
    private static final String TAG = "ViewRootImpl";
    private static final boolean DBG = false;
    private static final boolean LOCAL_LOGV = false;
    /**
     * @noinspection PointlessBooleanExpression
     */
    private static final boolean DEBUG_DRAW = false || LOCAL_LOGV;
    private static final boolean DEBUG_LAYOUT = false || LOCAL_LOGV;
    private static final boolean DEBUG_DIALOG = false || LOCAL_LOGV;
    private static final boolean DEBUG_INPUT_RESIZE = false || LOCAL_LOGV;
    private static final boolean DEBUG_ORIENTATION = false || LOCAL_LOGV;
    private static final boolean DEBUG_TRACKBALL = false || LOCAL_LOGV;
    private static final boolean DEBUG_IMF = false || LOCAL_LOGV;
    private static final boolean DEBUG_CONFIGURATION = false || LOCAL_LOGV;
    private static final boolean DEBUG_FPS = false;
    private static final boolean DEBUG_INPUT_STAGES = false || LOCAL_LOGV;
    private static final boolean DEBUG_KEEP_SCREEN_ON = false || LOCAL_LOGV;

    /**
     * Set to false if we do not want to use the multi threaded renderer. Note that by disabling
     * this, WindowCallbacks will not fire.
     * 如果我们不想使用多线程来进行渲染，则将此值设置为false。
     */
    private static final boolean USE_MT_RENDERER = true;

    /**
     * Set this system property to true to force the view hierarchy to render
     * at 60 Hz. This can be used to measure the potential(潜在的、可能的、潜力) framerate.
     * 将此系统属性设置为true，以此强制要求视图树以一秒60次的速度渲染（更新）。
     * 这将被用来估量潜在的帧速
     */
    private static final String PROPERTY_PROFILE_RENDERING = "viewroot.profile_rendering";

    // properties used by emulator to determine display shape
    // 被模拟器使用的，用来决定显示器形状的属性
    public static final String PROPERTY_EMULATOR_WIN_OUTSET_BOTTOM_PX =
            "ro.emu.win_outset_bottom_px";

    /**
     * Maximum time we allow the user to roll the trackball enough to generate
     * a key event, before resetting the counters.
     * 在重置计数器之前，允许用户滚动轨迹球，以充分保证按键事件，的最大时间，
     */
    static final int MAX_TRACKBALL_DELAY = 250;

    /**
     * 线程级变量
     * 在没有handler的情况下，线程消息存储的代替类
     */
    static final ThreadLocal<HandlerActionQueue> sRunQueues = new ThreadLocal<HandlerActionQueue>();

    /**
     * 跟随第一次绘制，运行的runnable对象？
     */
    static final ArrayList<Runnable> sFirstDrawHandlers = new ArrayList();

    /**
     * 第一次绘制是否完成
     */
    static boolean sFirstDrawComplete = false;

    static final ArrayList<ComponentCallbacks> sConfigCallbacks = new ArrayList();

    /**
     * This list must only be modified by the main thread, so a lock is only needed when changing
     * the list or when accessing the list from a non-main thread.
     * 此列表只能被主线程修改，
     * 所以在改变此list以及从一个非主线程修改此列表的时候，需要一个锁
     */
    @GuardedBy("mWindowCallbacks")
    final ArrayList<WindowCallbacks> mWindowCallbacks = new ArrayList<>();

    final Context mContext;

    /**
     * 此对象是应用在WMS之中的一个映射？
     */
    final IWindowSession mWindowSession;

    /**
     * 显示屏幕信息？
     */
    @NonNull
    Display mDisplay;

    /**
     * 显示屏幕管理器？
     */
    final DisplayManager mDisplayManager;

    /**
     * 基本包名
     */
    final String mBasePackageName;

    final int[] mTmpLocation = new int[2];

    final TypedValue mTmpValue = new TypedValue();

    final Thread mThread;

    final WindowLeaked mLocation;

    /**
     * 窗口布局参数
     */
    final WindowManager.LayoutParams mWindowAttributes = new WindowManager.LayoutParams();

    /**
     * 主要与WMS进行交互
     */
    final W mWindow;

    /**
     * 当前Android SDK的版本号
     */
    final int mTargetSdkVersion;

    int mSeq;

    /**
     * 视图树之中最顶级的视图
     */
    View mView;

    View mAccessibilityFocusedHost;
    AccessibilityNodeInfo mAccessibilityFocusedVirtualView;

    // The view which captures mouse input, or null when no one is capturing.
    /**
     * 获取鼠标输入的视图，
     * 如果没有这样的视图，则为空
     */
    View mCapturingView;

    /**
     * 视图当前的可视状态
     */
    int mViewVisibility;

    /**
     * 应用是否可视
     */
    boolean mAppVisible = true;
    // For recents（最近的） to freeform（自由的、任意的） transition we need to keep drawing after the app receives information
    // that it became invisible. This will ignore that information and depend on the decor view
    // visibility to control drawing. The decor view visibility will get adjusted when the app get
    // stopped and that's when the app will stop drawing further frames.
    /**
     * 对于最近的任意的动画，我们需要保持绘制，在视图接收到不可见的信息之后。
     * 会忽略这样的信息，并且根据decor视图的可视性来控制绘制。
     * 当应用停止时，decor视图的可视性将会调整，并且这就是应用将停止绘制未来的帧的时候.
     * 如果为true，则标识窗口的可视状态完全由其内部视图的可视状态来决定
     */
    private boolean mForceDecorViewVisibility = false;

    /**
     * 初始的窗口类型
     */
    int mOrigWindowType = -1;

    /**
     * Whether the window had focus during the most recent traversal.
     * 在最新的遍历之中，窗口是否有焦点
     */
    boolean mHadWindowFocus;

    /**
     * Whether the window lost focus during a previous traversal and has not
     * yet gained it back. Used to determine whether a WINDOW_STATE_CHANGE
     * accessibility events should be sent during traversal.
     * 在上一个遍历之中，窗口是否失去了焦点，并且不在重新获得。
     * 此值被用来决定，在遍历期间，是否需要将一个窗口状态改变
     * 的辅助事件发送
     */
    boolean mLostWindowFocus;

    // Set to true if the owner of this window is in the stopped state,
    // so the window should no longer be active.
    /**
     * 此窗口的拥有者处于一个停止状态了，则此值变回被设置为true,
     * 因此对应的窗口将不在活跃
     */
    boolean mStopped = false;

    // Set to true if the owner of this window is in ambient mode,
    // which means it won't receive input events.
    /**
     * 如果为true，则表示此窗口将不会接受到输入事件
     */
    boolean mIsAmbientMode = false;

    // Set to true to stop input during an Activity Transition.
    /**
     * 在一个activity动画期间，会将此值设置为true，以此停止接受输入事件
     */
    boolean mPausedForTransition = false;

    /**
     * 是否在兼容模式之宗？
     */
    boolean mLastInCompatMode = false;

    SurfaceHolder.Callback2 mSurfaceHolderCallback;
    BaseSurfaceHolder mSurfaceHolder;

    /**
     * 是否创建了
     */
    boolean mIsCreating;

    /**
     * 是否允许绘制
     */
    boolean mDrawingAllowed;

    /**
     * 透明区域
     */
    final Region mTransparentRegion;

    /**
     * 上一次的透明区域
     */
    final Region mPreviousTransparentRegion;

    /**
     * 视图宽度？
     */
    int mWidth;

    /**
     * 视图高度？
     */
    int mHeight;

    /**
     * 脏区域
     */
    Rect mDirty;

    /**
     * 是否正在动画中
     */
    boolean mIsAnimating;

    /**
     * 是否正在通过拖拽调整大小？
     */
    private boolean mDragResizing;

    /**
     * 是否进行了无效视图树的请求？
     */
    private boolean mInvalidateRootRequested;

    /**
     * 调整模式
     */
    private int mResizeMode;

    /**
     * 画布在x轴上的偏移量
     */
    private int mCanvasOffsetX;

    /**
     * 画布在y轴上的偏移量
     */
    private int mCanvasOffsetY;

    /**
     * activity是否重启了
     */
    private boolean mActivityRelaunched;

    /**
     * 用于兼容性的转换器
     */
    CompatibilityInfo.Translator mTranslator;

    /**
     * 视图树的粘贴信息
     */
    final View.AttachInfo mAttachInfo;

    /**
     * 接收输入事件的通道
     */
    InputChannel mInputChannel;

    /**
     * 输入事件队列回调;
     * 包含输入队列创建回调
     * 以及输入队列销毁回调
     */
    InputQueue.Callback mInputQueueCallback;

    /**
     * 输入事件队列
     */
    InputQueue mInputQueue;

    /**
     * 回调事件处理器？
     */
    FallbackEventHandler mFallbackEventHandler;

    /**
     * 视图绘制的总设计师,绘制导演
     * 推测与黄油计划相关？
     * 推测与Surfacefiling服务相关？
     */
    Choreographer mChoreographer;

    /**
     * 在事务之中使用，从而不浪费虚拟机堆
     */
    final Rect mTempRect; // used in the transaction to not thrash the heap.

    /**
     * 被用来恢复焦点视图的可视区域
     */
    final Rect mVisRect; // used to retrieve visible rect of focused view.

    /**
     * 是否进行了遍历调用
     */
    boolean mTraversalScheduled;

    /**
     * 遍历栅栏
     */
    int mTraversalBarrier;

    /**
     * 即将绘制
     */
    boolean mWillDrawSoon;
    /**
     * Set to true while in performTraversals for detecting when die(true) is called from internal
     * callbacks such as onMeasure, onPreDraw, onDraw and deferring doDie() until later.
     * 在执行遍历的过程之中，此值被设置为true;
     * 此属性被用来探测，来自于内部（例如onMeasure, onPreDraw, onDraw方法）的die(true)方法调用，
     * 并且用以延迟由此引起的doDie方法的调用。
     */
    boolean mIsInTraversal;

    /**
     * 请求是否需要提供系统窗口相关边衬
     */
    boolean mApplyInsetsRequested;

    /**
     * 是否进行了布局请求
     */
    boolean mLayoutRequested;

    /**
     * 是否第一次
     */
    boolean mFirst;

    /**
     * 是否报告下一次绘制
     */
    boolean mReportNextDraw;

    /**
     * 是否需要全屏重绘
     */
    boolean mFullRedrawNeeded;

    /**
     * 是否需要新的Surface对象
     */
    boolean mNewSurfaceNeeded;

    /**
     * 是否窗口焦点
     */
    boolean mHasHadWindowFocus;

    /**
     * 上一次是否存在输入法目标
     */
    boolean mLastWasImTarget;

    /**
     * 是否在下次遍历的过程之中，强制窗口重布局
     */
    boolean mForceNextWindowRelayout;

    /**
     * 窗口绘制倒计时门阀
     * 运行一个或者多个线程等待,
     * 直到一系列操作在其他线程完成
     */
    CountDownLatch mWindowDrawCountDown;

    /**
     * 是否正在绘制之中
     */
    boolean mIsDrawing;

    /**
     * 上一次系统UI的可视状态
     */
    int mLastSystemUiVisibility;

    /**
     * 客户端提供的窗口布局标识位
     */
    int mClientWindowLayoutFlags;

    /**
     * 此处布局生效前，窗口是否请求扩展至overscan区域
     */
    boolean mLastOverscanRequested;

    // Pool of queued input events.
    // 队列化的输入事件池

    /**
     * 最大输入事件池的数量
     */
    private static final int MAX_QUEUED_INPUT_EVENT_POOL_SIZE = 10;

    /**
     * 输入事件池
     */
    private QueuedInputEvent mQueuedInputEventPool;
    /**
     * 输入事件池的当前个数
     */
    private int mQueuedInputEventPoolSize;

    /* Input event queue.
     * Pending input events are input events waiting to be delivered to the input stages
     * and handled by the application.
     * 输入事件队列
     * 即将执行的输入事件是这些等待被调度至输入阶段，以及被应用处理的输入事件
     */
    QueuedInputEvent mPendingInputEventHead;
    QueuedInputEvent mPendingInputEventTail;

    /**
     * 即将执行的输入事件的个数
     */
    int mPendingInputEventCount;

    /**
     * 是否处理输入事件调度
     */
    boolean mProcessInputEventsScheduled;

    /**
     * 是否进行无缓冲的输入事件分配
     */
    boolean mUnbufferedInputDispatch;

    /**
     * 即将执行输入事件队列长度计数器名称
     */
    String mPendingInputEventQueueLengthCounterName = "pq";

    // 输入事件执行阶段
    /**
     * 首先执行的阶段
     */
    InputStage mFirstInputStage;
    /**
     * 输入法之后的执行阶段
     */
    InputStage mFirstPostImeInputStage;
    /**
     * 人工合成阶段
     */
    InputStage mSyntheticInputStage;

    /**
     * 窗口参数是否发生改变
     */
    boolean mWindowAttributesChanged = false;

    /**
     * 发生了改变的窗口参数标志位
     */
    int mWindowAttributesChangesFlag = 0;

    // These can be accessed by any thread, must be protected with a lock.
    // 这些属性能够被任意线程访问，必须使用锁保护。
    // Surface can never be reassigned or cleared (use Surface.clear()).
    /**
     * surface永远不会被重新分配或清除？
     */
    final Surface mSurface = new Surface();

    /**
     * 是否添加到WMS
     */
    boolean mAdded;

    /**
     * 是否添加了触摸模式
     */
    boolean mAddedTouchMode;

    // These are accessed by multiple threads.
    // 以下的Rect对象被多个线程访问

    /**
     * 被WMS计算出来的窗口范文大小
     */
    final Rect mWinFrame; // frame given by window manager.

    /**
     * 整个屏幕的边衬大小，由WMS计算而出，还未生效
     */
    final Rect mPendingOverscanInsets = new Rect();
    /**
     * 窗口可见区域的边村大小，由WMS计算而出，还未生效
     */
    final Rect mPendingVisibleInsets = new Rect();
    final Rect mPendingStableInsets = new Rect();
    /**
     * 窗口内容区域的边衬大小，由WMS计算而出，还未生效
     */
    final Rect mPendingContentInsets = new Rect();
    final Rect mPendingOutsets = new Rect();
    final Rect mPendingBackDropFrame = new Rect();

    /**
     * 每次布局是否都需要消耗导航栏？
     */
    boolean mPendingAlwaysConsumeNavBar;
    /**
     * 供视图树观察者使用的内部边衬信息
     */
    final ViewTreeObserver.InternalInsetsInfo mLastGivenInsets
            = new ViewTreeObserver.InternalInsetsInfo();

    final Rect mDispatchContentInsets = new Rect();
    final Rect mDispatchStableInsets = new Rect();

    private WindowInsets mLastWindowInsets;

    final Configuration mLastConfiguration = new Configuration();
    final Configuration mPendingConfiguration = new Configuration();

    /**
     * 是否滚动偏移量即将改变
     */
    boolean mScrollMayChange;
    /**
     * 输入法模式
     */
    int mSoftInputMode;

    /**
     * 上一次滚动的焦点视图
     */
    WeakReference<View> mLastScrolledFocus;

    int mScrollY;
    /**
     * 当前的滚动Y值
     */
    int mCurScrollY;
    /**
     * 滚动器
     */
    Scroller mScroller;

    /**
     * 速度器，加、减速器
     */
    static final Interpolator mResizeInterpolator = new AccelerateDecelerateInterpolator();
    /**
     * 未执行的动画
     * 与布局动画相关？
     */
    private ArrayList<LayoutTransition> mPendingTransitions;

    final ViewConfiguration mViewConfiguration;

    /* Drag/drop */
    ClipDescription mDragDescription;

    /**
     * 当前拖拽的视图
     */
    View mCurrentDragView;

    /**
     * 本地拖拽状态
     */
    volatile Object mLocalDragState;

    /**
     * 拖拽点
     */
    final PointF mDragPoint = new PointF();

    /**
     * 上一次触摸点
     */
    final PointF mLastTouchPoint = new PointF();

    /**
     * 上一次的触摸源
     */
    int mLastTouchSource;

    /**
     * 是否进行了轮廓渲染？
     */
    private boolean mProfileRendering;

    /**
     * 轮廓渲染帧回调？
     */
    private Choreographer.FrameCallback mRenderProfiler;

    /**
     * 是否启用了轮廓渲染
     */
    private boolean mRenderProfilingEnabled;

    // Variables to track frames per second, enabled via DEBUG_FPS flag
    // 被用来追踪每一秒的帧的变量，通过DEBUG_FPS标识来启用
    /**
     * 追踪帧数的开始时间？
     */
    private long mFpsStartTime = -1;
    /**
     * 追踪帧数的结束时间？
     */
    private long mFpsPrevTime = -1;
    /**
     * 追踪时间内的总帧数？
     */
    private int mFpsNumFrames;

    /**
     * 鼠标指针的ICON类型
     */
    private int mPointerIconType = PointerIcon.TYPE_NOT_SPECIFIED;
    /**
     * 鼠标指针ICON
     */
    private PointerIcon mCustomPointerIcon = null;

    /**
     * see {@link #playSoundEffect(int)}
     */
    AudioManager mAudioManager;

    final AccessibilityManager mAccessibilityManager;

    AccessibilityInteractionController mAccessibilityInteractionController;

    AccessibilityInteractionConnectionManager mAccessibilityInteractionConnectionManager;
    HighContrastTextManager mHighContrastTextManager;

    SendWindowContentChangedAccessibilityEvent mSendWindowContentChangedAccessibilityEvent;

    HashSet<View> mTempHashSet;

    /**
     * 密度
     */
    private final int mDensity;
    /**
     * 非兼容性的密度
     */
    private final int mNoncompatDensity;

    /**
     * 是否处于布局之中
     */
    private boolean mInLayout = false;

    /**
     * 布局请求者
     */
    ArrayList<View> mLayoutRequesters = new ArrayList<View>();

    /**
     * 是否在布局之中处理布局请求
     */
    boolean mHandlingLayoutInLayoutRequest = false;

    /**
     * 初始的视图布局方向
     */
    private int mViewLayoutDirectionInitial;

    /**
     * Set to true once doDie() has been called.
     * doDie()方法被调用时，将此属性设置为true
     */
    private boolean mRemoved;

    /**
     * 是否需要安装硬件渲染器
     */
    private boolean mNeedsHwRendererSetup;

    /**
     * Consistency verifier for debugging purposes.
     * 调试目的的连续验证器
     */
    protected final InputEventConsistencyVerifier mInputEventConsistencyVerifier =
            InputEventConsistencyVerifier.isInstrumentationEnabled() ?
                    new InputEventConsistencyVerifier(this, 0) : null;

    static final class SystemUiVisibilityInfo {
        int seq;
        int globalVisibility;
        int localValue;
        int localChanges;
    }

    private String mTag = TAG;

    public ViewRootImpl(Context context, Display display) {
        mContext = context;
        mWindowSession = WindowManagerGlobal.getWindowSession();
        mDisplay = display;
        mBasePackageName = context.getBasePackageName();
        mThread = Thread.currentThread();
        mLocation = new WindowLeaked(null);
        mLocation.fillInStackTrace();
        mWidth = -1;
        mHeight = -1;
        mDirty = new Rect();
        mTempRect = new Rect();
        mVisRect = new Rect();
        mWinFrame = new Rect();
        mWindow = new W(this);
        mTargetSdkVersion = context.getApplicationInfo().targetSdkVersion;
        mViewVisibility = View.GONE;
        mTransparentRegion = new Region();
        mPreviousTransparentRegion = new Region();
        mFirst = true; // true for the first time the view is added
        mAdded = false;
        mAttachInfo = new View.AttachInfo(mWindowSession, mWindow, display, this, mHandler, this);
        mAccessibilityManager = AccessibilityManager.getInstance(context);
        mAccessibilityInteractionConnectionManager =
                new AccessibilityInteractionConnectionManager();
        mAccessibilityManager.addAccessibilityStateChangeListener(
                mAccessibilityInteractionConnectionManager);
        mHighContrastTextManager = new HighContrastTextManager();
        mAccessibilityManager.addHighTextContrastStateChangeListener(
                mHighContrastTextManager);
        mViewConfiguration = ViewConfiguration.get(context);
        mDensity = context.getResources().getDisplayMetrics().densityDpi;
        mNoncompatDensity = context.getResources().getDisplayMetrics().noncompatDensityDpi;
        mFallbackEventHandler = new PhoneFallbackEventHandler(context);
        mChoreographer = Choreographer.getInstance();
        mDisplayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        loadSystemProperties();
    }

    public static void addFirstDrawHandler(Runnable callback) {
        synchronized (sFirstDrawHandlers) {
            if (!sFirstDrawComplete) {
                sFirstDrawHandlers.add(callback);
            }
        }
    }

    public static void addConfigCallback(ComponentCallbacks callback) {
        synchronized (sConfigCallbacks) {
            sConfigCallbacks.add(callback);
        }
    }

    public void addWindowCallbacks(WindowCallbacks callback) {
        if (USE_MT_RENDERER) {
            synchronized (mWindowCallbacks) {
                mWindowCallbacks.add(callback);
            }
        }
    }

    public void removeWindowCallbacks(WindowCallbacks callback) {
        if (USE_MT_RENDERER) {
            synchronized (mWindowCallbacks) {
                mWindowCallbacks.remove(callback);
            }
        }
    }

    public void reportDrawFinish() {
        if (mWindowDrawCountDown != null) {
            mWindowDrawCountDown.countDown();
        }
    }

    // FIXME for perf testing only
    private boolean mProfile = false;

    /**
     * Call this to profile the next traversal call.
     * FIXME for perf testing only. Remove eventually
     */
    public void profile() {
        mProfile = true;
    }

    /**
     * Indicates whether we are in touch mode. Calling this method triggers an IPC
     * call and should be avoided whenever possible.
     * 标识是否我们正处于触摸模式之中。
     * 调用此方法将会触发一次IPC调用，索引应该尽量避免此方法的调用。
     *
     * @return True, if the device is in touch mode, false otherwise.
     * @hide
     */
    static boolean isInTouchMode() {
        IWindowSession windowSession = WindowManagerGlobal.peekWindowSession();
        if (windowSession != null) {
            try {
                return windowSession.getInTouchMode();
            } catch (RemoteException e) {
            }
        }
        return false;
    }

    /**
     * Notifies us that our child has been rebuilt, following
     * a window preservation(保护、保留) operation. In these cases we
     * keep the same DecorView, but the activity controlling it
     * is a different instance, and we need to update our
     * callbacks.
     * 通知我们，视图树中的子视图被重建了，伴随着一个窗口保留操作。
     * 在这些情况下，我们将保持相同的decor view，
     * 但是正在控制此decor view的activity将会是另一个实例，
     * 所以我们需要更新我们的回调
     *
     * @hide
     */
    public void notifyChildRebuilt() {
        if (mView instanceof RootViewSurfaceTaker) {
            mSurfaceHolderCallback =
                    ((RootViewSurfaceTaker) mView).willYouTakeTheSurface();
            if (mSurfaceHolderCallback != null) {
                mSurfaceHolder = new TakenSurfaceHolder();
                mSurfaceHolder.setFormat(PixelFormat.UNKNOWN);
            } else {
                mSurfaceHolder = null;
            }

            mInputQueueCallback =
                    ((RootViewSurfaceTaker) mView).willYouTakeTheInputQueue();
            if (mInputQueueCallback != null) {
                mInputQueueCallback.onInputQueueCreated(mInputQueue);
            }
        }
    }

    /**
     * We have one child
     * 主要是将decor视图添加到WMS之中，并根据WMS的返回逻辑更新相应的信息，最后会建立输入事件的处理链表
     */
    public void setView(View view, WindowManager.LayoutParams attrs, View panelParentView) {
        synchronized (this) {
            if (mView == null) {
                mView = view;

                mAttachInfo.mDisplayState = mDisplay.getState();
                mDisplayManager.registerDisplayListener(mDisplayListener, mHandler);

                mViewLayoutDirectionInitial = mView.getRawLayoutDirection();
                mFallbackEventHandler.setView(view);
                mWindowAttributes.copyFrom(attrs);
                if (mWindowAttributes.packageName == null) {
                    mWindowAttributes.packageName = mBasePackageName;
                }
                attrs = mWindowAttributes;
                setTag();

                if (DEBUG_KEEP_SCREEN_ON && (mClientWindowLayoutFlags
                        & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
                        && (attrs.flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) == 0) {
                    Slog.d(mTag, "setView: FLAG_KEEP_SCREEN_ON changed from true to false!");
                }
                // Keep track of the actual window flags supplied by the client.
                mClientWindowLayoutFlags = attrs.flags;

                setAccessibilityFocus(null, null);

                if (view instanceof RootViewSurfaceTaker) {
                    mSurfaceHolderCallback =
                            ((RootViewSurfaceTaker) view).willYouTakeTheSurface();
                    if (mSurfaceHolderCallback != null) {
                        mSurfaceHolder = new TakenSurfaceHolder();
                        mSurfaceHolder.setFormat(PixelFormat.UNKNOWN);
                    }
                }

                // Compute surface insets required to draw at specified Z value.
                // 计算在指定z轴上绘制的surface边衬大小
                // TODO: Use real shadow insets for a constant max Z.
                if (!attrs.hasManualSurfaceInsets) {
                    attrs.setSurfaceInsets(view, false /*manual*/, true /*preservePrevious*/);
                }

                CompatibilityInfo compatibilityInfo =
                        mDisplay.getDisplayAdjustments().getCompatibilityInfo();
                mTranslator = compatibilityInfo.getTranslator();

                // If the application owns the surface, don't enable hardware acceleration
                // 如果应用拥有一个surface，则不要启用硬件加速
                if (mSurfaceHolder == null) {
                    enableHardwareAcceleration(attrs);
                }

                boolean restore = false;
                // 恢复
                if (mTranslator != null) {
                    mSurface.setCompatibilityTranslator(mTranslator);
                    restore = true;
                    attrs.backup();
                    mTranslator.translateWindowLayout(attrs);
                }
                if (DEBUG_LAYOUT) Log.d(mTag, "WindowLayout in setView:" + attrs);

                if (!compatibilityInfo.supportsScreen()) {
                    // 需要运行在屏幕尺寸兼容模式之中
                    attrs.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_COMPATIBLE_WINDOW;
                    mLastInCompatMode = true;
                }

                mSoftInputMode = attrs.softInputMode;
                mWindowAttributesChanged = true;
                mWindowAttributesChangesFlag = WindowManager.LayoutParams.EVERYTHING_CHANGED;
                mAttachInfo.mRootView = view;
                mAttachInfo.mScalingRequired = mTranslator != null;
                mAttachInfo.mApplicationScale =
                        mTranslator == null ? 1.0f : mTranslator.applicationScale;
                // 父视图不为空
                if (panelParentView != null) {
                    mAttachInfo.mPanelParentWindowToken
                            = panelParentView.getApplicationWindowToken();
                }
                mAdded = true;
                int res; /* = WindowManagerImpl.ADD_OKAY; */

                // Schedule the first layout -before- adding to the window
                // manager, to make sure we do the relayout before receiving
                // any other events from the system.
                // 在添加到WMS之前，调度第一次布局，用以确保在接受到其他来自于系统的事件之后
                requestLayout();
                if ((mWindowAttributes.inputFeatures
                        & WindowManager.LayoutParams.INPUT_FEATURE_NO_INPUT_CHANNEL) == 0) {
                    mInputChannel = new InputChannel();
                }
                mForceDecorViewVisibility = (mWindowAttributes.privateFlags
                        & PRIVATE_FLAG_FORCE_DECOR_VIEW_VISIBILITY) != 0;
                try {
                    mOrigWindowType = mWindowAttributes.type;
                    mAttachInfo.mRecomputeGlobalAttributes = true;
                    collectViewAttributes();
                    // 开始添加窗口至WMS
                    res = mWindowSession.addToDisplay(mWindow, mSeq, mWindowAttributes,
                            getHostVisibility(), mDisplay.getDisplayId(),
                            mAttachInfo.mContentInsets, mAttachInfo.mStableInsets,
                            mAttachInfo.mOutsets, mInputChannel);
                } catch (RemoteException e) {
                    mAdded = false;
                    mView = null;
                    mAttachInfo.mRootView = null;
                    mInputChannel = null;
                    mFallbackEventHandler.setView(null);
                    unscheduleTraversals();
                    setAccessibilityFocus(null, null);
                    throw new RuntimeException("Adding window failed", e);
                } finally {
                    if (restore) {
                        attrs.restore();
                    }
                }

                if (mTranslator != null) {
                    mTranslator.translateRectInScreenToAppWindow(mAttachInfo.mContentInsets);
                }
                // 根据WMS返回过来的信息，更新相关的边衬大小
                mPendingOverscanInsets.set(0, 0, 0, 0);
                mPendingContentInsets.set(mAttachInfo.mContentInsets);
                mPendingStableInsets.set(mAttachInfo.mStableInsets);
                mPendingVisibleInsets.set(0, 0, 0, 0);
                mAttachInfo.mAlwaysConsumeNavBar =
                        (res & WindowManagerGlobal.ADD_FLAG_ALWAYS_CONSUME_NAV_BAR) != 0;
                mPendingAlwaysConsumeNavBar = mAttachInfo.mAlwaysConsumeNavBar;
                if (DEBUG_LAYOUT) Log.v(mTag, "Added window " + mWindow);
                if (res < WindowManagerGlobal.ADD_OKAY) {
                    // 添加窗口至WMS失败
                    mAttachInfo.mRootView = null;
                    mAdded = false;
                    mFallbackEventHandler.setView(null);
                    unscheduleTraversals();
                    setAccessibilityFocus(null, null);
                    switch (res) {
                        case WindowManagerGlobal.ADD_BAD_APP_TOKEN:
                        case WindowManagerGlobal.ADD_BAD_SUBWINDOW_TOKEN:
                            throw new WindowManager.BadTokenException(
                                    "Unable to add window -- token " + attrs.token
                                            + " is not valid; is your activity running?");
                        case WindowManagerGlobal.ADD_NOT_APP_TOKEN:
                            throw new WindowManager.BadTokenException(
                                    "Unable to add window -- token " + attrs.token
                                            + " is not for an application");
                        case WindowManagerGlobal.ADD_APP_EXITING:
                            throw new WindowManager.BadTokenException(
                                    "Unable to add window -- app for token " + attrs.token
                                            + " is exiting");
                        case WindowManagerGlobal.ADD_DUPLICATE_ADD:
                            throw new WindowManager.BadTokenException(
                                    "Unable to add window -- window " + mWindow
                                            + " has already been added");
                        case WindowManagerGlobal.ADD_STARTING_NOT_NEEDED:
                            // Silently ignore -- we would have just removed it
                            // right away, anyway.
                            return;
                        case WindowManagerGlobal.ADD_MULTIPLE_SINGLETON:
                            throw new WindowManager.BadTokenException("Unable to add window "
                                    + mWindow + " -- another window of type "
                                    + mWindowAttributes.type + " already exists");
                        case WindowManagerGlobal.ADD_PERMISSION_DENIED:
                            throw new WindowManager.BadTokenException("Unable to add window "
                                    + mWindow + " -- permission denied for window type "
                                    + mWindowAttributes.type);
                        case WindowManagerGlobal.ADD_INVALID_DISPLAY:
                            throw new WindowManager.InvalidDisplayException("Unable to add window "
                                    + mWindow + " -- the specified display can not be found");
                        case WindowManagerGlobal.ADD_INVALID_TYPE:
                            throw new WindowManager.InvalidDisplayException("Unable to add window "
                                    + mWindow + " -- the specified window type "
                                    + mWindowAttributes.type + " is not valid");
                    }
                    throw new RuntimeException(
                            "Unable to add window -- unknown error code " + res);
                }

                if (view instanceof RootViewSurfaceTaker) {
                    mInputQueueCallback =
                            ((RootViewSurfaceTaker) view).willYouTakeTheInputQueue();
                }
                // 更新输入事件接受器
                if (mInputChannel != null) {
                    if (mInputQueueCallback != null) {
                        mInputQueue = new InputQueue();
                        mInputQueueCallback.onInputQueueCreated(mInputQueue);
                    }
                    mInputEventReceiver = new WindowInputEventReceiver(mInputChannel,
                            Looper.myLooper());
                }

                view.assignParent(this);
                mAddedTouchMode = (res & WindowManagerGlobal.ADD_FLAG_IN_TOUCH_MODE) != 0;
                mAppVisible = (res & WindowManagerGlobal.ADD_FLAG_APP_VISIBLE) != 0;

                if (mAccessibilityManager.isEnabled()) {
                    mAccessibilityInteractionConnectionManager.ensureConnection();
                }

                if (view.getImportantForAccessibility() == View.IMPORTANT_FOR_ACCESSIBILITY_AUTO) {
                    view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
                }

                // Set up the input pipeline.
                // 建立输入事件执行链
                CharSequence counterSuffix = attrs.getTitle();
                mSyntheticInputStage = new SyntheticInputStage();
                InputStage viewPostImeStage = new ViewPostImeInputStage(mSyntheticInputStage);
                InputStage nativePostImeStage = new NativePostImeInputStage(viewPostImeStage,
                        "aq:native-post-ime:" + counterSuffix);
                InputStage earlyPostImeStage = new EarlyPostImeInputStage(nativePostImeStage);
                InputStage imeStage = new ImeInputStage(earlyPostImeStage,
                        "aq:ime:" + counterSuffix);
                InputStage viewPreImeStage = new ViewPreImeInputStage(imeStage);
                InputStage nativePreImeStage = new NativePreImeInputStage(viewPreImeStage,
                        "aq:native-pre-ime:" + counterSuffix);

                mFirstInputStage = nativePreImeStage;
                mFirstPostImeInputStage = earlyPostImeStage;
                mPendingInputEventQueueLengthCounterName = "aq:pending:" + counterSuffix;
            }
        }
    }

    private void setTag() {
        final String[] split = mWindowAttributes.getTitle().toString().split("\\.");
        if (split.length > 0) {
            mTag = TAG + "[" + split[split.length - 1] + "]";
        }
    }

    /**
     * Whether the window is in local focus mode or not
     * 是否窗口在本地焦点模式之中
     */
    private boolean isInLocalFocusMode() {
        return (mWindowAttributes.flags & WindowManager.LayoutParams.FLAG_LOCAL_FOCUS_MODE) != 0;
    }

    public int getWindowFlags() {
        return mWindowAttributes.flags;
    }

    public int getDisplayId() {
        return mDisplay.getDisplayId();
    }

    public CharSequence getTitle() {
        return mWindowAttributes.getTitle();
    }

    void destroyHardwareResources() {
        if (mAttachInfo.mHardwareRenderer != null) {
            mAttachInfo.mHardwareRenderer.destroyHardwareResources(mView);
            mAttachInfo.mHardwareRenderer.destroy();
        }
    }

    // 硬件渲染相关，
    // 分离函数因子？
    public void detachFunctor(long functor) {
        if (mAttachInfo.mHardwareRenderer != null) {
            // Fence so that any pending invokeFunctor() messages will be processed
            // before we return from detachFunctor.
            mAttachInfo.mHardwareRenderer.stopDrawing();
        }
    }

    /**
     * Schedules the functor for execution in either kModeProcess or
     * kModeProcessNoContext, depending on whether or not there is an EGLContext.
     * 调起在kModeProcess进程或者kModeProcessNoContext进程之中运行的函数？
     * 具体哪个进程则依赖于是否存在一个EGLContext？
     *
     * @param functor           The native functor to invoke
     *                          用来执行的本地函数？
     * @param waitForCompletion If true, this will not return until the functor
     *                          has invoked. If false, the functor may be invoked
     *                          asynchronously.
     *                          如果设置为true，则此方法知道本地函数被执行了才会返回；
     *                          如果设置为false，则此本地函数可能被异步执行
     */
    public static void invokeFunctor(long functor, boolean waitForCompletion) {
        ThreadedRenderer.invokeFunctor(functor, waitForCompletion);
    }

    // 注册动画渲染节点？
    public void registerAnimatingRenderNode(RenderNode animator) {
        if (mAttachInfo.mHardwareRenderer != null) {
            mAttachInfo.mHardwareRenderer.registerAnimatingRenderNode(animator);
        } else {
            if (mAttachInfo.mPendingAnimatingRenderNodes == null) {
                mAttachInfo.mPendingAnimatingRenderNodes = new ArrayList<RenderNode>();
            }
            mAttachInfo.mPendingAnimatingRenderNodes.add(animator);
        }
    }

    // 注册向量可绘动画节点？
    public void registerVectorDrawableAnimator(
            AnimatedVectorDrawable.VectorDrawableAnimatorRT animator) {
        if (mAttachInfo.mHardwareRenderer != null) {
            mAttachInfo.mHardwareRenderer.registerVectorDrawableAnimator(animator);
        }
    }

    /**
     * 启用硬件加速
     * 创建mAttachInfo.mHardwareRenderer对象，并更新与硬件加速相关的属性
     *
     * @param attrs
     */
    private void enableHardwareAcceleration(WindowManager.LayoutParams attrs) {
        mAttachInfo.mHardwareAccelerated = false;
        mAttachInfo.mHardwareAccelerationRequested = false;

        // Don't enable hardware acceleration when the application is in compatibility mode
        // 当应用处于兼容模式的时候，不启用硬件加速
        if (mTranslator != null) return;

        // Try to enable hardware acceleration if requested
        final boolean hardwareAccelerated =
                (attrs.flags & WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED) != 0;

        if (hardwareAccelerated) {
            if (!ThreadedRenderer.isAvailable()) {
                return;
            }

            // Persistent processes (including the system) should not do
            // accelerated rendering on low-end devices.  In that case,
            // sRendererDisabled will be set.  In addition, the system process
            // itself should never do accelerated rendering.  In that case, both
            // sRendererDisabled and sSystemRendererDisabled are set.  When
            // sSystemRendererDisabled is set, PRIVATE_FLAG_FORCE_HARDWARE_ACCELERATED
            // can be used by code on the system process to escape that and enable
            // HW accelerated drawing.  (This is basically for the lock screen.)

            // 持久的进程（包括系统进程）不应该在低配的手机上进行加速渲染。
            // 在这种情况下，sRendererDisabled将被设置。此外，系统进程自己也永不勇哥进行加速渲染。
            // 在这种情况下，sRendererDisabled与sSystemRendererDisabled第被时间而终。
            // 当sSystemRendererDisabled被设置了，PRIVATE_FLAG_FORCE_HARDWARE_ACCELERATED
            // 被系统进程的代码使用，用以忽略sSystemRendererDisabled的设置，并启用硬件加速绘制。
            // 这基本上是为锁屏服务的

            // 是否伪造硬件加速
            final boolean fakeHwAccelerated = (attrs.privateFlags &
                    WindowManager.LayoutParams.PRIVATE_FLAG_FAKE_HARDWARE_ACCELERATED) != 0;
            final boolean forceHwAccelerated = (attrs.privateFlags &
                    WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_HARDWARE_ACCELERATED) != 0;

            if (fakeHwAccelerated) {
                // This is exclusively(专一的，专门的) for the preview windows the window manager
                // shows for launching applications, so they will look more like
                // the app being launched.
                // 这是专门为WMS为了运行应用而显示的预览窗口服务的，
                // 所以它们将看起来更像应用被运行了
                mAttachInfo.mHardwareAccelerationRequested = true;
            } else if (!ThreadedRenderer.sRendererDisabled
                    || (ThreadedRenderer.sSystemRendererDisabled && forceHwAccelerated)) {
                if (mAttachInfo.mHardwareRenderer != null) {
                    mAttachInfo.mHardwareRenderer.destroy();
                }

                final Rect insets = attrs.surfaceInsets;
                final boolean hasSurfaceInsets = insets.left != 0 || insets.right != 0
                        || insets.top != 0 || insets.bottom != 0;
                // 是否半透明
                final boolean translucent = attrs.format != PixelFormat.OPAQUE || hasSurfaceInsets;
                mAttachInfo.mHardwareRenderer = ThreadedRenderer.create(mContext, translucent);
                if (mAttachInfo.mHardwareRenderer != null) {
                    mAttachInfo.mHardwareRenderer.setName(attrs.getTitle().toString());
                    mAttachInfo.mHardwareAccelerated =
                            mAttachInfo.mHardwareAccelerationRequested = true;
                }
            }
        }
    }

    // 获取根视图
    public View getView() {
        return mView;
    }

    final WindowLeaked getLocation() {
        return mLocation;
    }

    /**
     * 更新参数
     *
     * @param attrs
     * @param newView
     */
    void setLayoutParams(WindowManager.LayoutParams attrs, boolean newView) {
        synchronized (this) {
            final int oldInsetLeft = mWindowAttributes.surfaceInsets.left;
            final int oldInsetTop = mWindowAttributes.surfaceInsets.top;
            final int oldInsetRight = mWindowAttributes.surfaceInsets.right;
            final int oldInsetBottom = mWindowAttributes.surfaceInsets.bottom;
            final int oldSoftInputMode = mWindowAttributes.softInputMode;
            final boolean oldHasManualSurfaceInsets = mWindowAttributes.hasManualSurfaceInsets;

            if (DEBUG_KEEP_SCREEN_ON && (mClientWindowLayoutFlags
                    & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
                    && (attrs.flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) == 0) {
                Slog.d(mTag, "setLayoutParams: FLAG_KEEP_SCREEN_ON from true to false!");
            }

            // Keep track of the actual window flags supplied by the client.
            // 保持对由客户端提供的实际窗口标志的追踪
            mClientWindowLayoutFlags = attrs.flags;

            // Preserve compatible window flag if exists.
            // 如果退出，则保护兼容性窗口
            final int compatibleWindowFlag = mWindowAttributes.privateFlags
                    & WindowManager.LayoutParams.PRIVATE_FLAG_COMPATIBLE_WINDOW;

            // Transfer over system UI visibility values as they carry current state.
            // 将系统UI的可视状态值转换为当前状态
            attrs.systemUiVisibility = mWindowAttributes.systemUiVisibility;
            attrs.subtreeSystemUiVisibility = mWindowAttributes.subtreeSystemUiVisibility;

            mWindowAttributesChangesFlag = mWindowAttributes.copyFrom(attrs);
            if ((mWindowAttributesChangesFlag
                    & WindowManager.LayoutParams.TRANSLUCENT_FLAGS_CHANGED) != 0) {
                // 透明性改变
                // Recompute system ui visibility.
                // 重新计算系统UI的可践行
                mAttachInfo.mRecomputeGlobalAttributes = true;
            }
            if ((mWindowAttributesChangesFlag
                    & WindowManager.LayoutParams.LAYOUT_CHANGED) != 0) {
                // Request to update light center.
                // 请求更新光度
                mAttachInfo.mNeedsUpdateLightCenter = true;
            }
            if (mWindowAttributes.packageName == null) {
                mWindowAttributes.packageName = mBasePackageName;
            }
            mWindowAttributes.privateFlags |= compatibleWindowFlag;

            if (mWindowAttributes.preservePreviousSurfaceInsets) {
                // Restore old surface insets.
                mWindowAttributes.surfaceInsets.set(
                        oldInsetLeft, oldInsetTop, oldInsetRight, oldInsetBottom);
                mWindowAttributes.hasManualSurfaceInsets = oldHasManualSurfaceInsets;
            } else if (mWindowAttributes.surfaceInsets.left != oldInsetLeft
                    || mWindowAttributes.surfaceInsets.top != oldInsetTop
                    || mWindowAttributes.surfaceInsets.right != oldInsetRight
                    || mWindowAttributes.surfaceInsets.bottom != oldInsetBottom) {
                mNeedsHwRendererSetup = true;
            }

            applyKeepScreenOnFlag(mWindowAttributes);

            if (newView) {
                mSoftInputMode = attrs.softInputMode;
                requestLayout();
            }

            // Don't lose the mode we last auto-computed.
            // 不要失去我们上一次自动计算出的模式
            if ((attrs.softInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
                    == WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED) {
                mWindowAttributes.softInputMode = (mWindowAttributes.softInputMode
                        & ~WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
                        | (oldSoftInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
            }

            mWindowAttributesChanged = true;
            scheduleTraversals();
        }
    }

    void handleAppVisibility(boolean visible) {
        if (mAppVisible != visible) {
            mAppVisible = visible;
            scheduleTraversals();
            if (!mAppVisible) {
                WindowManagerGlobal.trimForeground();
            }
        }
    }

    void handleGetNewSurface() {
        mNewSurfaceNeeded = true;
        mFullRedrawNeeded = true;
        scheduleTraversals();
    }

    // 监听屏幕状态是否发生改变
    private final DisplayListener mDisplayListener = new DisplayListener() {
        @Override
        public void onDisplayChanged(int displayId) {
            if (mView != null && mDisplay.getDisplayId() == displayId) {
                final int oldDisplayState = mAttachInfo.mDisplayState;
                final int newDisplayState = mDisplay.getState();
                if (oldDisplayState != newDisplayState) {
                    mAttachInfo.mDisplayState = newDisplayState;
                    pokeDrawLockIfNeeded();
                    if (oldDisplayState != Display.STATE_UNKNOWN) {
                        final int oldScreenState = toViewScreenState(oldDisplayState);
                        final int newScreenState = toViewScreenState(newDisplayState);
                        if (oldScreenState != newScreenState) {
                            mView.dispatchScreenStateChanged(newScreenState);
                        }
                        if (oldDisplayState == Display.STATE_OFF) {
                            // Draw was suppressed so we need to for it to happen here.
                            mFullRedrawNeeded = true;
                            scheduleTraversals();
                        }
                    }
                }
            }
        }

        @Override
        public void onDisplayRemoved(int displayId) {
        }

        @Override
        public void onDisplayAdded(int displayId) {
        }

        private int toViewScreenState(int displayState) {
            return displayState == Display.STATE_OFF ?
                    View.SCREEN_STATE_OFF : View.SCREEN_STATE_ON;
        }
    };

    void pokeDrawLockIfNeeded() {
        final int displayState = mAttachInfo.mDisplayState;
        if (mView != null && mAdded && mTraversalScheduled
                && (displayState == Display.STATE_DOZE
                || displayState == Display.STATE_DOZE_SUSPEND)) {
            try {
                mWindowSession.pokeDrawLock(mWindow);
            } catch (RemoteException ex) {
                // System server died, oh well.
            }
        }
    }

    @Override
    public void requestFitSystemWindows() {
        checkThread();
        mApplyInsetsRequested = true;
        scheduleTraversals();
    }

    @Override
    public void requestLayout() {
        if (!mHandlingLayoutInLayoutRequest) {
            checkThread();
            mLayoutRequested = true;
            scheduleTraversals();
        }
    }

    @Override
    public boolean isLayoutRequested() {
        return mLayoutRequested;
    }

    /**
     * 将这个视图树范文纳入脏区域之中，
     * 且如果不会即将绘制，则进行遍历
     */
    void invalidate() {
        mDirty.set(0, 0, mWidth, mHeight);
        if (!mWillDrawSoon) {
            scheduleTraversals();
        }
    }

    void invalidateWorld(View view) {
        view.invalidate();
        if (view instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) view;
            for (int i = 0; i < parent.getChildCount(); i++) {
                invalidateWorld(parent.getChildAt(i));
            }
        }
    }

    @Override
    public void invalidateChild(View child, Rect dirty) {
        invalidateChildInParent(null, dirty);
    }

    @Override
    public ViewParent invalidateChildInParent(int[] location, Rect dirty) {
        checkThread();
        if (DEBUG_DRAW) Log.v(mTag, "Invalidate child: " + dirty);

        if (dirty == null) {
            // 全局重绘
            invalidate();
            return null;
        } else if (dirty.isEmpty() && !mIsAnimating) {
            return null;
        }

        if (mCurScrollY != 0 || mTranslator != null) {
            mTempRect.set(dirty);
            dirty = mTempRect;
            if (mCurScrollY != 0) {
                // 脏区域加上了滚动的部分
                dirty.offset(0, -mCurScrollY);
            }
            if (mTranslator != null) {
                // 将脏区域在APP窗口之中的坐标转换为在屏幕之中的坐标
                mTranslator.translateRectInAppWindowToScreen(dirty);
            }
            if (mAttachInfo.mScalingRequired) {
                dirty.inset(-1, -1);
            }
        }
        // 将屏幕之中指定的脏区域无效化
        invalidateRectOnScreen(dirty);

        return null;
    }

    private void invalidateRectOnScreen(Rect dirty) {
        final Rect localDirty = mDirty;
        if (!localDirty.isEmpty() && !localDirty.contains(dirty)) {
            mAttachInfo.mSetIgnoreDirtyState = true;
            mAttachInfo.mIgnoreDirtyState = true;
        }

        // Add the new dirty rect to the current one
        // 增加新的脏区域到当前的脏区域之中
        localDirty.union(dirty.left, dirty.top, dirty.right, dirty.bottom);
        // Intersect with the bounds of the window to skip
        // updates that lie outside of the visible region
        // 跳过窗口范围之内的交际
        // 更新可视区域之外的部分
        final float appScale = mAttachInfo.mApplicationScale;
        final boolean intersected = localDirty.intersect(0, 0,
                (int) (mWidth * appScale + 0.5f), (int) (mHeight * appScale + 0.5f));
        if (!intersected) {
            // 如果脏数据没有与窗口只能的内容有交集，则清空脏数据
            localDirty.setEmpty();
        }
        if (!mWillDrawSoon && (intersected || mIsAnimating)) {
            scheduleTraversals();
        }
    }

    public void setIsAmbientMode(boolean ambient) {
        mIsAmbientMode = ambient;
    }

    void setWindowStopped(boolean stopped) {
        if (mStopped != stopped) {
            mStopped = stopped;
            final ThreadedRenderer renderer = mAttachInfo.mHardwareRenderer;
            if (renderer != null) {
                if (DEBUG_DRAW)
                    Log.d(mTag, "WindowStopped on " + getTitle() + " set to " + mStopped);
                // 停止硬件渲染
                renderer.setStopped(mStopped);
            }
            if (!mStopped) {
                scheduleTraversals();
            } else {
                // 销毁硬件加速资源
                if (renderer != null) {
                    renderer.destroyHardwareResources(mView);
                }
            }
        }
    }

    /**
     * Block the input events during an Activity Transition. The KEYCODE_BACK event is allowed
     * through to allow quick reversal of the Activity Transition.
     * 在一个activity动画之间，阻塞输入事件。
     * 但是KEYCODE_BACK事件是被允许的
     *
     * @param paused true to pause, false to resume.
     */
    public void setPausedForTransition(boolean paused) {
        mPausedForTransition = paused;
    }

    @Override
    public ViewParent getParent() {
        return null;
    }

    @Override
    public boolean getChildVisibleRect(View child, Rect r, android.graphics.Point offset) {
        if (child != mView) {
            throw new RuntimeException("child is not mine, honest!");
        }
        // Note: don't apply scroll offset, because we want to know its
        // visibility in the virtual canvas being given to the view hierarchy.
        // 不适应滚动偏移量，因为我们想知道视图在虚拟画布之中的可视性
        return r.intersect(0, 0, mWidth, mHeight);
    }

    @Override
    public void bringChildToFront(View child) {
    }

    int getHostVisibility() {
        return (mAppVisible || mForceDecorViewVisibility) ? mView.getVisibility() : View.GONE;
    }

    /**
     * Add LayoutTransition to the list of transitions to be started in the next traversal.
     * This list will be cleared after the transitions on the list are start()'ed. These
     * transitionsa are added by LayoutTransition itself when it sets up animations. The setup
     * happens during the layout phase of traversal, which we want to complete before any of the
     * animations are started (because those animations may side-effect properties that layout
     * depends upon, like the bounding rectangles of the affected views). So we add the transition
     * to the list and it is started just prior to starting the drawing phase of traversal.
     * 增加布局动画到动画列表之中，用以在下一次遍历的过程之中开始。
     * 在此动画列表之中的动画被开始之后，此列表将会清除。
     * 当LayoutTransition建立动画的时候，会自己添加。
     * 这个建立步骤发生在遍历的布局阶段，此布局阶段将被期望在所有动画开始之前完成（因为这些动画
     * 可能具有与布局相关的副作用）。所以我们添加动画到列表之中，并且此动画将优先于遍历过程之中的绘制阶段
     *
     * @param transition The LayoutTransition to be started on the next traversal.
     * @hide
     */
    public void requestTransitionStart(LayoutTransition transition) {
        if (mPendingTransitions == null || !mPendingTransitions.contains(transition)) {
            if (mPendingTransitions == null) {
                mPendingTransitions = new ArrayList<LayoutTransition>();
            }
            mPendingTransitions.add(transition);
        }
    }

    /**
     * Notifies the HardwareRenderer that a new frame will be coming soon.
     * Currently only {@link ThreadedRenderer} cares about this, and uses
     * this knowledge to adjust the scheduling of off-thread animations
     * 通知硬件渲染器一个新的帧将会很快来临。
     * 当前只有{@link ThreadedRenderer}关心这个，
     * 并且使用此信息来调整线程动画的调度
     */
    void notifyRendererOfFramePending() {
        if (mAttachInfo.mHardwareRenderer != null) {
            mAttachInfo.mHardwareRenderer.notifyFramePending();
        }
    }

    // OK开始调度布局
    void scheduleTraversals() {
        if (!mTraversalScheduled) {
            mTraversalScheduled = true;
            // 设置一个消息队列栅栏，在等待遍历的这段时间内禁止消息的处理
            mTraversalBarrier = mHandler.getLooper().getQueue().postSyncBarrier();
            // 将此次布局请求加入绘制导演的队列之中，当垂直信息来临之际，便是遍历开始之时
            mChoreographer.postCallback(
                    Choreographer.CALLBACK_TRAVERSAL, mTraversalRunnable, null);
            if (!mUnbufferedInputDispatch) {
                scheduleConsumeBatchedInput();
            }
            notifyRendererOfFramePending();
            pokeDrawLockIfNeeded();
        }
    }

    void unscheduleTraversals() {
        if (mTraversalScheduled) {
            mTraversalScheduled = false;
            mHandler.getLooper().getQueue().removeSyncBarrier(mTraversalBarrier);
            mChoreographer.removeCallbacks(
                    Choreographer.CALLBACK_TRAVERSAL, mTraversalRunnable, null);
        }
    }

    // 遍历的实际调用类
    void doTraversal() {
        if (mTraversalScheduled) {
            mTraversalScheduled = false;
            mHandler.getLooper().getQueue().removeSyncBarrier(mTraversalBarrier);

            if (mProfile) {
                Debug.startMethodTracing("ViewAncestor");
            }

            performTraversals();

            if (mProfile) {
                Debug.stopMethodTracing();
                mProfile = false;
            }
        }
    }

    private void applyKeepScreenOnFlag(WindowManager.LayoutParams params) {
        // Update window's global keep screen on flag: if a view has requested
        // that the screen be kept on, then it is always set; otherwise, it is
        // set to whatever the client last requested for the global state.
        // 更新窗口的全局保持屏幕高亮的标识：如果窗口请求屏幕保持亮度，
        // 则此标识总是被设置；否者将被设置为客户端上次请求的状态
        if (mAttachInfo.mKeepScreenOn) {
            params.flags |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        } else {
            params.flags = (params.flags & ~WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    | (mClientWindowLayoutFlags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private boolean collectViewAttributes() {
        if (mAttachInfo.mRecomputeGlobalAttributes) {
            //Log.i(mTag, "Computing view hierarchy attributes!");
            mAttachInfo.mRecomputeGlobalAttributes = false;
            boolean oldScreenOn = mAttachInfo.mKeepScreenOn;
            mAttachInfo.mKeepScreenOn = false;
            mAttachInfo.mSystemUiVisibility = 0;
            mAttachInfo.mHasSystemUiListeners = false;
            mView.dispatchCollectViewAttributes(mAttachInfo, 0);
            mAttachInfo.mSystemUiVisibility &= ~mAttachInfo.mDisabledSystemUiVisibility;
            WindowManager.LayoutParams params = mWindowAttributes;
            mAttachInfo.mSystemUiVisibility |= getImpliedSystemUiVisibility(params);
            if (mAttachInfo.mKeepScreenOn != oldScreenOn
                    || mAttachInfo.mSystemUiVisibility != params.subtreeSystemUiVisibility
                    || mAttachInfo.mHasSystemUiListeners != params.hasSystemUiListeners) {
                applyKeepScreenOnFlag(params);
                params.subtreeSystemUiVisibility = mAttachInfo.mSystemUiVisibility;
                params.hasSystemUiListeners = mAttachInfo.mHasSystemUiListeners;
                mView.dispatchWindowSystemUiVisiblityChanged(mAttachInfo.mSystemUiVisibility);
                return true;
            }
        }
        return false;
    }

    private int getImpliedSystemUiVisibility(WindowManager.LayoutParams params) {
        int vis = 0;
        // Translucent decor window flags imply stable system ui visibility.
        if ((params.flags & WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS) != 0) {
            vis |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        }
        if ((params.flags & WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION) != 0) {
            vis |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        }
        return vis;
    }

    /**
     * 测量视图树
     *
     * @param host                decor view
     * @param lp                  窗口布局参数
     * @param res                 资源对象
     * @param desiredWindowWidth  渴望的宽度
     * @param desiredWindowHeight 渴望的高度
     * @return
     */
    private boolean measureHierarchy(final View host, final WindowManager.LayoutParams lp,
                                     final Resources res, final int desiredWindowWidth, final int desiredWindowHeight) {
        int childWidthMeasureSpec;
        int childHeightMeasureSpec;
        // 窗口的大小是否可能改变
        boolean windowSizeMayChange = false;

        if (DEBUG_ORIENTATION || DEBUG_LAYOUT) Log.v(mTag,
                "Measuring " + host + " in display " + desiredWindowWidth
                        + "x" + desiredWindowHeight + "...");
        // 是否是一个好的测量
        boolean goodMeasure = false;
        if (lp.width == ViewGroup.LayoutParams.WRAP_CONTENT) {
            // On large screens, we don't want to allow dialogs to just
            // stretch（延伸、张开） to fill the entire width of the screen to display
            // one line of text.  First try doing the layout at a smaller
            // size to see if it will fit.
            // 在大屏幕之上，我们不希望允许对话框延伸，以致填充屏幕的整个宽度，用以显示一行文字。
            // 首先试着在一个较小的尺寸上进行布局
            final DisplayMetrics packageMetrics = res.getDisplayMetrics();
            res.getValue(com.android.internal.R.dimen.config_prefDialogWidth, mTmpValue, true);
            int baseSize = 0;
            if (mTmpValue.type == TypedValue.TYPE_DIMENSION) {
                baseSize = (int) mTmpValue.getDimension(packageMetrics);
            }
            if (DEBUG_DIALOG) Log.v(mTag, "Window " + mView + ": baseSize=" + baseSize
                    + ", desiredWindowWidth=" + desiredWindowWidth);
            if (baseSize != 0 && desiredWindowWidth > baseSize) {
                // 期望的窗口宽度，大于对话框的优先宽度
                childWidthMeasureSpec = getRootMeasureSpec(baseSize, lp.width);
                childHeightMeasureSpec = getRootMeasureSpec(desiredWindowHeight, lp.height);
                // 执行布局
                performMeasure(childWidthMeasureSpec, childHeightMeasureSpec);
                if (DEBUG_DIALOG) Log.v(mTag, "Window " + mView + ": measured ("
                        + host.getMeasuredWidth() + "," + host.getMeasuredHeight()
                        + ") from width spec: " + MeasureSpec.toString(childWidthMeasureSpec)
                        + " and height spec: " + MeasureSpec.toString(childHeightMeasureSpec));
                if ((host.getMeasuredWidthAndState() & View.MEASURED_STATE_TOO_SMALL) == 0) {
                    goodMeasure = true;
                } else {
                    // Didn't fit in that size... try expanding a bit.
                    // 如果不是一个好的布局，则不要适应此尺寸，试着扩展
                    baseSize = (baseSize + desiredWindowWidth) / 2;
                    if (DEBUG_DIALOG) Log.v(mTag, "Window " + mView + ": next baseSize="
                            + baseSize);
                    childWidthMeasureSpec = getRootMeasureSpec(baseSize, lp.width);
                    performMeasure(childWidthMeasureSpec, childHeightMeasureSpec);
                    if (DEBUG_DIALOG) Log.v(mTag, "Window " + mView + ": measured ("
                            + host.getMeasuredWidth() + "," + host.getMeasuredHeight() + ")");
                    if ((host.getMeasuredWidthAndState() & View.MEASURED_STATE_TOO_SMALL) == 0) {
                        if (DEBUG_DIALOG) Log.v(mTag, "Good!");
                        goodMeasure = true;
                    }
                }
            }
        }

        // 如果不是一个好的布局，则重新以期望的宽度和高度，来测量整个视图树
        if (!goodMeasure) {
            childWidthMeasureSpec = getRootMeasureSpec(desiredWindowWidth, lp.width);
            childHeightMeasureSpec = getRootMeasureSpec(desiredWindowHeight, lp.height);
            performMeasure(childWidthMeasureSpec, childHeightMeasureSpec);
            if (mWidth != host.getMeasuredWidth() || mHeight != host.getMeasuredHeight()) {
                windowSizeMayChange = true;
            }
        }

        if (DBG) {
            System.out.println("======================================");
            System.out.println("performTraversals -- after measure");
            host.debug();
        }

        return windowSizeMayChange;
    }

    /**
     * Modifies the input matrix such that it maps view-local coordinates to
     * on-screen coordinates.
     * 修复输入的矩形，以便它能将视图本地的坐标映射为屏幕坐标
     *
     * @param m input matrix to modify
     */
    void transformMatrixToGlobal(Matrix m) {
        m.preTranslate(mAttachInfo.mWindowLeft, mAttachInfo.mWindowTop);
    }

    /**
     * Modifies the input matrix such that it maps on-screen coordinates to
     * view-local coordinates.
     * 修复输入的矩形，以便它能将基于屏幕的坐标映射值视图本地的坐标
     *
     * @param m input matrix to modify
     */
    void transformMatrixToLocal(Matrix m) {
        m.postTranslate(-mAttachInfo.mWindowLeft, -mAttachInfo.mWindowTop);
    }

    /* package */ WindowInsets getWindowInsets(boolean forceConstruct) {
        if (mLastWindowInsets == null || forceConstruct) {
            mDispatchContentInsets.set(mAttachInfo.mContentInsets);
            mDispatchStableInsets.set(mAttachInfo.mStableInsets);
            Rect contentInsets = mDispatchContentInsets;
            Rect stableInsets = mDispatchStableInsets;
            // For dispatch we preserve old logic, but for direct requests from Views we allow to
            // immediately use pending insets.
            // 为了派遣，我们保存就得逻辑，但是为了直接从视图之中请求，我们运行立即使用即将生效的边衬
            if (!forceConstruct
                    && (!mPendingContentInsets.equals(contentInsets) ||
                    !mPendingStableInsets.equals(stableInsets))) {
                contentInsets = mPendingContentInsets;
                stableInsets = mPendingStableInsets;
            }
            Rect outsets = mAttachInfo.mOutsets;
            if (outsets.left > 0 || outsets.top > 0 || outsets.right > 0 || outsets.bottom > 0) {
                contentInsets = new Rect(contentInsets.left + outsets.left,
                        contentInsets.top + outsets.top, contentInsets.right + outsets.right,
                        contentInsets.bottom + outsets.bottom);
            }
            mLastWindowInsets = new WindowInsets(contentInsets,
                    null /* windowDecorInsets */, stableInsets,
                    mContext.getResources().getConfiguration().isScreenRound(),
                    mAttachInfo.mAlwaysConsumeNavBar);
        }
        return mLastWindowInsets;
    }

    void dispatchApplyInsets(View host) {
        host.dispatchApplyWindowInsets(getWindowInsets(true /* forceConstruct */));
    }

    private static boolean shouldUseDisplaySize(final WindowManager.LayoutParams lp) {
        return lp.type == TYPE_STATUS_BAR_PANEL
                || lp.type == TYPE_INPUT_METHOD
                || lp.type == TYPE_VOLUME_OVERLAY;
    }

    private int dipToPx(int dip) {
        final DisplayMetrics displayMetrics = mContext.getResources().getDisplayMetrics();
        return (int) (displayMetrics.density * dip + 0.5f);
    }

    // 开始执行遍历
    private void performTraversals() {
        // cache mView since it is used so much below...
        // 缓存decor view，因为它将在下面被多次使用
        final View host = mView;

        if (DBG) {
            System.out.println("======================================");
            System.out.println("performTraversals");
            host.debug();
        }

        if (host == null || !mAdded)
            return;

        mIsInTraversal = true;
        mWillDrawSoon = true;
        // 窗口此处是否可能改变
        boolean windowSizeMayChange = false;
        // 是否申请了一个新的Surface对象
        boolean newSurface = false;
        // 是否surface对象发生了改变
        boolean surfaceChanged = false;
        // 窗口的布局参数
        WindowManager.LayoutParams lp = mWindowAttributes;

        int desiredWindowWidth;
        int desiredWindowHeight;

        final int viewVisibility = getHostVisibility();
        // 视图可见性是否发生了改变
        final boolean viewVisibilityChanged = !mFirst
                && (mViewVisibility != viewVisibility || mNewSurfaceNeeded);
        final boolean viewUserVisibilityChanged = !mFirst &&
                ((mViewVisibility == View.VISIBLE) != (viewVisibility == View.VISIBLE));

        WindowManager.LayoutParams params = null;
        if (mWindowAttributesChanged) {
            mWindowAttributesChanged = false;
            surfaceChanged = true;
            params = lp;
        }
        CompatibilityInfo compatibilityInfo =
                mDisplay.getDisplayAdjustments().getCompatibilityInfo();
        if (compatibilityInfo.supportsScreen() == mLastInCompatMode) {
            params = lp;
            mFullRedrawNeeded = true;
            mLayoutRequested = true;
            if (mLastInCompatMode) {
                params.privateFlags &= ~WindowManager.LayoutParams.PRIVATE_FLAG_COMPATIBLE_WINDOW;
                mLastInCompatMode = false;
            } else {
                params.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_COMPATIBLE_WINDOW;
                mLastInCompatMode = true;
            }
        }

        mWindowAttributesChangesFlag = 0;

        Rect frame = mWinFrame;
        if (mFirst) {
            mFullRedrawNeeded = true;
            mLayoutRequested = true;

            if (shouldUseDisplaySize(lp)) {
                // NOTE -- system code, won't try to do compat mode.
                Point size = new Point();
                mDisplay.getRealSize(size);
                desiredWindowWidth = size.x;
                desiredWindowHeight = size.y;
            } else {
                Configuration config = mContext.getResources().getConfiguration();
                desiredWindowWidth = dipToPx(config.screenWidthDp);
                desiredWindowHeight = dipToPx(config.screenHeightDp);
            }

            // We used to use the following condition to choose 32 bits drawing caches:
            // PixelFormat.hasAlpha(lp.format) || lp.format == PixelFormat.RGBX_8888
            // However, windows are now always 32 bits by default, so choose 32 bits
            // 我们曾经习惯于使用件PixelFormat.hasAlpha(lp.format) || lp.format == PixelFormat.RGBX_8888，来选择32位的绘制缓存：
            // 然而现在，窗口默认为32位，所以选择32位
            mAttachInfo.mUse32BitDrawingCache = true;
            mAttachInfo.mHasWindowFocus = false;
            mAttachInfo.mWindowVisibility = viewVisibility;
            mAttachInfo.mRecomputeGlobalAttributes = false;
            mLastConfiguration.setTo(host.getResources().getConfiguration());
            mLastSystemUiVisibility = mAttachInfo.mSystemUiVisibility;
            // Set the layout direction if it has not been set before (inherit is the default)
            // 如果之前没有设置布局方向，则在此处设置布局方向
            if (mViewLayoutDirectionInitial == View.LAYOUT_DIRECTION_INHERIT) {
                host.setLayoutDirection(mLastConfiguration.getLayoutDirection());
            }
            // 开始粘贴到窗口之上了
            host.dispatchAttachedToWindow(mAttachInfo, 0);
            mAttachInfo.mTreeObserver.dispatchOnWindowAttachedChange(true);
            dispatchApplyInsets(host);
            //Log.i(mTag, "Screen on initialized: " + attachInfo.mKeepScreenOn);

        } else {
            desiredWindowWidth = frame.width();
            desiredWindowHeight = frame.height();
            if (desiredWindowWidth != mWidth || desiredWindowHeight != mHeight) {
                if (DEBUG_ORIENTATION) Log.v(mTag, "View " + host + " resized to: " + frame);
                mFullRedrawNeeded = true;
                mLayoutRequested = true;
                windowSizeMayChange = true;
            }
        }

        // 窗口可视性发生了改变
        if (viewVisibilityChanged) {
            mAttachInfo.mWindowVisibility = viewVisibility;
            host.dispatchWindowVisibilityChanged(viewVisibility);
            if (viewUserVisibilityChanged) {
                host.dispatchVisibilityAggregated(viewVisibility == View.VISIBLE);
            }
            if (viewVisibility != View.VISIBLE || mNewSurfaceNeeded) {
                endDragResizing();
                destroyHardwareResources();
            }
            if (viewVisibility == View.GONE) {
                // After making a window gone, we will count it as being
                // shown for the first time the next time it gets focus.
                // 在标记一个窗口为消失状态，则我们将认为它在下一次获取到焦点时，
                // 它是第一次显示的
                mHasHadWindowFocus = false;
            }
        }

        // Non-visible windows can't hold accessibility focus.
        // 非可见化的窗口不能持有辅助性焦点
        if (mAttachInfo.mWindowVisibility != View.VISIBLE) {
            host.clearAccessibilityFocus();
        }

        // Execute enqueued actions on every traversal in case a detached view enqueued an action
        // 在每一次遍历的过程之中执行入队操作，防止一个分离的视图入队一个操作
        getRunQueue().executeActions(mAttachInfo.mHandler);

        boolean insetsChanged = false;

        // 是否需要进行布局
        boolean layoutRequested = mLayoutRequested && (!mStopped || mReportNextDraw);
        if (layoutRequested) {

            final Resources res = mView.getContext().getResources();

            if (mFirst) {
                // make sure touch mode code executes by setting cached value
                // to opposite of the added touch mode.
                // 通过缓存的，被添加的触摸模式的反转值，来确保触摸模式代码一定被执行
                mAttachInfo.mInTouchMode = !mAddedTouchMode;
                ensureTouchModeLocally(mAddedTouchMode);
            } else {
                // 确保相关的边衬大小是否发生了改变
                if (!mPendingOverscanInsets.equals(mAttachInfo.mOverscanInsets)) {
                    insetsChanged = true;
                }
                if (!mPendingContentInsets.equals(mAttachInfo.mContentInsets)) {
                    insetsChanged = true;
                }
                if (!mPendingStableInsets.equals(mAttachInfo.mStableInsets)) {
                    insetsChanged = true;
                }
                if (!mPendingVisibleInsets.equals(mAttachInfo.mVisibleInsets)) {
                    mAttachInfo.mVisibleInsets.set(mPendingVisibleInsets);
                    if (DEBUG_LAYOUT) Log.v(mTag, "Visible insets changing to: "
                            + mAttachInfo.mVisibleInsets);
                }
                if (!mPendingOutsets.equals(mAttachInfo.mOutsets)) {
                    insetsChanged = true;
                }
                if (mPendingAlwaysConsumeNavBar != mAttachInfo.mAlwaysConsumeNavBar) {
                    insetsChanged = true;
                }
                // 确认期望的窗口宽度、长度
                if (lp.width == ViewGroup.LayoutParams.WRAP_CONTENT
                        || lp.height == ViewGroup.LayoutParams.WRAP_CONTENT) {
                    windowSizeMayChange = true;

                    if (shouldUseDisplaySize(lp)) {
                        // NOTE -- system code, won't try to do compat mode.
                        Point size = new Point();
                        mDisplay.getRealSize(size);
                        desiredWindowWidth = size.x;
                        desiredWindowHeight = size.y;
                    } else {
                        Configuration config = res.getConfiguration();
                        desiredWindowWidth = dipToPx(config.screenWidthDp);
                        desiredWindowHeight = dipToPx(config.screenHeightDp);
                    }
                }
            }

            // Ask host how big it wants to be
            // 开始测量
            windowSizeMayChange |= measureHierarchy(host, lp, res,
                    desiredWindowWidth, desiredWindowHeight);
        }

        if (collectViewAttributes()) {
            params = lp;
        }
        if (mAttachInfo.mForceReportNewAttributes) {
            mAttachInfo.mForceReportNewAttributes = false;
            params = lp;
        }

        // 调整输入法窗口模式
        if (mFirst || mAttachInfo.mViewVisibilityChanged) {
            mAttachInfo.mViewVisibilityChanged = false;
            int resizeMode = mSoftInputMode &
                    WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST;
            // If we are in auto resize mode, then we need to determine
            // what mode to use now.
            // 如果输入法窗口模式是一个自动调整模式，则我们需要决定我们应该使用哪种输入法窗口模式了
            if (resizeMode == WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED) {
                final int N = mAttachInfo.mScrollContainers.size();
                for (int i = 0; i < N; i++) {
                    if (mAttachInfo.mScrollContainers.get(i).isShown()) {
                        resizeMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
                    }
                }
                if (resizeMode == 0) {
                    resizeMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;
                }
                if ((lp.softInputMode &
                        WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST) != resizeMode) {
                    lp.softInputMode = (lp.softInputMode &
                            ~WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST) |
                            resizeMode;
                    params = lp;
                }
            }
        }

        if (params != null) {
            if ((host.mPrivateFlags & View.PFLAG_REQUEST_TRANSPARENT_REGIONS) != 0) {
                if (!PixelFormat.formatHasAlpha(params.format)) {
                    params.format = PixelFormat.TRANSLUCENT;
                }
            }
            mAttachInfo.mOverscanRequested = (params.flags
                    & WindowManager.LayoutParams.FLAG_LAYOUT_IN_OVERSCAN) != 0;
        }

        if (mApplyInsetsRequested) {
            mApplyInsetsRequested = false;
            mLastOverscanRequested = mAttachInfo.mOverscanRequested;
            dispatchApplyInsets(host);
            if (mLayoutRequested) {
                // Short-circuit catching a new layout request here, so
                // we don't need to go through two layout passes when things
                // change due to fitting system windows, which can happen a lot.
                windowSizeMayChange |= measureHierarchy(host, lp,
                        mView.getContext().getResources(),
                        desiredWindowWidth, desiredWindowHeight);
            }
        }

        if (layoutRequested) {
            // Clear this now, so that if anything requests a layout in the
            // rest of this function we will catch it and re-run a full
            // layout pass.
            // 清除请求布局标识，以便在执行此方法剩下部分时，一个来至于其他地方的
            // 布局请求能够被我们捕获，从而能够正确的重新运行一次全布局过程
            mLayoutRequested = false;
        }

        boolean windowShouldResize = layoutRequested && windowSizeMayChange
                && ((mWidth != host.getMeasuredWidth() || mHeight != host.getMeasuredHeight())
                || (lp.width == ViewGroup.LayoutParams.WRAP_CONTENT &&
                frame.width() < desiredWindowWidth && frame.width() != mWidth)
                || (lp.height == ViewGroup.LayoutParams.WRAP_CONTENT &&
                frame.height() < desiredWindowHeight && frame.height() != mHeight));
        windowShouldResize |= mDragResizing && mResizeMode == RESIZE_MODE_FREEFORM;

        // If the activity was just relaunched, it might have unfrozen the task bounds (while
        // relaunching), so we need to force a call into window manager to pick up the latest
        // bounds.
        windowShouldResize |= mActivityRelaunched;

        // Determine whether to compute insets.
        // If there are no inset listeners remaining then we may still need to compute
        // insets in case the old insets were non-empty and must be reset.
        // 决定是否计算边衬
        // 如果没有边衬监听器持有，则我们将依旧会计算边衬，一方旧的边衬是非空且需重设的。
        final boolean computesInternalInsets =
                mAttachInfo.mTreeObserver.hasComputeInternalInsetsListeners()
                        || mAttachInfo.mHasNonEmptyGivenInternalInsets;

        boolean insetsPending = false;
        int relayoutResult = 0;
        boolean updatedConfiguration = false;

        final int surfaceGenerationId = mSurface.getGenerationId();

        final boolean isViewVisible = viewVisibility == View.VISIBLE;
        // 如果是第一次，或者窗口应该调整大小，或者相关的边衬返回发生了改变
        // 或者视图的可视性发生了改变，或者窗口布局参数反发生了改变，或者强制窗口重布局
        // 以上这几种，至少有一种条件发生了，则会进行WMS通信，启动WMS的窗口重布局
        if (mFirst || windowShouldResize || insetsChanged ||
                viewVisibilityChanged || params != null || mForceNextWindowRelayout) {
            mForceNextWindowRelayout = false;

            if (isViewVisible) {
                // If this window is giving internal insets to the window
                // manager, and it is being added or changing its visibility,
                // then we want to first give the window manager "fake"
                // insets to cause it to effectively ignore the content of
                // the window during layout.  This avoids it briefly causing
                // other windows to resize/move based on the raw frame of the
                // window, waiting until we can finish laying out this window
                // and get back to the window manager with the ultimately
                // computed insets.
                // 如果此窗口将会把内部边衬传递给WMS，
                // 则此窗口将被添加或者改变其自身的可视性，
                // 然后我们想在第一次给WMS一个假的边衬，
                // 用以使之有效忽略在布局过程之中的窗口内容。
                // 这将避免此窗口会短暂的引起其他窗口
                // 基于此窗口的原始范围，而进行的大小调整、移动，
                // 所以等待，知道我们完成了对此窗口的布局，
                // 并将最终计算出的边衬返回给WMS
                insetsPending = computesInternalInsets && (mFirst || viewVisibilityChanged);
            }

            if (mSurfaceHolder != null) {
                mSurfaceHolder.mSurfaceLock.lock();
                mDrawingAllowed = true;
            }

            boolean hwInitialized = false;
            boolean contentInsetsChanged = false;
            // 是否有surface对象
            boolean hadSurface = mSurface.isValid();

            try {
                if (DEBUG_LAYOUT) {
                    Log.i(mTag, "host=w:" + host.getMeasuredWidth() + ", h:" +
                            host.getMeasuredHeight() + ", params=" + params);
                }

                if (mAttachInfo.mHardwareRenderer != null) {
                    // relayoutWindow may decide to destroy mSurface. As that decision
                    // happens in WindowManager service, we need to be defensive here
                    // and stop using the surface in case it gets destroyed.
                    // 重布局窗口可能会导致surface对象被销毁。
                    // 由于此决定是在WMS之中发生的，我们需要在此处预防一下，并停止使用surface对象
                    if (mAttachInfo.mHardwareRenderer.pauseSurface(mSurface)) {
                        // Animations were running so we need to push a frame
                        // to resume them
                        // 动画正在运行，所以我们需要推送一帧用以恢复
                        mDirty.set(0, 0, mWidth, mHeight);
                    }
                    mChoreographer.mFrameInfo.addFlags(FrameInfo.FLAG_WINDOW_LAYOUT_CHANGED);
                }
                // 开始重布局窗口
                relayoutResult = relayoutWindow(params, viewVisibility, insetsPending);

                if (DEBUG_LAYOUT) Log.v(mTag, "relayout: frame=" + frame.toShortString()
                        + " overscan=" + mPendingOverscanInsets.toShortString()
                        + " content=" + mPendingContentInsets.toShortString()
                        + " visible=" + mPendingVisibleInsets.toShortString()
                        + " visible=" + mPendingStableInsets.toShortString()
                        + " outsets=" + mPendingOutsets.toShortString()
                        + " surface=" + mSurface);

                // 更新配置
                if (mPendingConfiguration.seq != 0) {
                    if (DEBUG_CONFIGURATION) Log.v(mTag, "Visible with new config: "
                            + mPendingConfiguration);
                    updateConfiguration(new Configuration(mPendingConfiguration), !mFirst);
                    mPendingConfiguration.seq = 0;
                    updatedConfiguration = true;
                }

                // 更新相关的边衬大小
                final boolean overscanInsetsChanged = !mPendingOverscanInsets.equals(
                        mAttachInfo.mOverscanInsets);
                contentInsetsChanged = !mPendingContentInsets.equals(
                        mAttachInfo.mContentInsets);
                final boolean visibleInsetsChanged = !mPendingVisibleInsets.equals(
                        mAttachInfo.mVisibleInsets);
                final boolean stableInsetsChanged = !mPendingStableInsets.equals(
                        mAttachInfo.mStableInsets);
                final boolean outsetsChanged = !mPendingOutsets.equals(mAttachInfo.mOutsets);
                final boolean surfaceSizeChanged = (relayoutResult
                        & WindowManagerGlobal.RELAYOUT_RES_SURFACE_RESIZED) != 0;
                final boolean alwaysConsumeNavBarChanged =
                        mPendingAlwaysConsumeNavBar != mAttachInfo.mAlwaysConsumeNavBar;
                if (contentInsetsChanged) {
                    mAttachInfo.mContentInsets.set(mPendingContentInsets);
                    if (DEBUG_LAYOUT) Log.v(mTag, "Content insets changing to: "
                            + mAttachInfo.mContentInsets);
                }
                if (overscanInsetsChanged) {
                    mAttachInfo.mOverscanInsets.set(mPendingOverscanInsets);
                    if (DEBUG_LAYOUT) Log.v(mTag, "Overscan insets changing to: "
                            + mAttachInfo.mOverscanInsets);
                    // Need to relayout with content insets.
                    contentInsetsChanged = true;
                }
                if (stableInsetsChanged) {
                    mAttachInfo.mStableInsets.set(mPendingStableInsets);
                    if (DEBUG_LAYOUT) Log.v(mTag, "Decor insets changing to: "
                            + mAttachInfo.mStableInsets);
                    // Need to relayout with content insets.
                    contentInsetsChanged = true;
                }
                if (alwaysConsumeNavBarChanged) {
                    mAttachInfo.mAlwaysConsumeNavBar = mPendingAlwaysConsumeNavBar;
                    contentInsetsChanged = true;
                }
                if (contentInsetsChanged || mLastSystemUiVisibility !=
                        mAttachInfo.mSystemUiVisibility || mApplyInsetsRequested
                        || mLastOverscanRequested != mAttachInfo.mOverscanRequested
                        || outsetsChanged) {
                    mLastSystemUiVisibility = mAttachInfo.mSystemUiVisibility;
                    mLastOverscanRequested = mAttachInfo.mOverscanRequested;
                    mAttachInfo.mOutsets.set(mPendingOutsets);
                    mApplyInsetsRequested = false;
                    dispatchApplyInsets(host);
                }
                if (visibleInsetsChanged) {
                    mAttachInfo.mVisibleInsets.set(mPendingVisibleInsets);
                    if (DEBUG_LAYOUT) Log.v(mTag, "Visible insets changing to: "
                            + mAttachInfo.mVisibleInsets);
                }

                if (!hadSurface) {
                    // 如果没有surface对象
                    if (mSurface.isValid()) {
                        // 如果WMS返回的surface对象有效
                        // If we are creating a new surface, then we need to
                        // completely redraw it.  Also, when we get to the
                        // point of drawing it we will hold off and schedule
                        // a new traversal instead.  This is so we can tell the
                        // window manager about all of the windows being displayed
                        // before actually drawing them, so it can display then
                        // all at once.
                        // 如果我们创建了一个新的surface对象，则我们需要完全重绘视图树/
                        // 所以，当我们运行到绘制视图树的时间点时，我们将拖延并发起一场新的遍历操作。
                        // 这也是在确切的绘制视图之前，我们应该告诉 WMS窗口上所有的视图即将被显示的原因
                        newSurface = true;
                        mFullRedrawNeeded = true;
                        mPreviousTransparentRegion.setEmpty();

                        // Only initialize up-front if transparent regions are not
                        // requested, otherwise defer to see if the entire window
                        // will be transparent
                        // 如果透明区域没有被请求，则只预先初始化一下，否者将退出判断实体窗口是否将是透明的
                        if (mAttachInfo.mHardwareRenderer != null) {
                            try {
                                hwInitialized = mAttachInfo.mHardwareRenderer.initialize(
                                        mSurface);
                                if (hwInitialized && (host.mPrivateFlags
                                        & View.PFLAG_REQUEST_TRANSPARENT_REGIONS) == 0) {
                                    // Don't pre-allocate if transparent regions
                                    // are requested as they may not be needed
                                    // 如果透明区域被请求，不要预先分配，因为它们可能不需要
                                    mSurface.allocateBuffers();
                                }
                            } catch (OutOfResourcesException e) {
                                handleOutOfResourcesException(e);
                                return;
                            }
                        }
                    }
                } else if (!mSurface.isValid()) {
                    // If the surface has been removed, then reset the scroll
                    // positions.
                    // 如果surface对象被销毁了，则需要重设滚动位置
                    if (mLastScrolledFocus != null) {
                        mLastScrolledFocus.clear();
                    }
                    mScrollY = mCurScrollY = 0;
                    if (mView instanceof RootViewSurfaceTaker) {
                        ((RootViewSurfaceTaker) mView).onRootViewScrollYChanged(mCurScrollY);
                    }
                    if (mScroller != null) {
                        mScroller.abortAnimation();
                    }
                    // Our surface is gone
                    if (mAttachInfo.mHardwareRenderer != null &&
                            mAttachInfo.mHardwareRenderer.isEnabled()) {
                        mAttachInfo.mHardwareRenderer.destroy();
                    }
                } else if ((surfaceGenerationId != mSurface.getGenerationId()
                        || surfaceSizeChanged)
                        && mSurfaceHolder == null
                        && mAttachInfo.mHardwareRenderer != null) {
                    // surface对象更新了
                    mFullRedrawNeeded = true;
                    try {
                        // Need to do updateSurface (which leads to CanvasContext::setSurface and
                        // re-create the EGLSurface) if either the Surface changed (as indicated by
                        // generation id), or WindowManager changed the surface size. The latter is
                        // because on some chips, changing the consumer side's BufferQueue size may
                        // not take effect immediately unless we create a new EGLSurface.
                        // Note that frame size change doesn't always imply surface size change (eg.
                        // drag resizing uses fullscreen surface), need to check surfaceSizeChanged
                        // flag from WindowManager.
                        // 如果surface对象的生成ID发生改变，或者surface对象的尺寸发生了改变，
                        // 则需要进行surface对象更新操作（导致CanvasContext::setSurface方法执行，
                        // 已经EGLSurface对象的重建）。surface对象的尺寸改变，因为一些芯片的原因，无法立即
                        // 同步到客户端这边的BufferQueue对象，除非我们创建一个新的EGLSurface对象。
                        // 需注意的，帧大小的改变并不总是意味者surface对象的改变,需要检测来至于WMS的surfaceSizeChanged
                        // 标识
                        mAttachInfo.mHardwareRenderer.updateSurface(mSurface);
                    } catch (OutOfResourcesException e) {
                        handleOutOfResourcesException(e);
                        return;
                    }
                }

                final boolean freeformResizing = (relayoutResult
                        & WindowManagerGlobal.RELAYOUT_RES_DRAG_RESIZING_FREEFORM) != 0;
                final boolean dockedResizing = (relayoutResult
                        & WindowManagerGlobal.RELAYOUT_RES_DRAG_RESIZING_DOCKED) != 0;
                final boolean dragResizing = freeformResizing || dockedResizing;
                if (mDragResizing != dragResizing) {
                    if (dragResizing) {
                        mResizeMode = freeformResizing
                                ? RESIZE_MODE_FREEFORM
                                : RESIZE_MODE_DOCKED_DIVIDER;
                        startDragResizing(mPendingBackDropFrame,
                                mWinFrame.equals(mPendingBackDropFrame), mPendingVisibleInsets,
                                mPendingStableInsets, mResizeMode);
                    } else {
                        // We shouldn't come here, but if we come we should end the resize.
                        endDragResizing();
                    }
                }
                if (!USE_MT_RENDERER) {
                    if (dragResizing) {
                        mCanvasOffsetX = mWinFrame.left;
                        mCanvasOffsetY = mWinFrame.top;
                    } else {
                        mCanvasOffsetX = mCanvasOffsetY = 0;
                    }
                }
            } catch (RemoteException e) {
            }

            if (DEBUG_ORIENTATION) Log.v(
                    TAG, "Relayout returned: frame=" + frame + ", surface=" + mSurface);

            mAttachInfo.mWindowLeft = frame.left;
            mAttachInfo.mWindowTop = frame.top;

            // !!FIXME!! This next section handles the case where we did not get the
            // window size we asked for. We should avoid this by getting a maximum size from
            // the window session beforehand.
            // 我们要求的窗口尺寸
            if (mWidth != frame.width() || mHeight != frame.height()) {
                mWidth = frame.width();
                mHeight = frame.height();
            }

            if (mSurfaceHolder != null) {
                // The app owns the surface; tell it about what is going on.
                // 应用拥有surface对象；则告诉它接下来要发生什么
                if (mSurface.isValid()) {
                    // XXX .copyFrom() doesn't work!
                    //mSurfaceHolder.mSurface.copyFrom(mSurface);
                    mSurfaceHolder.mSurface = mSurface;
                }
                mSurfaceHolder.setSurfaceFrameSize(mWidth, mHeight);
                mSurfaceHolder.mSurfaceLock.unlock();
                // 回调SurfaceHolder的回调接口
                if (mSurface.isValid()) {
                    if (!hadSurface) {
                        mSurfaceHolder.ungetCallbacks();

                        mIsCreating = true;
                        mSurfaceHolderCallback.surfaceCreated(mSurfaceHolder);
                        SurfaceHolder.Callback callbacks[] = mSurfaceHolder.getCallbacks();
                        if (callbacks != null) {
                            for (SurfaceHolder.Callback c : callbacks) {
                                c.surfaceCreated(mSurfaceHolder);
                            }
                        }
                        surfaceChanged = true;
                    }
                    if (surfaceChanged || surfaceGenerationId != mSurface.getGenerationId()) {
                        mSurfaceHolderCallback.surfaceChanged(mSurfaceHolder,
                                lp.format, mWidth, mHeight);
                        SurfaceHolder.Callback callbacks[] = mSurfaceHolder.getCallbacks();
                        if (callbacks != null) {
                            for (SurfaceHolder.Callback c : callbacks) {
                                c.surfaceChanged(mSurfaceHolder, lp.format,
                                        mWidth, mHeight);
                            }
                        }
                    }
                    mIsCreating = false;
                } else if (hadSurface) {
                    mSurfaceHolder.ungetCallbacks();
                    SurfaceHolder.Callback callbacks[] = mSurfaceHolder.getCallbacks();
                    mSurfaceHolderCallback.surfaceDestroyed(mSurfaceHolder);
                    if (callbacks != null) {
                        for (SurfaceHolder.Callback c : callbacks) {
                            c.surfaceDestroyed(mSurfaceHolder);
                        }
                    }
                    mSurfaceHolder.mSurfaceLock.lock();
                    try {
                        mSurfaceHolder.mSurface = new Surface();
                    } finally {
                        mSurfaceHolder.mSurfaceLock.unlock();
                    }
                }
            }

            final ThreadedRenderer hardwareRenderer = mAttachInfo.mHardwareRenderer;
            if (hardwareRenderer != null && hardwareRenderer.isEnabled()) {
                if (hwInitialized
                        || mWidth != hardwareRenderer.getWidth()
                        || mHeight != hardwareRenderer.getHeight()
                        || mNeedsHwRendererSetup) {
                    hardwareRenderer.setup(mWidth, mHeight, mAttachInfo,
                            mWindowAttributes.surfaceInsets);
                    mNeedsHwRendererSetup = false;
                }
            }

            // 根据窗口重布局的结果，判断是否需要重新测量视图树
            if (!mStopped || mReportNextDraw) {
                // 如果没有停止，或者需要报告下一次绘制
                boolean focusChangedDueToTouchMode = ensureTouchModeLocally(
                        (relayoutResult & WindowManagerGlobal.RELAYOUT_RES_IN_TOUCH_MODE) != 0);
                if (focusChangedDueToTouchMode || mWidth != host.getMeasuredWidth()
                        || mHeight != host.getMeasuredHeight() || contentInsetsChanged ||
                        updatedConfiguration) {
                    // 如果因为触摸模式的原因而发生了焦点改变，
                    // 或者窗口宽度经过WMS计算后，与decor视图的上一次测量宽度不一致；
                    // 或者窗口高度经过WMS计算后，与decor视图的上一次测量高度不一致；
                    // 或者内容边衬大小发生了改变；
                    // 或者配置进行了更新。
                    int childWidthMeasureSpec = getRootMeasureSpec(mWidth, lp.width);
                    int childHeightMeasureSpec = getRootMeasureSpec(mHeight, lp.height);

                    if (DEBUG_LAYOUT) Log.v(mTag, "Ooops, something changed!  mWidth="
                            + mWidth + " measuredWidth=" + host.getMeasuredWidth()
                            + " mHeight=" + mHeight
                            + " measuredHeight=" + host.getMeasuredHeight()
                            + " coveredInsetsChanged=" + contentInsetsChanged);

                    // Ask host how big it wants to be
                    // 再次执行测量，以WMS计算后的宽度和高度
                    performMeasure(childWidthMeasureSpec, childHeightMeasureSpec);

                    // Implementation of weights from WindowManager.LayoutParams
                    // We just grow the dimensions as needed and re-measure if
                    // needs be
                    // 来自于WindowManager.LayoutParams的权重实现，
                    // 我们按需发展dimensions，并且如果需要，进行重新测量
                    int width = host.getMeasuredWidth();
                    int height = host.getMeasuredHeight();
                    boolean measureAgain = false;

                    if (lp.horizontalWeight > 0.0f) {
                        width += (int) ((mWidth - width) * lp.horizontalWeight);
                        childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(width,
                                MeasureSpec.EXACTLY);
                        measureAgain = true;
                    }
                    if (lp.verticalWeight > 0.0f) {
                        height += (int) ((mHeight - height) * lp.verticalWeight);
                        childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(height,
                                MeasureSpec.EXACTLY);
                        measureAgain = true;
                    }

                    if (measureAgain) {
                        if (DEBUG_LAYOUT) Log.v(mTag,
                                "And hey let's measure once more: width=" + width
                                        + " height=" + height);
                        performMeasure(childWidthMeasureSpec, childHeightMeasureSpec);
                    }

                    layoutRequested = true;
                }
            }
        } else {
            // Not the first pass and no window/insets/visibility change but the window
            // may have moved and we need check that and if so to update the left and right
            // in the attach info. We translate only the window frame since on window move
            // the window manager tells us only for the new frame but the insets are the
            // same and we do not want to translate them more than once.
            maybeHandleWindowMove(frame);
        }

        // 测量之后则判断需要执行布局操作
        final boolean didLayout = layoutRequested && (!mStopped || mReportNextDraw);
        // 是否触发全局布局监听器
        boolean triggerGlobalLayoutListener = didLayout
                || mAttachInfo.mRecomputeGlobalAttributes;
        if (didLayout) {
            // 执行布局操作
            performLayout(lp, mWidth, mHeight);

            // By this point all views have been sized and positioned
            // We can compute the transparent area

            // 在这个点，所有的视图都是大小的了，并且是被定位的了，
            // 所以我们可以计算透明区域了。
            if ((host.mPrivateFlags & View.PFLAG_REQUEST_TRANSPARENT_REGIONS) != 0) {
                // start out transparent
                // TODO: AVOID THAT CALL BY CACHING THE RESULT?
                host.getLocationInWindow(mTmpLocation);
                mTransparentRegion.set(mTmpLocation[0], mTmpLocation[1],
                        mTmpLocation[0] + host.mRight - host.mLeft,
                        mTmpLocation[1] + host.mBottom - host.mTop);
                // 生成透明区域
                host.gatherTransparentRegion(mTransparentRegion);
                if (mTranslator != null) {
                    mTranslator.translateRegionInWindowToScreen(mTransparentRegion);
                }

                // 如果此次透明区域与上一次的透明区域不相等，则告诉WMS最新的透明区域
                if (!mTransparentRegion.equals(mPreviousTransparentRegion)) {
                    mPreviousTransparentRegion.set(mTransparentRegion);
                    mFullRedrawNeeded = true;
                    // reconfigure window manager
                    try {
                        // 个人猜测第一个参数会让WMS来进行设置完透明区域之后的回调
                        mWindowSession.setTransparentRegion(mWindow, mTransparentRegion);
                    } catch (RemoteException e) {
                    }
                }
            }

            if (DBG) {
                System.out.println("======================================");
                System.out.println("performTraversals -- after setFrame");
                host.debug();
            }
        }

        // 触发全局布局监听器回调
        if (triggerGlobalLayoutListener) {
            mAttachInfo.mRecomputeGlobalAttributes = false;
            mAttachInfo.mTreeObserver.dispatchOnGlobalLayout();
        }

        if (computesInternalInsets) {
            // Clear the original insets.
            // 清除原始的边衬
            final ViewTreeObserver.InternalInsetsInfo insets = mAttachInfo.mGivenInternalInsets;
            insets.reset();

            // Compute new insets in place.
            // 计算新的边衬
            mAttachInfo.mTreeObserver.dispatchOnComputeInternalInsets(insets);
            mAttachInfo.mHasNonEmptyGivenInternalInsets = !insets.isEmpty();

            // Tell the window manager.
            if (insetsPending || !mLastGivenInsets.equals(insets)) {
                mLastGivenInsets.set(insets);

                // Translate insets to screen coordinates if needed.
                final Rect contentInsets;
                final Rect visibleInsets;
                final Region touchableRegion;
                if (mTranslator != null) {
                    contentInsets = mTranslator.getTranslatedContentInsets(insets.contentInsets);
                    visibleInsets = mTranslator.getTranslatedVisibleInsets(insets.visibleInsets);
                    touchableRegion = mTranslator.getTranslatedTouchableArea(insets.touchableRegion);
                } else {
                    contentInsets = insets.contentInsets;
                    visibleInsets = insets.visibleInsets;
                    touchableRegion = insets.touchableRegion;
                }

                try {
                    mWindowSession.setInsets(mWindow, insets.mTouchableInsets,
                            contentInsets, visibleInsets, touchableRegion);
                } catch (RemoteException e) {
                }
            }
        }

        if (mFirst) {
            // handle first focus request
            // 处理第一次的焦点请求
            if (DEBUG_INPUT_RESIZE) Log.v(mTag, "First: mView.hasFocus()="
                    + mView.hasFocus());
            if (mView != null) {
                if (!mView.hasFocus()) {
                    mView.requestFocus(View.FOCUS_FORWARD);
                    if (DEBUG_INPUT_RESIZE) Log.v(mTag, "First: requested focused view="
                            + mView.findFocus());
                } else {
                    if (DEBUG_INPUT_RESIZE) Log.v(mTag, "First: existing focused view="
                            + mView.findFocus());
                }
            }
        }

        // 可见性是否发生了改变
        final boolean changedVisibility = (viewVisibilityChanged || mFirst) && isViewVisible;
        // 是否拥有窗口焦点
        final boolean hasWindowFocus = mAttachInfo.mHasWindowFocus && isViewVisible;
        // 是否重新获取了焦点
        final boolean regainedFocus = hasWindowFocus && mLostWindowFocus;
        if (regainedFocus) {
            mLostWindowFocus = false;
        } else if (!hasWindowFocus && mHadWindowFocus) {
            mLostWindowFocus = true;
        }

        if (changedVisibility || regainedFocus) {
            // Toasts are presented as notifications - don't present them as windows as well
            boolean isToast = (mWindowAttributes == null) ? false
                    : (mWindowAttributes.type == WindowManager.LayoutParams.TYPE_TOAST);
            if (!isToast) {
                host.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
            }
        }

        mFirst = false;
        mWillDrawSoon = false;
        mNewSurfaceNeeded = false;
        mActivityRelaunched = false;
        mViewVisibility = viewVisibility;
        mHadWindowFocus = hasWindowFocus;

        if (hasWindowFocus && !isInLocalFocusMode()) {
            // 拥有了窗口焦点
            // 是否是一个输入法窗口的目标窗口
            final boolean imTarget = WindowManager.LayoutParams
                    .mayUseInputMethod(mWindowAttributes.flags);
            if (imTarget != mLastWasImTarget) {
                mLastWasImTarget = imTarget;
                InputMethodManager imm = InputMethodManager.peekInstance();
                if (imm != null && imTarget) {
                    imm.onPreWindowFocus(mView, hasWindowFocus);
                    imm.onPostWindowFocus(mView, mView.findFocus(),
                            mWindowAttributes.softInputMode,
                            !mHasHadWindowFocus, mWindowAttributes.flags);
                }
            }
        }

        // Remember if we must report the next draw.
        // 缓存是否我们必须报告下一次绘制
        if ((relayoutResult & WindowManagerGlobal.RELAYOUT_RES_FIRST_TIME) != 0) {
            mReportNextDraw = true;
        }

        boolean cancelDraw = mAttachInfo.mTreeObserver.dispatchOnPreDraw() || !isViewVisible;

        if (!cancelDraw && !newSurface) {
            // 如果继续绘制，且不是一个最新的surface对象
            if (mPendingTransitions != null && mPendingTransitions.size() > 0) {
                for (int i = 0; i < mPendingTransitions.size(); ++i) {
                    mPendingTransitions.get(i).startChangingAnimations();
                }
                mPendingTransitions.clear();
            }

            performDraw();
        } else {
            if (isViewVisible) {
                // Try again
                // 为了告诉WMS即将用一个新的surface对象进行视图树的绘制了
                scheduleTraversals();
            } else if (mPendingTransitions != null && mPendingTransitions.size() > 0) {
                for (int i = 0; i < mPendingTransitions.size(); ++i) {
                    mPendingTransitions.get(i).endChangingAnimations();
                }
                mPendingTransitions.clear();
            }
        }

        mIsInTraversal = false;
    }

    private void maybeHandleWindowMove(Rect frame) {

        // TODO: Well, we are checking whether the frame has changed similarly
        // to how this is done for the insets. This is however incorrect since
        // the insets and the frame are translated. For example, the old frame
        // was (1, 1 - 1, 1) and was translated to say (2, 2 - 2, 2), now the new
        // reported frame is (2, 2 - 2, 2) which implies no change but this is not
        // true since we are comparing a not translated value to a translated one.
        // This scenario is rare but we may want to fix that.

        final boolean windowMoved = mAttachInfo.mWindowLeft != frame.left
                || mAttachInfo.mWindowTop != frame.top;
        if (windowMoved) {
            if (mTranslator != null) {
                mTranslator.translateRectInScreenToAppWinFrame(frame);
            }
            mAttachInfo.mWindowLeft = frame.left;
            mAttachInfo.mWindowTop = frame.top;
        }
        if (windowMoved || mAttachInfo.mNeedsUpdateLightCenter) {
            // Update the light position for the new offsets.
            if (mAttachInfo.mHardwareRenderer != null) {
                mAttachInfo.mHardwareRenderer.setLightCenter(mAttachInfo);
            }
            mAttachInfo.mNeedsUpdateLightCenter = false;
        }
    }

    private void handleOutOfResourcesException(Surface.OutOfResourcesException e) {
        Log.e(mTag, "OutOfResourcesException initializing HW surface", e);
        try {
            if (!mWindowSession.outOfMemory(mWindow) &&
                    Process.myUid() != Process.SYSTEM_UID) {
                Slog.w(mTag, "No processes killed for memory; killing self");
                Process.killProcess(Process.myPid());
            }
        } catch (RemoteException ex) {
        }
        mLayoutRequested = true;    // ask wm for a new surface next time.
    }

    private void performMeasure(int childWidthMeasureSpec, int childHeightMeasureSpec) {
        Trace.traceBegin(Trace.TRACE_TAG_VIEW, "measure");
        try {
            mView.measure(childWidthMeasureSpec, childHeightMeasureSpec);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIEW);
        }
    }

    /**
     * Called by {@link android.view.View#isInLayout()} to determine whether the view hierarchy
     * is currently undergoing a layout pass.
     *
     * @return whether the view hierarchy is currently undergoing a layout pass
     */
    boolean isInLayout() {
        return mInLayout;
    }

    /**
     * Called by {@link android.view.View#requestLayout()} if the view hierarchy is currently
     * undergoing a layout pass. requestLayout() should not generally be called during layout,
     * unless the container hierarchy knows what it is doing (i.e., it is fine as long as
     * all children in that container hierarchy are measured and laid out at the end of the layout
     * pass for that container). If requestLayout() is called anyway, we handle it correctly
     * by registering all requesters during a frame as it proceeds. At the end of the frame,
     * we check all of those views to see if any still have pending layout requests, which
     * indicates that they were not correctly handled by their container hierarchy. If that is
     * the case, we clear all such flags in the tree, to remove the buggy flag state that leads
     * to blank containers, and force a second request/measure/layout pass in this frame. If
     * more requestLayout() calls are received during that second layout pass, we post those
     * requests to the next frame to avoid possible infinite loops.
     * <p>
     * 被View#requestLayout方法调用，如果视图树正处于一场布局流程之中。
     * requestLayout()方法在布局过程之中一般是不应该被调用的，触发容器树知道在做什么
     * 总之如果requestLayout()方法被调用，则我们通过注册所有的调用者来正确处理，当一个布局正在被处理的期间。
     * 在此布局的末尾，我们检测所有的这些视图用以判断是否存在请求过，且未能正确处理的布局的视图。
     * 如果存在这种情况，我们将清楚所有的这些标识，从而移除使得容器无效的漏洞标识，并强制在此布局之中进行第二次布局。
     * 如果在第二次布局过程之中收到了更多的requestLayout请求，则我们将这些请求传递给下一帧，用以避免无限的卡顿
     * <p>The return value from this method indicates whether the request should proceed
     * (if it is a request during the first layout pass) or should be skipped and posted to the
     * next frame (if it is a request during the second layout pass).
     * 此方法的返回值用以标识是否请求应该被处理（如果此请求在第一次布局过程之中到来）
     * 或者应该被跳过并传递个下一帧（如果此请求在第二次布局过程之中到来）
     * </p>
     *
     * @param view the view that requested the layout.
     * @return true if request should proceed, false otherwise.
     */
    boolean requestLayoutDuringLayout(final View view) {
        if (view.mParent == null || view.mAttachInfo == null) {
            // Would not normally trigger another layout, so just let it pass through as usual
            return true;
        }
        if (!mLayoutRequesters.contains(view)) {
            mLayoutRequesters.add(view);
        }
        if (!mHandlingLayoutInLayoutRequest) {
            // Let the request proceed normally; it will be processed in a second layout pass
            // if necessary
            // 让请求处理一般化，将在第二次布局过程中被处理，如果需要
            return true;
        } else {
            // Don't let the request proceed during the second layout pass.
            // It will post to the next frame instead.
            return false;
        }
    }

    private void performLayout(WindowManager.LayoutParams lp, int desiredWindowWidth,
                               int desiredWindowHeight) {
        mLayoutRequested = false;
        mScrollMayChange = true;
        mInLayout = true;

        final View host = mView;
        if (DEBUG_ORIENTATION || DEBUG_LAYOUT) {
            Log.v(mTag, "Laying out " + host + " to (" +
                    host.getMeasuredWidth() + ", " + host.getMeasuredHeight() + ")");
        }

        Trace.traceBegin(Trace.TRACE_TAG_VIEW, "layout");
        try {
            // 开始布局
            host.layout(0, 0, host.getMeasuredWidth(), host.getMeasuredHeight());

            mInLayout = false;
            // 在布局过程之中，请求过布局的个数
            int numViewsRequestingLayout = mLayoutRequesters.size();
            if (numViewsRequestingLayout > 0) {
                // requestLayout() was called during layout.
                // If no layout-request flags are set on the requesting views, there is no problem.
                // If some requests are still pending, then we need to clear those flags and do
                // a full request/measure/layout pass to handle this situation.
                // 在布局过程中requesLayout方法被调用。
                // 在此请求的视图中，如果布局标识没有被设置，则不会引起任何问题。
                // 如果一写请求依然即将发生，则我们需要清除这些标识，并做一次全量的请求/测量/布局流程用以处理此情况
                // validLayoutRequesters表示请求有效的视图，即此视图被粘贴到一个窗口，且它所有的父视图的可视性都
                // 不为GONE
                ArrayList<View> validLayoutRequesters = getValidLayoutRequesters(mLayoutRequesters,
                        false);
                if (validLayoutRequesters != null) {
                    // Set this flag to indicate that any further requests are happening during
                    // the second pass, which may result in posting those requests to the next
                    // frame instead
                    // 设置此标识用来标识任何在第二次布局过程之中发生的布局请求，将会在下一次frame之中被代替
                    mHandlingLayoutInLayoutRequest = true;

                    // Process fresh layout requests, then measure and layout
                    // 执行第二次请求
                    int numValidRequests = validLayoutRequesters.size();
                    for (int i = 0; i < numValidRequests; ++i) {
                        final View view = validLayoutRequesters.get(i);
                        Log.w("View", "requestLayout() improperly called by " + view +
                                " during layout: running second layout pass");
                        // 布局请求延迟到此处
                        view.requestLayout();
                    }
                    // 测量视图树
                    measureHierarchy(host, lp, mView.getContext().getResources(),
                            desiredWindowWidth, desiredWindowHeight);
                    mInLayout = true;
                    // 布局视图树
                    host.layout(0, 0, host.getMeasuredWidth(), host.getMeasuredHeight());

                    mHandlingLayoutInLayoutRequest = false;

                    // Check the valid requests again, this time without checking/clearing the
                    // layout flags, since requests happening during the second pass get noop'd
                    // 再次检测有效期的布局请求者，但是本次将不会检测和清除布局标志，因为请求者一定会在下一个
                    // 遍历之中进行
                    validLayoutRequesters = getValidLayoutRequesters(mLayoutRequesters, true);
                    if (validLayoutRequesters != null) {
                        final ArrayList<View> finalRequesters = validLayoutRequesters;
                        // Post second-pass requests to the next frame
                        getRunQueue().post(new Runnable() {
                            @Override
                            public void run() {
                                int numValidRequests = finalRequesters.size();
                                for (int i = 0; i < numValidRequests; ++i) {
                                    final View view = finalRequesters.get(i);
                                    Log.w("View", "requestLayout() improperly called by " + view +
                                            " during second layout pass: posting in next frame");
                                    view.requestLayout();
                                }
                            }
                        });
                    }
                }

            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIEW);
        }
        mInLayout = false;
    }

    /**
     * This method is called during layout when there have been calls to requestLayout() during
     * layout. It walks through the list of views that requested layout to determine which ones
     * still need it, based on visibility in the hierarchy and whether they have already been
     * handled (as is usually the case with ListView children).
     * 当在布局过程之中，有视图调用了requestLayout方法，则此方法会在布局过程之中被调用。
     * 此方法将遍历在布局过程之中调用过requestLayout方法的视图列表，用以决定哪些视图的requestLayout是有效的。
     * 判断的依据是，视图在视图树中的可视性，以及是否视图已经被处理了（通常有ListView的子视图引起）
     *
     * @param layoutRequesters     The list of views that requested layout during layout
     * @param secondLayoutRequests Whether the requests were issued during the second layout pass.
     *                             If so, the FORCE_LAYOUT flag was not set on requesters.
     *                             是否请求布局的情况发生在第二次布局流程之中，
     *                             如果是的，则强制布局标识将不会在请求者之中被设置
     * @return A list of the actual views that still need to be laid out.
     */
    private ArrayList<View> getValidLayoutRequesters(ArrayList<View> layoutRequesters,
                                                     boolean secondLayoutRequests) {

        int numViewsRequestingLayout = layoutRequesters.size();
        ArrayList<View> validLayoutRequesters = null;
        for (int i = 0; i < numViewsRequestingLayout; ++i) {
            View view = layoutRequesters.get(i);
            if (view != null && view.mAttachInfo != null && view.mParent != null &&
                    (secondLayoutRequests || (view.mPrivateFlags & View.PFLAG_FORCE_LAYOUT) ==
                            View.PFLAG_FORCE_LAYOUT)) {
                // 如果视图有效，且需要强制布局
                boolean gone = false;
                View parent = view;
                // Only trigger new requests for views in a non-GONE hierarchy
                // 判断请求布局的视图其所在的继承链上，是否存在可视状态为GONE是父视图。
                while (parent != null) {
                    if ((parent.mViewFlags & View.VISIBILITY_MASK) == View.GONE) {
                        gone = true;
                        break;
                    }
                    if (parent.mParent instanceof View) {
                        parent = (View) parent.mParent;
                    } else {
                        parent = null;
                    }
                }
                // 视图请求者不存在父视图为GONE的情况，则此视图请求者的请求是有效的
                if (!gone) {
                    if (validLayoutRequesters == null) {
                        validLayoutRequesters = new ArrayList<View>();
                    }
                    validLayoutRequesters.add(view);
                }
            }
        }
        // 清除强制布局的标识
        if (!secondLayoutRequests) {
            // If we're checking the layout flags, then we need to clean them up also
            for (int i = 0; i < numViewsRequestingLayout; ++i) {
                View view = layoutRequesters.get(i);
                while (view != null &&
                        (view.mPrivateFlags & View.PFLAG_FORCE_LAYOUT) != 0) {
                    view.mPrivateFlags &= ~View.PFLAG_FORCE_LAYOUT;
                    if (view.mParent instanceof View) {
                        view = (View) view.mParent;
                    } else {
                        view = null;
                    }
                }
            }
        }
        layoutRequesters.clear();
        return validLayoutRequesters;
    }

    /**
     * 开始请求透明区域
     *
     * @param child the view requesting the transparent region computation
     */
    @Override
    public void requestTransparentRegion(View child) {
        // the test below should not fail unless someone is messing with us
        checkThread();
        if (mView == child) {
            mView.mPrivateFlags |= View.PFLAG_REQUEST_TRANSPARENT_REGIONS;
            // Need to make sure we re-evaluate the window attributes next
            // time around, to ensure the window has the correct format.
            // 需要确保我们重新评估了窗口的参数，用以确保窗口有正确的格式
            mWindowAttributesChanged = true;
            mWindowAttributesChangesFlag = 0;
            requestLayout();
        }
    }

    /**
     * Figures out the measure spec for the root view in a window based on it's
     * layout params.
     *
     * @param windowSize    The available width or height of the window
     * @param rootDimension The layout params for one dimension (width or height) of the
     *                      window.
     * @return The measure spec to use to measure the root view.
     */
    private static int getRootMeasureSpec(int windowSize, int rootDimension) {
        int measureSpec;
        switch (rootDimension) {

            case ViewGroup.LayoutParams.MATCH_PARENT:
                // Window can't resize. Force root view to be windowSize.
                measureSpec = MeasureSpec.makeMeasureSpec(windowSize, MeasureSpec.EXACTLY);
                break;
            case ViewGroup.LayoutParams.WRAP_CONTENT:
                // Window can resize. Set max size for root view.
                measureSpec = MeasureSpec.makeMeasureSpec(windowSize, MeasureSpec.AT_MOST);
                break;
            default:
                // Window wants to be an exact size. Force root view to be that size.
                measureSpec = MeasureSpec.makeMeasureSpec(rootDimension, MeasureSpec.EXACTLY);
                break;
        }
        return measureSpec;
    }

    int mHardwareXOffset;
    int mHardwareYOffset;

    @Override
    public void onHardwarePreDraw(DisplayListCanvas canvas) {
        canvas.translate(-mHardwareXOffset, -mHardwareYOffset);
    }

    @Override
    public void onHardwarePostDraw(DisplayListCanvas canvas) {
        drawAccessibilityFocusedDrawableIfNeeded(canvas);
        for (int i = mWindowCallbacks.size() - 1; i >= 0; i--) {
            mWindowCallbacks.get(i).onPostDraw(canvas);
        }
    }

    /**
     * @hide
     */
    void outputDisplayList(View view) {
        view.mRenderNode.output();
        if (mAttachInfo.mHardwareRenderer != null) {
            ((ThreadedRenderer) mAttachInfo.mHardwareRenderer).serializeDisplayListTree();
        }
    }

    /**
     * @param enabled 是否以一秒60次的频率渲染？
     * @see #PROPERTY_PROFILE_RENDERING
     */
    private void profileRendering(boolean enabled) {
        if (mProfileRendering) {
            mRenderProfilingEnabled = enabled;

            if (mRenderProfiler != null) {
                mChoreographer.removeFrameCallback(mRenderProfiler);
            }
            if (mRenderProfilingEnabled) {
                if (mRenderProfiler == null) {
                    mRenderProfiler = new Choreographer.FrameCallback() {
                        @Override
                        public void doFrame(long frameTimeNanos) {
                            mDirty.set(0, 0, mWidth, mHeight);
                            scheduleTraversals();
                            if (mRenderProfilingEnabled) {
                                mChoreographer.postFrameCallback(mRenderProfiler);
                            }
                        }
                    };
                }
                mChoreographer.postFrameCallback(mRenderProfiler);
            } else {
                mRenderProfiler = null;
            }
        }
    }

    /**
     * Called from draw() when DEBUG_FPS is enabled
     */
    private void trackFPS() {
        // Tracks frames per second drawn. First value in a series of draws may be bogus
        // because it down not account for the intervening idle time
        long nowTime = System.currentTimeMillis();
        if (mFpsStartTime < 0) {
            mFpsStartTime = mFpsPrevTime = nowTime;
            mFpsNumFrames = 0;
        } else {
            ++mFpsNumFrames;
            String thisHash = Integer.toHexString(System.identityHashCode(this));
            long frameTime = nowTime - mFpsPrevTime;
            long totalTime = nowTime - mFpsStartTime;
            Log.v(mTag, "0x" + thisHash + "\tFrame time:\t" + frameTime);
            mFpsPrevTime = nowTime;
            if (totalTime > 1000) {
                float fps = (float) mFpsNumFrames * 1000 / totalTime;
                Log.v(mTag, "0x" + thisHash + "\tFPS:\t" + fps);
                mFpsStartTime = nowTime;
                mFpsNumFrames = 0;
            }
        }
    }

    /**
     * 执行绘制
     */
    private void performDraw() {
        if (mAttachInfo.mDisplayState == Display.STATE_OFF && !mReportNextDraw) {
            return;
        }

        final boolean fullRedrawNeeded = mFullRedrawNeeded;
        mFullRedrawNeeded = false;

        mIsDrawing = true;
        Trace.traceBegin(Trace.TRACE_TAG_VIEW, "draw");
        try {
            draw(fullRedrawNeeded);
        } finally {
            mIsDrawing = false;
            Trace.traceEnd(Trace.TRACE_TAG_VIEW);
        }

        // For whatever reason we didn't create a HardwareRenderer, end any
        // hardware animations that are now dangling
        // 无论因为什么原因，我们之前没有创建一个硬件元燃气，则结束现在正在摇摆的所有硬件动画
        if (mAttachInfo.mPendingAnimatingRenderNodes != null) {
            final int count = mAttachInfo.mPendingAnimatingRenderNodes.size();
            for (int i = 0; i < count; i++) {
                mAttachInfo.mPendingAnimatingRenderNodes.get(i).endAllAnimators();
            }
            mAttachInfo.mPendingAnimatingRenderNodes.clear();
        }

        if (mReportNextDraw) {
            mReportNextDraw = false;

            // if we're using multi-thread renderer, wait for the window frame draws
            if (mWindowDrawCountDown != null) {
                try {
                    mWindowDrawCountDown.await();
                } catch (InterruptedException e) {
                    Log.e(mTag, "Window redraw count down interruped!");
                }
                mWindowDrawCountDown = null;
            }

            if (mAttachInfo.mHardwareRenderer != null) {
                mAttachInfo.mHardwareRenderer.fence();
                mAttachInfo.mHardwareRenderer.setStopped(mStopped);
            }

            if (LOCAL_LOGV) {
                Log.v(mTag, "FINISHED DRAWING: " + mWindowAttributes.getTitle());
            }
            if (mSurfaceHolder != null && mSurface.isValid()) {
                mSurfaceHolderCallback.surfaceRedrawNeeded(mSurfaceHolder);
                SurfaceHolder.Callback callbacks[] = mSurfaceHolder.getCallbacks();
                if (callbacks != null) {
                    for (SurfaceHolder.Callback c : callbacks) {
                        if (c instanceof SurfaceHolder.Callback2) {
                            ((SurfaceHolder.Callback2) c).surfaceRedrawNeeded(mSurfaceHolder);
                        }
                    }
                }
            }
            try {
                // 告诉WMS完成了绘制
                mWindowSession.finishDrawing(mWindow);
            } catch (RemoteException e) {
            }
        }
    }

    /**
     * 绘制视图树
     *
     * @param fullRedrawNeeded 是否需要全量重绘
     */
    private void draw(boolean fullRedrawNeeded) {
        Surface surface = mSurface;
        if (!surface.isValid()) {
            return;
        }

        if (DEBUG_FPS) {
            trackFPS();
        }

        if (!sFirstDrawComplete) {
            synchronized (sFirstDrawHandlers) {
                sFirstDrawComplete = true;
                final int count = sFirstDrawHandlers.size();
                for (int i = 0; i < count; i++) {
                    mHandler.post(sFirstDrawHandlers.get(i));
                }
            }
        }

        scrollToRectOrFocus(null, false);

        if (mAttachInfo.mViewScrollChanged) {
            mAttachInfo.mViewScrollChanged = false;
            mAttachInfo.mTreeObserver.dispatchOnScrollChanged();
        }

        boolean animating = mScroller != null && mScroller.computeScrollOffset();
        final int curScrollY;
        if (animating) {
            curScrollY = mScroller.getCurrY();
        } else {
            curScrollY = mScrollY;
        }
        if (mCurScrollY != curScrollY) {
            mCurScrollY = curScrollY;
            fullRedrawNeeded = true;
            if (mView instanceof RootViewSurfaceTaker) {
                ((RootViewSurfaceTaker) mView).onRootViewScrollYChanged(mCurScrollY);
            }
        }

        final float appScale = mAttachInfo.mApplicationScale;
        final boolean scalingRequired = mAttachInfo.mScalingRequired;

        int resizeAlpha = 0;

        final Rect dirty = mDirty;
        if (mSurfaceHolder != null) {
            // The app owns the surface, we won't draw.
            dirty.setEmpty();
            if (animating && mScroller != null) {
                mScroller.abortAnimation();
            }
            return;
        }

        if (fullRedrawNeeded) {
            mAttachInfo.mIgnoreDirtyState = true;
            dirty.set(0, 0, (int) (mWidth * appScale + 0.5f), (int) (mHeight * appScale + 0.5f));
        }

        if (DEBUG_ORIENTATION || DEBUG_DRAW) {
            Log.v(mTag, "Draw " + mView + "/"
                    + mWindowAttributes.getTitle()
                    + ": dirty={" + dirty.left + "," + dirty.top
                    + "," + dirty.right + "," + dirty.bottom + "} surface="
                    + surface + " surface.isValid()=" + surface.isValid() + ", appScale:" +
                    appScale + ", width=" + mWidth + ", height=" + mHeight);
        }

        mAttachInfo.mTreeObserver.dispatchOnDraw();

        int xOffset = -mCanvasOffsetX;
        int yOffset = -mCanvasOffsetY + curScrollY;
        final WindowManager.LayoutParams params = mWindowAttributes;
        final Rect surfaceInsets = params != null ? params.surfaceInsets : null;
        if (surfaceInsets != null) {
            xOffset -= surfaceInsets.left;
            yOffset -= surfaceInsets.top;

            // Offset dirty rect for surface insets.
            dirty.offset(surfaceInsets.left, surfaceInsets.right);
        }

        boolean accessibilityFocusDirty = false;
        final Drawable drawable = mAttachInfo.mAccessibilityFocusDrawable;
        if (drawable != null) {
            final Rect bounds = mAttachInfo.mTmpInvalRect;
            final boolean hasFocus = getAccessibilityFocusedRect(bounds);
            if (!hasFocus) {
                bounds.setEmpty();
            }
            if (!bounds.equals(drawable.getBounds())) {
                accessibilityFocusDirty = true;
            }
        }

        mAttachInfo.mDrawingTime =
                mChoreographer.getFrameTimeNanos() / TimeUtils.NANOS_PER_MS;

        if (!dirty.isEmpty() || mIsAnimating || accessibilityFocusDirty) {
            if (mAttachInfo.mHardwareRenderer != null && mAttachInfo.mHardwareRenderer.isEnabled()) {
                // If accessibility focus moved, always invalidate the root.
                boolean invalidateRoot = accessibilityFocusDirty || mInvalidateRootRequested;
                mInvalidateRootRequested = false;

                // Draw with hardware renderer.
                mIsAnimating = false;

                if (mHardwareYOffset != yOffset || mHardwareXOffset != xOffset) {
                    mHardwareYOffset = yOffset;
                    mHardwareXOffset = xOffset;
                    invalidateRoot = true;
                }

                if (invalidateRoot) {
                    mAttachInfo.mHardwareRenderer.invalidateRoot();
                }

                dirty.setEmpty();

                // Stage the content drawn size now. It will be transferred to the renderer
                // shortly before the draw commands get send to the renderer.
                final boolean updated = updateContentDrawBounds();

                if (mReportNextDraw) {
                    // report next draw overrides setStopped()
                    // This value is re-sync'd to the value of mStopped
                    // in the handling of mReportNextDraw post-draw.
                    mAttachInfo.mHardwareRenderer.setStopped(false);
                }

                if (updated) {
                    requestDrawWindow();
                }

                mAttachInfo.mHardwareRenderer.draw(mView, mAttachInfo, this);
            } else {
                // If we get here with a disabled & requested hardware renderer, something went
                // wrong (an invalidate posted right before we destroyed the hardware surface
                // for instance) so we should just bail out. Locking the surface with software
                // rendering at this point would lock it forever and prevent hardware renderer
                // from doing its job when it comes back.
                // Before we request a new frame we must however attempt to reinitiliaze the
                // hardware renderer if it's in requested state. This would happen after an
                // eglTerminate() for instance.
                if (mAttachInfo.mHardwareRenderer != null &&
                        !mAttachInfo.mHardwareRenderer.isEnabled() &&
                        mAttachInfo.mHardwareRenderer.isRequested()) {

                    try {
                        mAttachInfo.mHardwareRenderer.initializeIfNeeded(
                                mWidth, mHeight, mAttachInfo, mSurface, surfaceInsets);
                    } catch (OutOfResourcesException e) {
                        handleOutOfResourcesException(e);
                        return;
                    }

                    mFullRedrawNeeded = true;
                    scheduleTraversals();
                    return;
                }

                if (!drawSoftware(surface, mAttachInfo, xOffset, yOffset, scalingRequired, dirty)) {
                    return;
                }
            }
        }

        if (animating) {
            mFullRedrawNeeded = true;
            scheduleTraversals();
        }
    }

    /**
     * @return true if drawing was successful, false if an error occurred
     */
    private boolean drawSoftware(Surface surface, AttachInfo attachInfo, int xoff, int yoff,
                                 boolean scalingRequired, Rect dirty) {

        // Draw with software renderer.
        final Canvas canvas;
        try {
            final int left = dirty.left;
            final int top = dirty.top;
            final int right = dirty.right;
            final int bottom = dirty.bottom;

            canvas = mSurface.lockCanvas(dirty);

            // The dirty rectangle can be modified by Surface.lockCanvas()
            //noinspection ConstantConditions
            if (left != dirty.left || top != dirty.top || right != dirty.right
                    || bottom != dirty.bottom) {
                attachInfo.mIgnoreDirtyState = true;
            }

            // TODO: Do this in native
            canvas.setDensity(mDensity);
        } catch (Surface.OutOfResourcesException e) {
            handleOutOfResourcesException(e);
            return false;
        } catch (IllegalArgumentException e) {
            Log.e(mTag, "Could not lock surface", e);
            // Don't assume this is due to out of memory, it could be
            // something else, and if it is something else then we could
            // kill stuff (or ourself) for no reason.
            mLayoutRequested = true;    // ask wm for a new surface next time.
            return false;
        }

        try {
            if (DEBUG_ORIENTATION || DEBUG_DRAW) {
                Log.v(mTag, "Surface " + surface + " drawing to bitmap w="
                        + canvas.getWidth() + ", h=" + canvas.getHeight());
                //canvas.drawARGB(255, 255, 0, 0);
            }

            // If this bitmap's format includes an alpha channel, we
            // need to clear it before drawing so that the child will
            // properly re-composite its drawing on a transparent
            // background. This automatically respects the clip/dirty region
            // or
            // If we are applying an offset, we need to clear the area
            // where the offset doesn't appear to avoid having garbage
            // left in the blank areas.
            if (!canvas.isOpaque() || yoff != 0 || xoff != 0) {
                canvas.drawColor(0, PorterDuff.Mode.CLEAR);
            }

            dirty.setEmpty();
            mIsAnimating = false;
            mView.mPrivateFlags |= View.PFLAG_DRAWN;

            if (DEBUG_DRAW) {
                Context cxt = mView.getContext();
                Log.i(mTag, "Drawing: package:" + cxt.getPackageName() +
                        ", metrics=" + cxt.getResources().getDisplayMetrics() +
                        ", compatibilityInfo=" + cxt.getResources().getCompatibilityInfo());
            }
            try {
                canvas.translate(-xoff, -yoff);
                if (mTranslator != null) {
                    mTranslator.translateCanvas(canvas);
                }
                canvas.setScreenDensity(scalingRequired ? mNoncompatDensity : 0);
                attachInfo.mSetIgnoreDirtyState = false;

                mView.draw(canvas);

                drawAccessibilityFocusedDrawableIfNeeded(canvas);
            } finally {
                if (!attachInfo.mSetIgnoreDirtyState) {
                    // Only clear the flag if it was not set during the mView.draw() call
                    attachInfo.mIgnoreDirtyState = false;
                }
            }
        } finally {
            try {
                surface.unlockCanvasAndPost(canvas);
            } catch (IllegalArgumentException e) {
                Log.e(mTag, "Could not unlock surface", e);
                mLayoutRequested = true;    // ask wm for a new surface next time.
                //noinspection ReturnInsideFinallyBlock
                return false;
            }

            if (LOCAL_LOGV) {
                Log.v(mTag, "Surface " + surface + " unlockCanvasAndPost");
            }
        }
        return true;
    }

    /**
     * We want to draw a highlight around the current accessibility focused.
     * Since adding a style for all possible view is not a viable option we
     * have this specialized drawing method.
     * <p>
     * Note: We are doing this here to be able to draw the highlight for
     * virtual views in addition to real ones.
     *
     * @param canvas The canvas on which to draw.
     */
    private void drawAccessibilityFocusedDrawableIfNeeded(Canvas canvas) {
        final Rect bounds = mAttachInfo.mTmpInvalRect;
        if (getAccessibilityFocusedRect(bounds)) {
            final Drawable drawable = getAccessibilityFocusedDrawable();
            if (drawable != null) {
                drawable.setBounds(bounds);
                drawable.draw(canvas);
            }
        } else if (mAttachInfo.mAccessibilityFocusDrawable != null) {
            mAttachInfo.mAccessibilityFocusDrawable.setBounds(0, 0, 0, 0);
        }
    }

    private boolean getAccessibilityFocusedRect(Rect bounds) {
        final AccessibilityManager manager = AccessibilityManager.getInstance(mView.mContext);
        if (!manager.isEnabled() || !manager.isTouchExplorationEnabled()) {
            return false;
        }

        final View host = mAccessibilityFocusedHost;
        if (host == null || host.mAttachInfo == null) {
            return false;
        }

        final AccessibilityNodeProvider provider = host.getAccessibilityNodeProvider();
        if (provider == null) {
            host.getBoundsOnScreen(bounds, true);
        } else if (mAccessibilityFocusedVirtualView != null) {
            mAccessibilityFocusedVirtualView.getBoundsInScreen(bounds);
        } else {
            return false;
        }

        // Transform the rect into window-relative coordinates.
        final AttachInfo attachInfo = mAttachInfo;
        bounds.offset(0, attachInfo.mViewRootImpl.mScrollY);
        bounds.offset(-attachInfo.mWindowLeft, -attachInfo.mWindowTop);
        if (!bounds.intersect(0, 0, attachInfo.mViewRootImpl.mWidth,
                attachInfo.mViewRootImpl.mHeight)) {
            // If no intersection, set bounds to empty.
            bounds.setEmpty();
        }
        return !bounds.isEmpty();
    }

    private Drawable getAccessibilityFocusedDrawable() {
        // Lazily load the accessibility focus drawable.
        if (mAttachInfo.mAccessibilityFocusDrawable == null) {
            final TypedValue value = new TypedValue();
            final boolean resolved = mView.mContext.getTheme().resolveAttribute(
                    R.attr.accessibilityFocusedDrawable, value, true);
            if (resolved) {
                mAttachInfo.mAccessibilityFocusDrawable =
                        mView.mContext.getDrawable(value.resourceId);
            }
        }
        return mAttachInfo.mAccessibilityFocusDrawable;
    }

    /**
     * Requests that the root render node is invalidated next time we perform a draw, such that
     * {@link WindowCallbacks#onPostDraw} gets called.
     */
    public void requestInvalidateRootRenderNode() {
        mInvalidateRootRequested = true;
    }

    /**
     * 滚动到指定的矩形或者焦点？
     *
     * @param rectangle 需要滚动到的矩形,可为空
     * @param immediate 是否不执行滚动动画
     * @return 是否发生了滚动?
     */
    boolean scrollToRectOrFocus(Rect rectangle, boolean immediate) {
        final Rect ci = mAttachInfo.mContentInsets;
        final Rect vi = mAttachInfo.mVisibleInsets;
        int scrollY = 0;
        boolean handled = false;

        if (vi.left > ci.left || vi.top > ci.top
                || vi.right > ci.right || vi.bottom > ci.bottom) {
            // 可见视图有一边一定超过了内容视图
            // We'll assume that we aren't going to change the scroll
            // offset, since we want to avoid that unless it is actually
            // going to make the focus visible...  otherwise we scroll
            // all over the place.
            // 我们将假定我们将不会改变滚动的偏移量，因为我们将避免确切的使焦点可见...
            // 不然我们会到处滚动
            scrollY = mScrollY;
            // We can be called for two different situations: during a draw,
            // to update the scroll position if the focus has changed (in which
            // case 'rectangle' is null), or in response to a
            // requestChildRectangleOnScreen() call (in which case 'rectangle'
            // is non-null and we just want to scroll to whatever that
            // rectangle is).
            // 此方法能够为两种情况进行调用：在绘制期间，因为焦点改变的原因，而更新滚动位置（此种情况，第一个参数为空）
            // 第二种情况是，作为requestChildRectangleOnScreen方法的一种响应
            // 这种情况下（第一个入参为非空，并且我们将期望滚动到此矩形所在的任何位置）
            final View focus = mView.findFocus();
            if (focus == null) {
                return false;
            }
            View lastScrolledFocus = (mLastScrolledFocus != null) ? mLastScrolledFocus.get() : null;
            if (focus != lastScrolledFocus) {
                // If the focus has changed, then ignore any requests to scroll
                // to a rectangle; first we want to make sure the entire focus
                // view is visible.
                // 如果焦点改变了，则忽略任何滚动到指定矩形位置之中的请求；
                // 因为我们需要优先确保实体的焦点是可见的
                rectangle = null;
            }
            if (DEBUG_INPUT_RESIZE) Log.v(mTag, "Eval scroll: focus=" + focus
                    + " rectangle=" + rectangle + " ci=" + ci
                    + " vi=" + vi);
            if (focus == lastScrolledFocus && !mScrollMayChange && rectangle == null) {
                // Optimization: if the focus hasn't changed since last
                // time, and no layout has happened, then just leave things
                // as they are.
                // 优化：自从上次开始，如果焦点没有改变，并且没有热呢和布局发生，直接离开
                if (DEBUG_INPUT_RESIZE) Log.v(mTag, "Keeping scroll y="
                        + mScrollY + " vi=" + vi.toShortString());
            } else {
                // We need to determine if the currently focused view is
                // within the visible part of the window and, if not, apply
                // a pan so it can be seen.
                // 我们需要决定是否当前的焦点视图在窗口的可视范围之内；
                // 如果不在可视范围之中，则提供一个调整，以便焦点视图能够被看见
                mLastScrolledFocus = new WeakReference<View>(focus);
                mScrollMayChange = false;
                if (DEBUG_INPUT_RESIZE) Log.v(mTag, "Need to scroll?");
                // Try to find the rectangle from the focus view.
                // 尽力找到来自于焦点视图的矩形
                if (focus.getGlobalVisibleRect(mVisRect, null)) {
                    // 焦点视图一定有一部分是可见的
                    if (DEBUG_INPUT_RESIZE) Log.v(mTag, "Root w="
                            + mView.getWidth() + " h=" + mView.getHeight()
                            + " ci=" + ci.toShortString()
                            + " vi=" + vi.toShortString());
                    if (rectangle == null) {
                        // 如果矩形为空的情况
                        // mTempRect表示焦点视图可绘制的区域
                        focus.getFocusedRect(mTempRect);
                        if (DEBUG_INPUT_RESIZE) Log.v(mTag, "Focus " + focus
                                + ": focusRect=" + mTempRect.toShortString());
                        if (mView instanceof ViewGroup) {
                            // 将某个子视图的范围移动指定的矩形距离？
                            ((ViewGroup) mView).offsetDescendantRectToMyCoords(
                                    focus, mTempRect);
                        }
                        if (DEBUG_INPUT_RESIZE) Log.v(mTag,
                                "Focus in window: focusRect="
                                        + mTempRect.toShortString()
                                        + " visRect=" + mVisRect.toShortString());
                    } else {
                        mTempRect.set(rectangle);
                        if (DEBUG_INPUT_RESIZE) Log.v(mTag,
                                "Request scroll to rect: "
                                        + mTempRect.toShortString()
                                        + " visRect=" + mVisRect.toShortString());
                    }
                    // 此处mTempRect表示需要滚动到的位置，一般有两种来源：
                    // 第一种来源即焦点视图的可绘制区域，另一种来源即第一入参
                    if (mTempRect.intersect(mVisRect)) {
                        if (DEBUG_INPUT_RESIZE) Log.v(mTag,
                                "Focus window visible rect: "
                                        + mTempRect.toShortString());
                        if (mTempRect.height() >
                                (mView.getHeight() - vi.top - vi.bottom)) {
                            // If the focus simply is not going to fit, then
                            // best is probably just to leave things as-is.
                            // 如果焦点不能简单的适配，即滚动到的区域的高度大于了decor的高度，则最好直接离开
                            if (DEBUG_INPUT_RESIZE) Log.v(mTag,
                                    "Too tall; leaving scrollY=" + scrollY);
                        }
                        // Next, check whether top or bottom is covered based on the non-scrolled
                        // position, and calculate new scrollY (or set it to 0).
                        // We can't keep using mScrollY here. For example mScrollY is non-zero
                        // due to IME, then IME goes away. The current value of mScrollY leaves top
                        // and bottom both visible, but we still need to scroll it back to 0.
                        // 接下来，检测滚动目标区域的顶部和底部是否是结余非滚动位置覆盖的，
                        // 并且计算新的滚动Y坐标
                        // 我们不会在此处持续使用全局的滚动Y坐标（mScrollY）。
                        // 例如因为输入法窗口的原因，而引起的mScrollY值非零，然后输入法窗口消失了。
                        // 当前的滚动值如果能够使得顶部和底部都可见，那么我们依然需要将其滚动到0
                        else if (mTempRect.top < vi.top) {
                            scrollY = mTempRect.top - vi.top;
                            if (DEBUG_INPUT_RESIZE) Log.v(mTag,
                                    "Top covered; scrollY=" + scrollY);
                        } else if (mTempRect.bottom > (mView.getHeight() - vi.bottom)) {
                            scrollY = mTempRect.bottom - (mView.getHeight() - vi.bottom);
                            if (DEBUG_INPUT_RESIZE) Log.v(mTag,
                                    "Bottom covered; scrollY=" + scrollY);
                        } else {
                            scrollY = 0;
                        }
                        handled = true;
                    }
                }
            }
        }

        // 开始滚动
        if (scrollY != mScrollY) {
            if (DEBUG_INPUT_RESIZE) Log.v(mTag, "Pan scroll changed: old="
                    + mScrollY + " , new=" + scrollY);
            if (!immediate) {
                if (mScroller == null) {
                    mScroller = new Scroller(mView.getContext());
                }
                mScroller.startScroll(0, mScrollY, 0, scrollY - mScrollY);
            } else if (mScroller != null) {
                mScroller.abortAnimation();
            }
            mScrollY = scrollY;
        }

        return handled;
    }

    /**
     * @hide
     */
    public View getAccessibilityFocusedHost() {
        return mAccessibilityFocusedHost;
    }

    /**
     * @hide
     */
    public AccessibilityNodeInfo getAccessibilityFocusedVirtualView() {
        return mAccessibilityFocusedVirtualView;
    }

    void setAccessibilityFocus(View view, AccessibilityNodeInfo node) {
        // If we have a virtual view with accessibility focus we need
        // to clear the focus and invalidate the virtual view bounds.
        if (mAccessibilityFocusedVirtualView != null) {

            AccessibilityNodeInfo focusNode = mAccessibilityFocusedVirtualView;
            View focusHost = mAccessibilityFocusedHost;

            // Wipe the state of the current accessibility focus since
            // the call into the provider to clear accessibility focus
            // will fire an accessibility event which will end up calling
            // this method and we want to have clean state when this
            // invocation happens.
            mAccessibilityFocusedHost = null;
            mAccessibilityFocusedVirtualView = null;

            // Clear accessibility focus on the host after clearing state since
            // this method may be reentrant.
            focusHost.clearAccessibilityFocusNoCallbacks(
                    AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);

            AccessibilityNodeProvider provider = focusHost.getAccessibilityNodeProvider();
            if (provider != null) {
                // Invalidate the area of the cleared accessibility focus.
                focusNode.getBoundsInParent(mTempRect);
                focusHost.invalidate(mTempRect);
                // Clear accessibility focus in the virtual node.
                final int virtualNodeId = AccessibilityNodeInfo.getVirtualDescendantId(
                        focusNode.getSourceNodeId());
                provider.performAction(virtualNodeId,
                        AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null);
            }
            focusNode.recycle();
        }
        if ((mAccessibilityFocusedHost != null) && (mAccessibilityFocusedHost != view)) {
            // Clear accessibility focus in the view.
            mAccessibilityFocusedHost.clearAccessibilityFocusNoCallbacks(
                    AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
        }

        // Set the new focus host and node.
        mAccessibilityFocusedHost = view;
        mAccessibilityFocusedVirtualView = node;

        if (mAttachInfo.mHardwareRenderer != null) {
            mAttachInfo.mHardwareRenderer.invalidateRoot();
        }
    }

    @Override
    public void requestChildFocus(View child, View focused) {
        if (DEBUG_INPUT_RESIZE) {
            Log.v(mTag, "Request child focus: focus now " + focused);
        }
        checkThread();
        scheduleTraversals();
    }

    @Override
    public void clearChildFocus(View child) {
        if (DEBUG_INPUT_RESIZE) {
            Log.v(mTag, "Clearing child focus");
        }
        checkThread();
        scheduleTraversals();
    }

    @Override
    public ViewParent getParentForAccessibility() {
        return null;
    }

    @Override
    public void focusableViewAvailable(View v) {
        checkThread();
        if (mView != null) {
            if (!mView.hasFocus()) {
                v.requestFocus();
            } else {
                // the one case where will transfer focus away from the current one
                // is if the current view is a view group that prefers to give focus
                // to its children first AND the view is a descendant of it.
                // 对于当前焦点将发生改变的这种情况而言，如果当前焦点视图是一个viewgroup，
                // 则最好优先将焦点转移给它的子视图
                View focused = mView.findFocus();
                if (focused instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) focused;
                    if (group.getDescendantFocusability() == ViewGroup.FOCUS_AFTER_DESCENDANTS
                            && isViewDescendantOf(v, focused)) {
                        v.requestFocus();
                    }
                }
            }
        }
    }

    @Override
    public void recomputeViewAttributes(View child) {
        checkThread();
        if (mView == child) {
            mAttachInfo.mRecomputeGlobalAttributes = true;
            if (!mWillDrawSoon) {
                scheduleTraversals();
            }
        }
    }

    /**
     * 从窗口上剥离视图
     * 1、首先调用相关的监听器；
     * 2、将decor view、attachinfo清空；
     * 3、释放surface对象；
     * 4、解除输入事件接受者；
     * 5、通知WMS移除自己；
     * 6、注销显示屏幕监听器；
     * 7、停止遍历。
     */
    void dispatchDetachedFromWindow() {
        if (mView != null && mView.mAttachInfo != null) {
            mAttachInfo.mTreeObserver.dispatchOnWindowAttachedChange(false);
            mView.dispatchDetachedFromWindow();
        }

        mAccessibilityInteractionConnectionManager.ensureNoConnection();
        mAccessibilityManager.removeAccessibilityStateChangeListener(
                mAccessibilityInteractionConnectionManager);
        mAccessibilityManager.removeHighTextContrastStateChangeListener(
                mHighContrastTextManager);
        removeSendWindowContentChangedCallback();

        destroyHardwareRenderer();

        setAccessibilityFocus(null, null);

        mView.assignParent(null);
        mView = null;
        mAttachInfo.mRootView = null;

        mSurface.release();

        if (mInputQueueCallback != null && mInputQueue != null) {
            mInputQueueCallback.onInputQueueDestroyed(mInputQueue);
            mInputQueue.dispose();
            mInputQueueCallback = null;
            mInputQueue = null;
        }
        if (mInputEventReceiver != null) {
            mInputEventReceiver.dispose();
            mInputEventReceiver = null;
        }
        try {
            mWindowSession.remove(mWindow);
        } catch (RemoteException e) {
        }

        // Dispose the input channel after removing the window so the Window Manager
        // doesn't interpret the input channel being closed as an abnormal termination.
        if (mInputChannel != null) {
            mInputChannel.dispose();
            mInputChannel = null;
        }

        mDisplayManager.unregisterDisplayListener(mDisplayListener);

        unscheduleTraversals();
    }

    void updateConfiguration(Configuration config, boolean force) {
        if (DEBUG_CONFIGURATION) Log.v(mTag,
                "Applying new config to window "
                        + mWindowAttributes.getTitle()
                        + ": " + config);

        CompatibilityInfo ci = mDisplay.getDisplayAdjustments().getCompatibilityInfo();
        if (!ci.equals(CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO)) {
            config = new Configuration(config);
            ci.applyToConfiguration(mNoncompatDensity, config);
        }

        synchronized (sConfigCallbacks) {
            for (int i = sConfigCallbacks.size() - 1; i >= 0; i--) {
                sConfigCallbacks.get(i).onConfigurationChanged(config);
            }
        }
        if (mView != null) {
            // At this point the resources have been updated to
            // have the most recent config, whatever that is.  Use
            // the one in them which may be newer.
            final Resources localResources = mView.getResources();
            config = localResources.getConfiguration();
            if (force || mLastConfiguration.diff(config) != 0) {
                // Update the display with new DisplayAdjustments.
                mDisplay = ResourcesManager.getInstance().getAdjustedDisplay(
                        mDisplay.getDisplayId(), localResources.getDisplayAdjustments());

                final int lastLayoutDirection = mLastConfiguration.getLayoutDirection();
                final int currentLayoutDirection = config.getLayoutDirection();
                mLastConfiguration.setTo(config);
                if (lastLayoutDirection != currentLayoutDirection &&
                        mViewLayoutDirectionInitial == View.LAYOUT_DIRECTION_INHERIT) {
                    mView.setLayoutDirection(currentLayoutDirection);
                }
                mView.dispatchConfigurationChanged(config);
            }
        }
    }

    /**
     * Return true if child is an ancestor of parent, (or equal to the parent).
     */
    public static boolean isViewDescendantOf(View child, View parent) {
        if (child == parent) {
            return true;
        }

        final ViewParent theParent = child.getParent();
        return (theParent instanceof ViewGroup) && isViewDescendantOf((View) theParent, parent);
    }

    private static void forceLayout(View view) {
        view.forceLayout();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            final int count = group.getChildCount();
            for (int i = 0; i < count; i++) {
                forceLayout(group.getChildAt(i));
            }
        }
    }

    // ViewRootImpl处理的主要消息
    // 无效
    private final static int MSG_INVALIDATE = 1;
    // 无效某个矩形
    private final static int MSG_INVALIDATE_RECT = 2;
    // 视图树死亡
    private final static int MSG_DIE = 3;
    // 重调大小
    private final static int MSG_RESIZED = 4;
    // 报告重调大小
    private final static int MSG_RESIZED_REPORT = 5;
    // 窗口焦点改变
    private final static int MSG_WINDOW_FOCUS_CHANGED = 6;
    // 分配输入事件
    private final static int MSG_DISPATCH_INPUT_EVENT = 7;
    // 分配APP可见性
    private final static int MSG_DISPATCH_APP_VISIBILITY = 8;
    // 获取新的surface对象
    private final static int MSG_DISPATCH_GET_NEW_SURFACE = 9;
    // 从输入法窗口分配键盘按键
    private final static int MSG_DISPATCH_KEY_FROM_IME = 11;
    // 检测焦点
    private final static int MSG_CHECK_FOCUS = 13;
    // 关闭系统对话框
    private final static int MSG_CLOSE_SYSTEM_DIALOGS = 14;
    // 分配拖拽事件
    private final static int MSG_DISPATCH_DRAG_EVENT = 15;
    // 分配拖拽位置事件
    private final static int MSG_DISPATCH_DRAG_LOCATION_EVENT = 16;
    // 分配系统UI的可见性
    private final static int MSG_DISPATCH_SYSTEM_UI_VISIBILITY = 17;
    // 更新配置
    private final static int MSG_UPDATE_CONFIGURATION = 18;
    // 处理输入事件
    private final static int MSG_PROCESS_INPUT_EVENTS = 19;
    // 清除辅助性焦点
    private final static int MSG_CLEAR_ACCESSIBILITY_FOCUS_HOST = 21;
    // 无效整个世界
    private final static int MSG_INVALIDATE_WORLD = 22;
    // 窗口移动
    private final static int MSG_WINDOW_MOVED = 23;
    // 合成输入事件
    private final static int MSG_SYNTHESIZE_INPUT_EVENT = 24;
    // 分配窗口显示
    private final static int MSG_DISPATCH_WINDOW_SHOWN = 25;
    // 请求按键快捷键
    private final static int MSG_REQUEST_KEYBOARD_SHORTCUTS = 26;
    // 更新指尖ICON
    private final static int MSG_UPDATE_POINTER_ICON = 27;

    final class ViewRootHandler extends Handler {
        @Override
        public String getMessageName(Message message) {
            switch (message.what) {
                case MSG_INVALIDATE:
                    return "MSG_INVALIDATE";
                case MSG_INVALIDATE_RECT:
                    return "MSG_INVALIDATE_RECT";
                case MSG_DIE:
                    return "MSG_DIE";
                case MSG_RESIZED:
                    return "MSG_RESIZED";
                case MSG_RESIZED_REPORT:
                    return "MSG_RESIZED_REPORT";
                case MSG_WINDOW_FOCUS_CHANGED:
                    return "MSG_WINDOW_FOCUS_CHANGED";
                case MSG_DISPATCH_INPUT_EVENT:
                    return "MSG_DISPATCH_INPUT_EVENT";
                case MSG_DISPATCH_APP_VISIBILITY:
                    return "MSG_DISPATCH_APP_VISIBILITY";
                case MSG_DISPATCH_GET_NEW_SURFACE:
                    return "MSG_DISPATCH_GET_NEW_SURFACE";
                case MSG_DISPATCH_KEY_FROM_IME:
                    return "MSG_DISPATCH_KEY_FROM_IME";
                case MSG_CHECK_FOCUS:
                    return "MSG_CHECK_FOCUS";
                case MSG_CLOSE_SYSTEM_DIALOGS:
                    return "MSG_CLOSE_SYSTEM_DIALOGS";
                case MSG_DISPATCH_DRAG_EVENT:
                    return "MSG_DISPATCH_DRAG_EVENT";
                case MSG_DISPATCH_DRAG_LOCATION_EVENT:
                    return "MSG_DISPATCH_DRAG_LOCATION_EVENT";
                case MSG_DISPATCH_SYSTEM_UI_VISIBILITY:
                    return "MSG_DISPATCH_SYSTEM_UI_VISIBILITY";
                case MSG_UPDATE_CONFIGURATION:
                    return "MSG_UPDATE_CONFIGURATION";
                case MSG_PROCESS_INPUT_EVENTS:
                    return "MSG_PROCESS_INPUT_EVENTS";
                case MSG_CLEAR_ACCESSIBILITY_FOCUS_HOST:
                    return "MSG_CLEAR_ACCESSIBILITY_FOCUS_HOST";
                case MSG_WINDOW_MOVED:
                    return "MSG_WINDOW_MOVED";
                case MSG_SYNTHESIZE_INPUT_EVENT:
                    return "MSG_SYNTHESIZE_INPUT_EVENT";
                case MSG_DISPATCH_WINDOW_SHOWN:
                    return "MSG_DISPATCH_WINDOW_SHOWN";
                case MSG_UPDATE_POINTER_ICON:
                    return "MSG_UPDATE_POINTER_ICON";
            }
            return super.getMessageName(message);
        }

        @Override
        public boolean sendMessageAtTime(Message msg, long uptimeMillis) {
            if (msg.what == MSG_REQUEST_KEYBOARD_SHORTCUTS && msg.obj == null) {
                // Debugging for b/27963013
                throw new NullPointerException(
                        "Attempted to call MSG_REQUEST_KEYBOARD_SHORTCUTS with null receiver:");
            }
            return super.sendMessageAtTime(msg, uptimeMillis);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_INVALIDATE:
                    ((View) msg.obj).invalidate();
                    break;
                case MSG_INVALIDATE_RECT:
                    final View.AttachInfo.InvalidateInfo info = (View.AttachInfo.InvalidateInfo) msg.obj;
                    info.target.invalidate(info.left, info.top, info.right, info.bottom);
                    info.recycle();
                    break;
                case MSG_PROCESS_INPUT_EVENTS:
                    mProcessInputEventsScheduled = false;
                    doProcessInputEvents();
                    break;
                case MSG_DISPATCH_APP_VISIBILITY:
                    handleAppVisibility(msg.arg1 != 0);
                    break;
                case MSG_DISPATCH_GET_NEW_SURFACE:
                    handleGetNewSurface();
                    break;
                case MSG_RESIZED: {
                    // Recycled in the fall through...
                    SomeArgs args = (SomeArgs) msg.obj;
                    if (mWinFrame.equals(args.arg1)
                            && mPendingOverscanInsets.equals(args.arg5)
                            && mPendingContentInsets.equals(args.arg2)
                            && mPendingStableInsets.equals(args.arg6)
                            && mPendingVisibleInsets.equals(args.arg3)
                            && mPendingOutsets.equals(args.arg7)
                            && mPendingBackDropFrame.equals(args.arg8)
                            && args.arg4 == null
                            && args.argi1 == 0) {
                        break;
                    }
                } // fall through...
                case MSG_RESIZED_REPORT:
                    if (mAdded) {
                        SomeArgs args = (SomeArgs) msg.obj;

                        Configuration config = (Configuration) args.arg4;
                        if (config != null) {
                            updateConfiguration(config, false);
                        }

                        final boolean framesChanged = !mWinFrame.equals(args.arg1)
                                || !mPendingOverscanInsets.equals(args.arg5)
                                || !mPendingContentInsets.equals(args.arg2)
                                || !mPendingStableInsets.equals(args.arg6)
                                || !mPendingVisibleInsets.equals(args.arg3)
                                || !mPendingOutsets.equals(args.arg7);

                        mWinFrame.set((Rect) args.arg1);
                        mPendingOverscanInsets.set((Rect) args.arg5);
                        mPendingContentInsets.set((Rect) args.arg2);
                        mPendingStableInsets.set((Rect) args.arg6);
                        mPendingVisibleInsets.set((Rect) args.arg3);
                        mPendingOutsets.set((Rect) args.arg7);
                        mPendingBackDropFrame.set((Rect) args.arg8);
                        mForceNextWindowRelayout = args.argi1 != 0;
                        mPendingAlwaysConsumeNavBar = args.argi2 != 0;

                        args.recycle();

                        if (msg.what == MSG_RESIZED_REPORT) {
                            mReportNextDraw = true;
                        }

                        if (mView != null && framesChanged) {
                            forceLayout(mView);
                        }

                        requestLayout();
                    }
                    break;
                case MSG_WINDOW_MOVED:
                    if (mAdded) {
                        final int w = mWinFrame.width();
                        final int h = mWinFrame.height();
                        final int l = msg.arg1;
                        final int t = msg.arg2;
                        mWinFrame.left = l;
                        mWinFrame.right = l + w;
                        mWinFrame.top = t;
                        mWinFrame.bottom = t + h;

                        mPendingBackDropFrame.set(mWinFrame);

                        // Suppress layouts during resizing - a correct layout will happen when resizing
                        // is done, and this just increases system load.
                        boolean isDockedDivider = mWindowAttributes.type == TYPE_DOCK_DIVIDER;
                        boolean suppress = (mDragResizing && mResizeMode == RESIZE_MODE_DOCKED_DIVIDER)
                                || isDockedDivider;
                        if (!suppress) {
                            if (mView != null) {
                                forceLayout(mView);
                            }
                            requestLayout();
                        } else {
                            maybeHandleWindowMove(mWinFrame);
                        }
                    }
                    break;
                case MSG_WINDOW_FOCUS_CHANGED: {
                    if (mAdded) {
                        boolean hasWindowFocus = msg.arg1 != 0;
                        mAttachInfo.mHasWindowFocus = hasWindowFocus;

                        profileRendering(hasWindowFocus);

                        if (hasWindowFocus) {
                            boolean inTouchMode = msg.arg2 != 0;
                            ensureTouchModeLocally(inTouchMode);

                            if (mAttachInfo.mHardwareRenderer != null && mSurface.isValid()) {
                                mFullRedrawNeeded = true;
                                try {
                                    final WindowManager.LayoutParams lp = mWindowAttributes;
                                    final Rect surfaceInsets = lp != null ? lp.surfaceInsets : null;
                                    mAttachInfo.mHardwareRenderer.initializeIfNeeded(
                                            mWidth, mHeight, mAttachInfo, mSurface, surfaceInsets);
                                } catch (OutOfResourcesException e) {
                                    Log.e(mTag, "OutOfResourcesException locking surface", e);
                                    try {
                                        if (!mWindowSession.outOfMemory(mWindow)) {
                                            Slog.w(mTag, "No processes killed for memory; killing self");
                                            Process.killProcess(Process.myPid());
                                        }
                                    } catch (RemoteException ex) {
                                    }
                                    // Retry in a bit.
                                    sendMessageDelayed(obtainMessage(msg.what, msg.arg1, msg.arg2), 500);
                                    return;
                                }
                            }
                        }

                        mLastWasImTarget = WindowManager.LayoutParams
                                .mayUseInputMethod(mWindowAttributes.flags);

                        InputMethodManager imm = InputMethodManager.peekInstance();
                        if (imm != null && mLastWasImTarget && !isInLocalFocusMode()) {
                            imm.onPreWindowFocus(mView, hasWindowFocus);
                        }
                        if (mView != null) {
                            mAttachInfo.mKeyDispatchState.reset();
                            mView.dispatchWindowFocusChanged(hasWindowFocus);
                            mAttachInfo.mTreeObserver.dispatchOnWindowFocusChange(hasWindowFocus);
                        }

                        // Note: must be done after the focus change callbacks,
                        // so all of the view state is set up correctly.
                        if (hasWindowFocus) {
                            if (imm != null && mLastWasImTarget && !isInLocalFocusMode()) {
                                imm.onPostWindowFocus(mView, mView.findFocus(),
                                        mWindowAttributes.softInputMode,
                                        !mHasHadWindowFocus, mWindowAttributes.flags);
                            }
                            // Clear the forward bit.  We can just do this directly, since
                            // the window manager doesn't care about it.
                            mWindowAttributes.softInputMode &=
                                    ~WindowManager.LayoutParams.SOFT_INPUT_IS_FORWARD_NAVIGATION;
                            ((WindowManager.LayoutParams) mView.getLayoutParams())
                                    .softInputMode &=
                                    ~WindowManager.LayoutParams.SOFT_INPUT_IS_FORWARD_NAVIGATION;
                            mHasHadWindowFocus = true;
                        }
                    }
                }
                break;
                case MSG_DIE:
                    doDie();
                    break;
                case MSG_DISPATCH_INPUT_EVENT: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    InputEvent event = (InputEvent) args.arg1;
                    InputEventReceiver receiver = (InputEventReceiver) args.arg2;
                    enqueueInputEvent(event, receiver, 0, true);
                    args.recycle();
                }
                break;
                case MSG_SYNTHESIZE_INPUT_EVENT: {
                    InputEvent event = (InputEvent) msg.obj;
                    enqueueInputEvent(event, null, QueuedInputEvent.FLAG_UNHANDLED, true);
                }
                break;
                case MSG_DISPATCH_KEY_FROM_IME: {
                    if (LOCAL_LOGV) Log.v(
                            TAG, "Dispatching key "
                                    + msg.obj + " from IME to " + mView);
                    KeyEvent event = (KeyEvent) msg.obj;
                    if ((event.getFlags() & KeyEvent.FLAG_FROM_SYSTEM) != 0) {
                        // The IME is trying to say this event is from the
                        // system!  Bad bad bad!
                        //noinspection UnusedAssignment
                        event = KeyEvent.changeFlags(event, event.getFlags() &
                                ~KeyEvent.FLAG_FROM_SYSTEM);
                    }
                    enqueueInputEvent(event, null, QueuedInputEvent.FLAG_DELIVER_POST_IME, true);
                }
                break;
                case MSG_CHECK_FOCUS: {
                    InputMethodManager imm = InputMethodManager.peekInstance();
                    if (imm != null) {
                        imm.checkFocus();
                    }
                }
                break;
                case MSG_CLOSE_SYSTEM_DIALOGS: {
                    if (mView != null) {
                        mView.onCloseSystemDialogs((String) msg.obj);
                    }
                }
                break;
                case MSG_DISPATCH_DRAG_EVENT:
                case MSG_DISPATCH_DRAG_LOCATION_EVENT: {
                    DragEvent event = (DragEvent) msg.obj;
                    event.mLocalState = mLocalDragState;    // only present when this app called startDrag()
                    handleDragEvent(event);
                }
                break;
                case MSG_DISPATCH_SYSTEM_UI_VISIBILITY: {
                    handleDispatchSystemUiVisibilityChanged((SystemUiVisibilityInfo) msg.obj);
                }
                break;
                case MSG_UPDATE_CONFIGURATION: {
                    Configuration config = (Configuration) msg.obj;
                    if (config.isOtherSeqNewer(mLastConfiguration)) {
                        config = mLastConfiguration;
                    }
                    updateConfiguration(config, false);
                }
                break;
                case MSG_CLEAR_ACCESSIBILITY_FOCUS_HOST: {
                    setAccessibilityFocus(null, null);
                }
                break;
                case MSG_INVALIDATE_WORLD: {
                    if (mView != null) {
                        invalidateWorld(mView);
                    }
                }
                break;
                case MSG_DISPATCH_WINDOW_SHOWN: {
                    handleDispatchWindowShown();
                }
                break;
                case MSG_REQUEST_KEYBOARD_SHORTCUTS: {
                    final IResultReceiver receiver = (IResultReceiver) msg.obj;
                    final int deviceId = msg.arg1;
                    handleRequestKeyboardShortcuts(receiver, deviceId);
                }
                break;
                case MSG_UPDATE_POINTER_ICON: {
                    MotionEvent event = (MotionEvent) msg.obj;
                    resetPointerIcon(event);
                }
                break;
            }
        }
    }

    final ViewRootHandler mHandler = new ViewRootHandler();

    /**
     * Something in the current window tells us we need to change the touch mode.  For
     * example, we are not in touch mode, and the user touches the screen.
     * <p>
     * If the touch mode has changed, tell the window manager, and handle it locally.
     *
     * @param inTouchMode Whether we want to be in touch mode.
     * @return True if the touch mode changed and focus changed was changed as a result
     */
    boolean ensureTouchMode(boolean inTouchMode) {
        if (DBG) Log.d("touchmode", "ensureTouchMode(" + inTouchMode + "), current "
                + "touch mode is " + mAttachInfo.mInTouchMode);
        if (mAttachInfo.mInTouchMode == inTouchMode) return false;

        // tell the window manager
        try {
            mWindowSession.setInTouchMode(inTouchMode);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }

        // handle the change
        return ensureTouchModeLocally(inTouchMode);
    }

    /**
     * Ensure that the touch mode for this window is set, and if it is changing,
     * take the appropriate action.
     * 确保此窗口的触摸模式被设置了，并且如果改变了，采取合适的操作
     *
     * @param inTouchMode Whether we want to be in touch mode.
     *                    是否我们期望进入触摸模式之宗
     * @return True if the touch mode changed and focus changed was changed as a result
     * 如果触摸模式改变了并且，焦点改变是作为一个改变的结果，则返回true
     */
    private boolean ensureTouchModeLocally(boolean inTouchMode) {
        if (DBG) Log.d("touchmode", "ensureTouchModeLocally(" + inTouchMode + "), current "
                + "touch mode is " + mAttachInfo.mInTouchMode);

        if (mAttachInfo.mInTouchMode == inTouchMode) return false;

        mAttachInfo.mInTouchMode = inTouchMode;
        mAttachInfo.mTreeObserver.dispatchOnTouchModeChanged(inTouchMode);

        return (inTouchMode) ? enterTouchMode() : leaveTouchMode();
    }

    private boolean enterTouchMode() {
        if (mView != null && mView.hasFocus()) {
            // note: not relying on mFocusedView here because this could
            // be when the window is first being added, and mFocused isn't
            // set yet.
            // 注意不在此处依赖于mFocusedView，是因为当窗口第一次被添加的时候，mFocused还没有被设置是可能的
            final View focused = mView.findFocus();
            if (focused != null && !focused.isFocusableInTouchMode()) {
                final ViewGroup ancestorToTakeFocus = findAncestorToTakeFocusInTouchMode(focused);
                if (ancestorToTakeFocus != null) {
                    // there is an ancestor that wants focus after its
                    // descendants that is focusable in touch mode.. give it
                    // focus
                    // 存在一个只能在其所有子视图都不能获取焦点时，才能获取焦点的父视图，且
                    // 此父视图在触摸模式下能够获取焦点。
                    return ancestorToTakeFocus.requestFocus();
                } else {
                    // There's nothing to focus. Clear and propagate through the
                    // hierarchy, but don't attempt to place new focus.
                    focused.clearFocusInternal(null, true, false);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Find an ancestor of focused that wants focus after its descendants and is
     * focusable in touch mode.
     * 一个只能在其所有子视图都不能获取焦点时，才能获取焦点的父视图，且
     * 此父视图在触摸模式下能够获取焦点。
     *
     * @param focused The currently focused view.
     * @return An appropriate view, or null if no such view exists.
     */
    private static ViewGroup findAncestorToTakeFocusInTouchMode(View focused) {
        ViewParent parent = focused.getParent();
        while (parent instanceof ViewGroup) {
            final ViewGroup vgParent = (ViewGroup) parent;
            if (vgParent.getDescendantFocusability() == ViewGroup.FOCUS_AFTER_DESCENDANTS
                    && vgParent.isFocusableInTouchMode()) {
                return vgParent;
            }
            if (vgParent.isRootNamespace()) {
                return null;
            } else {
                parent = vgParent.getParent();
            }
        }
        return null;
    }

    private boolean leaveTouchMode() {
        if (mView != null) {
            if (mView.hasFocus()) {
                View focusedView = mView.findFocus();
                if (!(focusedView instanceof ViewGroup)) {
                    // some view has focus, let it keep it
                    return false;
                } else if (((ViewGroup) focusedView).getDescendantFocusability() !=
                        ViewGroup.FOCUS_AFTER_DESCENDANTS) {
                    // some view group has focus, and doesn't prefer its children
                    // over itself for focus, so let them keep it.
                    return false;
                }
            }

            // find the best view to give focus to in this brave new non-touch-mode
            // world
            final View focused = focusSearch(null, View.FOCUS_DOWN);
            if (focused != null) {
                return focused.requestFocus(View.FOCUS_DOWN);
            }
        }
        return false;
    }

    /**
     * Base class for implementing a stage in the chain of responsibility
     * for processing input events.
     * 基本的类，用以实现处理输入事件的响应链上的一个阶段（环节）
     * 妥妥的责任链模式
     * <p>
     * Events are delivered to the stage by the {@link #deliver} method.  The stage
     * then has the choice of finishing the event or forwarding it to the next stage.
     * 事件通过deliver方法，被传递到此阶段之中。
     * 然后事件阶段类有两个选择：处理掉这个输入事件，将这个输入事件传递给（响应链上）的下一个阶段。
     * </p>
     */
    abstract class InputStage {
        private final InputStage mNext;

        // 结果处理类型
        // 传递给下一个阶段处理
        protected static final int FORWARD = 0;
        // 事件已经被处理了
        protected static final int FINISH_HANDLED = 1;
        // 事件还未被处理
        protected static final int FINISH_NOT_HANDLED = 2;

        /**
         * Creates an input stage.
         *
         * @param next The next stage to which events should be forwarded.
         */
        public InputStage(InputStage next) {
            mNext = next;
        }

        /**
         * Delivers an event to be processed.
         * 传递一个需要处理的事件
         */
        public final void deliver(QueuedInputEvent q) {
            if ((q.mFlags & QueuedInputEvent.FLAG_FINISHED) != 0) {
                forward(q);
            } else if (shouldDropInputEvent(q)) {
                finish(q, false);
            } else {
                apply(q, onProcess(q));
            }
        }

        /**
         * Marks the the input event as finished then forwards it to the next stage.
         * 标记此输入事件为结束，并将它传递给喜爱一个阶段
         */
        protected void finish(QueuedInputEvent q, boolean handled) {
            q.mFlags |= QueuedInputEvent.FLAG_FINISHED;
            if (handled) {
                q.mFlags |= QueuedInputEvent.FLAG_FINISHED_HANDLED;
            }
            forward(q);
        }

        /**
         * Forwards the event to the next stage.
         * 将输入事件传递给下一个阶段
         */
        protected void forward(QueuedInputEvent q) {
            onDeliverToNext(q);
        }

        /**
         * Applies a result code from {@link #onProcess} to the specified event.
         * 提供一个来至于onPrecess方法的结果码给指定的事件
         */
        protected void apply(QueuedInputEvent q, int result) {
            if (result == FORWARD) {
                forward(q);
            } else if (result == FINISH_HANDLED) {
                finish(q, true);
            } else if (result == FINISH_NOT_HANDLED) {
                finish(q, false);
            } else {
                throw new IllegalArgumentException("Invalid result: " + result);
            }
        }

        /**
         * Called when an event is ready to be processed.
         *
         * @return A result code indicating how the event was handled.
         */
        protected int onProcess(QueuedInputEvent q) {
            return FORWARD;
        }

        /**
         * Called when an event is being delivered to the next stage.
         * 分配给下一个阶段
         */
        protected void onDeliverToNext(QueuedInputEvent q) {
            if (DEBUG_INPUT_STAGES) {
                Log.v(mTag, "Done with " + getClass().getSimpleName() + ". " + q);
            }
            if (mNext != null) {
                mNext.deliver(q);
            } else {
                finishInputEvent(q);
            }
        }

        /**
         * 是否应该丢弃输入事件
         * 如果decor view脱离了窗口；
         * 或者没有窗口焦点，且输入事件并非来自于一个可触摸的设备；
         * 或者此视图树已经停止了；
         * 或者此视图树处于不接受输入事件的模式之中，且输入事件并非来自于一个可按键的设备
         * 或者因为动画的原因而暂停了接收输入事件，且当前出入事件并非一个返回输入事件
         *
         * @param q
         * @return
         */
        protected boolean shouldDropInputEvent(QueuedInputEvent q) {
            if (mView == null || !mAdded) {
                Slog.w(mTag, "Dropping event due to root view being removed: " + q.mEvent);
                return true;
            } else if ((!mAttachInfo.mHasWindowFocus
                    && !q.mEvent.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) || mStopped
                    || (mIsAmbientMode && !q.mEvent.isFromSource(InputDevice.SOURCE_CLASS_BUTTON))
                    || (mPausedForTransition && !isBack(q.mEvent))) {
                // 如果没有窗口焦点，且输入事件并非来自于一个可触摸的设备；
                // 或者此视图树已经停止了；
                // 或者此视图树处于不接受输入事件的模式之中，且输入事件并非来自于一个可按键的设备
                // 或者因为动画的原因而暂停了接收输入事件，且当前出入事件并非一个返回输入事件，
                // 则走此分支
                // This is a focus event and the window doesn't currently have input focus or
                // has stopped. This could be an event that came back from the previous stage
                // but the window has lost focus or stopped in the meantime.
                // 是一个焦点事件，并且窗口当前没有输入焦点或者已经停止了
                // 这可能是一个从上一个阶段返回而来的事件，但是在此期间窗口失去了焦点或者已经停止了
                if (isTerminalInputEvent(q.mEvent)) {
                    // 是否是一终点事件，例如按键事件的释放动作，触摸事件的释放动作等
                    // Don't drop terminal input events, however mark them as canceled.
                    q.mEvent.cancel();
                    Slog.w(mTag, "Cancelling event due to no window focus: " + q.mEvent);
                    return false;
                }

                // Drop non-terminal input events.
                Slog.w(mTag, "Dropping event due to no window focus: " + q.mEvent);
                return true;
            }
            return false;
        }

        void dump(String prefix, PrintWriter writer) {
            if (mNext != null) {
                mNext.dump(prefix, writer);
            }
        }

        private boolean isBack(InputEvent event) {
            if (event instanceof KeyEvent) {
                return ((KeyEvent) event).getKeyCode() == KeyEvent.KEYCODE_BACK;
            } else {
                return false;
            }
        }
    }

    /**
     * Base class for implementing an input pipeline stage that supports
     * asynchronous and out-of-order processing of input events.
     * 实现一个输入管道阶段的基本类，此类支持异步和无序的输入事件
     * <p>
     * In addition to what a normal input stage can do, an asynchronous
     * input stage may also defer an input event that has been delivered to it
     * and finish or forward it later.
     * 除了普通输入事件处理阶段能够做的，此类还可以推迟一个传递给它的输入事件，
     * 即可以稍后完成或者处理输入事件
     * </p>
     */
    abstract class AsyncInputStage extends InputStage {
        private final String mTraceCounter;

        private QueuedInputEvent mQueueHead;
        private QueuedInputEvent mQueueTail;
        private int mQueueLength;

        protected static final int DEFER = 3;

        /**
         * Creates an asynchronous input stage.
         *
         * @param next         The next stage to which events should be forwarded.
         * @param traceCounter The name of a counter to record the size of
         *                     the queue of pending events.
         *                     记录即将处理输入事件队列长度的计数器的名字
         */
        public AsyncInputStage(InputStage next, String traceCounter) {
            super(next);
            mTraceCounter = traceCounter;
        }

        /**
         * Marks the event as deferred, which is to say that it will be handled
         * asynchronously.  The caller is responsible for calling {@link #forward}
         * or {@link #finish} later when it is done handling the event.
         * 标记此事件是延迟的，也就是说此事件将被异步处理。
         * 调用者将延后得到{@link #forward}方法和 {@link #finish}方法的响应
         */
        protected void defer(QueuedInputEvent q) {
            q.mFlags |= QueuedInputEvent.FLAG_DEFERRED;
            enqueue(q);
        }

        @Override
        protected void forward(QueuedInputEvent q) {
            // Clear the deferred flag.
            // 清除延迟标识
            q.mFlags &= ~QueuedInputEvent.FLAG_DEFERRED;

            // Fast path if the queue is empty.
            // 如果队列是空的，则直接传递给下一阶段
            QueuedInputEvent curr = mQueueHead;
            if (curr == null) {
                super.forward(q);
                return;
            }

            // Determine whether the event must be serialized behind any others
            // before it can be delivered to the next stage.  This is done because
            // deferred events might be handled out of order by the stage.
            // 在一个事件能够被传递给下一个阶段之前，需要决定此事件是否必须排列在其他输入事件的后面。
            // 这是因为延迟的事件可能在此阶段被无序的处理
            // 此输入事件的来源设备ID
            final int deviceId = q.mEvent.getDeviceId();
            QueuedInputEvent prev = null;
            // 是否阻塞，如果延迟事件队列之中存在，
            // 与当前事件的来源设备ID一致的输入事件，则当前事件需要阻塞
            boolean blocked = false;
            while (curr != null && curr != q) {
                if (!blocked && deviceId == curr.mEvent.getDeviceId()) {
                    blocked = true;
                }
                prev = curr;
                curr = curr.mNext;
            }

            // If the event is blocked, then leave it in the queue to be delivered later.
            // Note that the event might not yet be in the queue if it was not previously
            // deferred so we will enqueue it if needed.
            // 如果事件在阻塞的，则将它放入延迟队列之中，以延迟传递。
            // 注意，事件可能不在队列之中，如果它没有提前延迟，所以
            // 我们将入队它，如果需要
            if (blocked) {
                if (curr == null) {
                    enqueue(q);
                }
                return;
            }

            // The event is not blocked.  Deliver it immediately.
            // 如果事件不是阻塞的。则立即传递它
            // 如果它在队列之中，则在传递之前，先将它从延迟队列之中清除
            if (curr != null) {
                curr = curr.mNext;
                dequeue(q, prev);
            }
            super.forward(q);

            // Dequeuing this event may have unblocked successors.  Deliver them.
            // 出队的这个事件可能有非阻塞的继承者。传递它们
            while (curr != null) {
                if (deviceId == curr.mEvent.getDeviceId()) {
                    // 如果延迟事件队列中还存在与当前已传递的输入事件的设备ID一致
                    // 则走此分支
                    if ((curr.mFlags & QueuedInputEvent.FLAG_DEFERRED) != 0) {
                        // 如果此事件需要延迟，则什么都不做
                        break;
                    }
                    // 如果此事件不需要延迟，则跟着入参一起被传递给下一阶段
                    QueuedInputEvent next = curr.mNext;
                    dequeue(curr, prev);
                    super.forward(curr);
                    curr = next;
                } else {
                    prev = curr;
                    curr = curr.mNext;
                }
            }
        }

        @Override
        protected void apply(QueuedInputEvent q, int result) {
            if (result == DEFER) {
                defer(q);
            } else {
                super.apply(q, result);
            }
        }

        private void enqueue(QueuedInputEvent q) {
            if (mQueueTail == null) {
                mQueueHead = q;
                mQueueTail = q;
            } else {
                mQueueTail.mNext = q;
                mQueueTail = q;
            }

            mQueueLength += 1;
            Trace.traceCounter(Trace.TRACE_TAG_INPUT, mTraceCounter, mQueueLength);
        }

        private void dequeue(QueuedInputEvent q, QueuedInputEvent prev) {
            if (prev == null) {
                mQueueHead = q.mNext;
            } else {
                prev.mNext = q.mNext;
            }
            if (mQueueTail == q) {
                mQueueTail = prev;
            }
            q.mNext = null;

            mQueueLength -= 1;
            Trace.traceCounter(Trace.TRACE_TAG_INPUT, mTraceCounter, mQueueLength);
        }

        @Override
        void dump(String prefix, PrintWriter writer) {
            writer.print(prefix);
            writer.print(getClass().getName());
            writer.print(": mQueueLength=");
            writer.println(mQueueLength);

            super.dump(prefix, writer);
        }
    }

    /**
     * Delivers pre-ime input events to a native activity.
     * Does not support pointer events.
     * 发送输入法之前的事件给一个本地的activity
     * 不支持触摸事件
     */
    final class NativePreImeInputStage extends AsyncInputStage
            implements InputQueue.FinishedInputEventCallback {
        public NativePreImeInputStage(InputStage next, String traceCounter) {
            super(next, traceCounter);
        }

        @Override
        protected int onProcess(QueuedInputEvent q) {
            if (mInputQueue != null && q.mEvent instanceof KeyEvent) {
                mInputQueue.sendInputEvent(q.mEvent, q, true, this);
                return DEFER;
            }
            return FORWARD;
        }

        @Override
        public void onFinishedInputEvent(Object token, boolean handled) {
            QueuedInputEvent q = (QueuedInputEvent) token;
            if (handled) {
                finish(q, true);
                return;
            }
            forward(q);
        }
    }

    /**
     * Delivers pre-ime input events to the view hierarchy.
     * Does not support pointer events.
     * 发送输入法之前的输入事件到视图树
     * 不支持触摸事件
     */
    final class ViewPreImeInputStage extends InputStage {
        public ViewPreImeInputStage(InputStage next) {
            super(next);
        }

        @Override
        protected int onProcess(QueuedInputEvent q) {
            if (q.mEvent instanceof KeyEvent) {
                return processKeyEvent(q);
            }
            return FORWARD;
        }

        private int processKeyEvent(QueuedInputEvent q) {
            final KeyEvent event = (KeyEvent) q.mEvent;
            if (mView.dispatchKeyEventPreIme(event)) {
                return FINISH_HANDLED;
            }
            return FORWARD;
        }
    }

    /**
     * Delivers input events to the ime.
     * Does not support pointer events.
     * 发送输入事件到输入法窗口
     * 不支持触摸事件
     */
    final class ImeInputStage extends AsyncInputStage
            implements InputMethodManager.FinishedInputEventCallback {
        public ImeInputStage(InputStage next, String traceCounter) {
            super(next, traceCounter);
        }

        @Override
        protected int onProcess(QueuedInputEvent q) {
            if (mLastWasImTarget && !isInLocalFocusMode()) {
                // 是输入法窗口的目标窗口，且此窗口并非本地焦点模式
                InputMethodManager imm = InputMethodManager.peekInstance();
                if (imm != null) {
                    final InputEvent event = q.mEvent;
                    if (DEBUG_IMF) Log.v(mTag, "Sending input event to IME: " + event);
                    int result = imm.dispatchInputEvent(event, q, this, mHandler);
                    if (result == InputMethodManager.DISPATCH_HANDLED) {
                        return FINISH_HANDLED;
                    } else if (result == InputMethodManager.DISPATCH_NOT_HANDLED) {
                        // The IME could not handle it, so skip along to the next InputStage
                        // 输入法窗口不能处理此事件，则跳至下一阶段
                        return FORWARD;
                    } else {
                        return DEFER; // callback will be invoked later
                    }
                }
            }
            return FORWARD;
        }

        @Override
        public void onFinishedInputEvent(Object token, boolean handled) {
            QueuedInputEvent q = (QueuedInputEvent) token;
            if (handled) {
                finish(q, true);
                return;
            }
            forward(q);
        }
    }

    /**
     * Performs early processing of post-ime input events.
     * 输入法后的第一个处理消息的阶段
     */
    final class EarlyPostImeInputStage extends InputStage {
        public EarlyPostImeInputStage(InputStage next) {
            super(next);
        }

        @Override
        protected int onProcess(QueuedInputEvent q) {
            if (q.mEvent instanceof KeyEvent) {
                return processKeyEvent(q);
            } else {
                final int source = q.mEvent.getSource();
                if ((source & InputDevice.SOURCE_CLASS_POINTER) != 0) {
                    return processPointerEvent(q);
                }
            }
            return FORWARD;
        }

        private int processKeyEvent(QueuedInputEvent q) {
            final KeyEvent event = (KeyEvent) q.mEvent;

            // If the key's purpose is to exit touch mode then we consume it
            // and consider it handled.
            // 如果按键的目的是退出触摸模式，我们消耗它，并将其表示为已完成处理
            if (checkForLeavingTouchModeAndConsume(event)) {
                return FINISH_HANDLED;
            }

            // Make sure the fallback event policy sees all keys that will be
            // delivered to the view hierarchy.
            // 确保撤退事件策略能够看见所有传递给视图树的按键事件
            mFallbackEventHandler.preDispatchKeyEvent(event);
            return FORWARD;
        }

        private int processPointerEvent(QueuedInputEvent q) {
            final MotionEvent event = (MotionEvent) q.mEvent;

            // Translate the pointer event for compatibility, if needed.
            // 将触摸事件的屏幕坐标系转换为应用窗口对应的坐标系
            if (mTranslator != null) {
                mTranslator.translateEventInScreenToAppWindow(event);
            }

            // Enter touch mode on down or scroll.
            // 进入触摸模式
            final int action = event.getAction();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_SCROLL) {
                ensureTouchMode(true);
            }

            // Offset the scroll position.
            // 将触摸事件的坐标纳入当前滚动的位置
            if (mCurScrollY != 0) {
                event.offsetLocation(0, mCurScrollY);
            }

            // Remember the touch position for possible drag-initiation.
            if (event.isTouchEvent()) {
                mLastTouchPoint.x = event.getRawX();
                mLastTouchPoint.y = event.getRawY();
                mLastTouchSource = event.getSource();
            }
            return FORWARD;
        }
    }

    /**
     * Delivers post-ime input events to a native activity.
     * 输入法后的第二个处理阶段，将其传递给本地的activity处理
     */
    final class NativePostImeInputStage extends AsyncInputStage
            implements InputQueue.FinishedInputEventCallback {
        public NativePostImeInputStage(InputStage next, String traceCounter) {
            super(next, traceCounter);
        }

        @Override
        protected int onProcess(QueuedInputEvent q) {
            if (mInputQueue != null) {
                mInputQueue.sendInputEvent(q.mEvent, q, false, this);
                return DEFER;
            }
            return FORWARD;
        }

        @Override
        public void onFinishedInputEvent(Object token, boolean handled) {
            QueuedInputEvent q = (QueuedInputEvent) token;
            if (handled) {
                finish(q, true);
                return;
            }
            forward(q);
        }
    }

    /**
     * Delivers post-ime input events to the view hierarchy.
     * 输入法后的第三个个处理阶段，将其传递给视图树处理
     */
    final class ViewPostImeInputStage extends InputStage {
        public ViewPostImeInputStage(InputStage next) {
            super(next);
        }

        @Override
        protected int onProcess(QueuedInputEvent q) {
            if (q.mEvent instanceof KeyEvent) {
                // 处理按键
                return processKeyEvent(q);
            } else {
                final int source = q.mEvent.getSource();
                if ((source & InputDevice.SOURCE_CLASS_POINTER) != 0) {
                    // 处理指尖事件？
                    return processPointerEvent(q);
                } else if ((source & InputDevice.SOURCE_CLASS_TRACKBALL) != 0) {
                    // 处理滚动球事件
                    return processTrackballEvent(q);
                } else {
                    // 处理一般触摸事件
                    return processGenericMotionEvent(q);
                }
            }
        }

        @Override
        protected void onDeliverToNext(QueuedInputEvent q) {
            if (mUnbufferedInputDispatch
                    && q.mEvent instanceof MotionEvent
                    && ((MotionEvent) q.mEvent).isTouchEvent()
                    && isTerminalInputEvent(q.mEvent)) {
                // 当前视图树进行无缓冲的输入事件分配
                // 且输入事件是触摸事件
                // 且是一个终点事件
                // 开始接受缓冲的输入事件调度
                mUnbufferedInputDispatch = false;
                // 通过调度消耗批量的输入事件
                scheduleConsumeBatchedInput();
            }
            super.onDeliverToNext(q);
        }

        private int processKeyEvent(QueuedInputEvent q) {
            final KeyEvent event = (KeyEvent) q.mEvent;

            // Deliver the key to the view hierarchy.
            // 将按键事件分配给视图树
            if (mView.dispatchKeyEvent(event)) {
                return FINISH_HANDLED;
            }

            if (shouldDropInputEvent(q)) {
                return FINISH_NOT_HANDLED;
            }

            // If the Control modifier is held, try to interpret the key as a shortcut.
            //如果控制修饰符被持有，则尝试将此按键事件作为一个快结键来解释
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && event.isCtrlPressed()
                    && event.getRepeatCount() == 0
                    && !KeyEvent.isModifierKey(event.getKeyCode())) {
                if (mView.dispatchKeyShortcutEvent(event)) {
                    return FINISH_HANDLED;
                }
                if (shouldDropInputEvent(q)) {
                    return FINISH_NOT_HANDLED;
                }
            }

            // Apply the fallback event policy.
            if (mFallbackEventHandler.dispatchKeyEvent(event)) {
                return FINISH_HANDLED;
            }
            if (shouldDropInputEvent(q)) {
                return FINISH_NOT_HANDLED;
            }

            // Handle automatic focus changes.
            // 处理自动焦点改变
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                int direction = 0;
                switch (event.getKeyCode()) {
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        if (event.hasNoModifiers()) {
                            direction = View.FOCUS_LEFT;
                        }
                        break;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        if (event.hasNoModifiers()) {
                            direction = View.FOCUS_RIGHT;
                        }
                        break;
                    case KeyEvent.KEYCODE_DPAD_UP:
                        if (event.hasNoModifiers()) {
                            direction = View.FOCUS_UP;
                        }
                        break;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        if (event.hasNoModifiers()) {
                            direction = View.FOCUS_DOWN;
                        }
                        break;
                    case KeyEvent.KEYCODE_TAB:
                        if (event.hasNoModifiers()) {
                            direction = View.FOCUS_FORWARD;
                        } else if (event.hasModifiers(KeyEvent.META_SHIFT_ON)) {
                            direction = View.FOCUS_BACKWARD;
                        }
                        break;
                }
                if (direction != 0) {
                    View focused = mView.findFocus();
                    if (focused != null) {
                        View v = focused.focusSearch(direction);
                        if (v != null && v != focused) {
                            // do the math the get the interesting rect
                            // of previous focused into the coord system of
                            // newly focused view
                            focused.getFocusedRect(mTempRect);
                            if (mView instanceof ViewGroup) {
                                ((ViewGroup) mView).offsetDescendantRectToMyCoords(
                                        focused, mTempRect);
                                ((ViewGroup) mView).offsetRectIntoDescendantCoords(
                                        v, mTempRect);
                            }
                            if (v.requestFocus(direction, mTempRect)) {
                                playSoundEffect(SoundEffectConstants
                                        .getContantForFocusDirection(direction));
                                return FINISH_HANDLED;
                            }
                        }

                        // Give the focused view a last chance to handle the dpad key.
                        if (mView.dispatchUnhandledMove(focused, direction)) {
                            return FINISH_HANDLED;
                        }
                    } else {
                        // find the best view to give focus to in this non-touch-mode with no-focus
                        View v = focusSearch(null, direction);
                        if (v != null && v.requestFocus(direction)) {
                            return FINISH_HANDLED;
                        }
                    }
                }
            }
            return FORWARD;
        }

        // 处理触摸事件
        private int processPointerEvent(QueuedInputEvent q) {
            final MotionEvent event = (MotionEvent) q.mEvent;

            mAttachInfo.mUnbufferedDispatchRequested = false;
            final View eventTarget =
                    (event.isFromSource(InputDevice.SOURCE_MOUSE) && mCapturingView != null) ?
                            mCapturingView : mView;
            mAttachInfo.mHandlingPointerEvent = true;
            boolean handled = eventTarget.dispatchPointerEvent(event);
            // 鼠标事件才会真正的执行此方法
            maybeUpdatePointerIcon(event);
            mAttachInfo.mHandlingPointerEvent = false;
            if (mAttachInfo.mUnbufferedDispatchRequested && !mUnbufferedInputDispatch) {
                mUnbufferedInputDispatch = true;
                if (mConsumeBatchedInputScheduled) {
                    scheduleConsumeBatchedInputImmediately();
                }
            }
            return handled ? FINISH_HANDLED : FORWARD;
        }

        private void maybeUpdatePointerIcon(MotionEvent event) {
            if (event.getPointerCount() == 1 && event.isFromSource(InputDevice.SOURCE_MOUSE)) {
                if (event.getActionMasked() == MotionEvent.ACTION_HOVER_ENTER
                        || event.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT) {
                    // Other apps or the window manager may change the icon type outside of
                    // this app, therefore the icon type has to be reset on enter/exit event.
                    mPointerIconType = PointerIcon.TYPE_NOT_SPECIFIED;
                }

                if (event.getActionMasked() != MotionEvent.ACTION_HOVER_EXIT) {
                    if (!updatePointerIcon(event) &&
                            event.getActionMasked() == MotionEvent.ACTION_HOVER_MOVE) {
                        mPointerIconType = PointerIcon.TYPE_NOT_SPECIFIED;
                    }
                }
            }
        }

        private int processTrackballEvent(QueuedInputEvent q) {
            final MotionEvent event = (MotionEvent) q.mEvent;

            if (mView.dispatchTrackballEvent(event)) {
                return FINISH_HANDLED;
            }
            return FORWARD;
        }

        private int processGenericMotionEvent(QueuedInputEvent q) {
            final MotionEvent event = (MotionEvent) q.mEvent;

            // Deliver the event to the view.
            if (mView.dispatchGenericMotionEvent(event)) {
                return FINISH_HANDLED;
            }
            return FORWARD;
        }
    }

    private void resetPointerIcon(MotionEvent event) {
        mPointerIconType = PointerIcon.TYPE_NOT_SPECIFIED;
        updatePointerIcon(event);
    }

    private boolean updatePointerIcon(MotionEvent event) {
        final int pointerIndex = 0;
        final float x = event.getX(pointerIndex);
        final float y = event.getY(pointerIndex);
        if (mView == null) {
            // E.g. click outside a popup to dismiss it
            Slog.d(mTag, "updatePointerIcon called after view was removed");
            return false;
        }
        if (x < 0 || x >= mView.getWidth() || y < 0 || y >= mView.getHeight()) {
            // E.g. when moving window divider with mouse
            Slog.d(mTag, "updatePointerIcon called with position out of bounds");
            return false;
        }
        final PointerIcon pointerIcon = mView.onResolvePointerIcon(event, pointerIndex);
        final int pointerType = (pointerIcon != null) ?
                pointerIcon.getType() : PointerIcon.TYPE_DEFAULT;

        if (mPointerIconType != pointerType) {
            mPointerIconType = pointerType;
            if (mPointerIconType != PointerIcon.TYPE_CUSTOM) {
                mCustomPointerIcon = null;
                InputManager.getInstance().setPointerIconType(pointerType);
                return true;
            }
        }
        if (mPointerIconType == PointerIcon.TYPE_CUSTOM &&
                !pointerIcon.equals(mCustomPointerIcon)) {
            mCustomPointerIcon = pointerIcon;
            InputManager.getInstance().setCustomPointerIcon(mCustomPointerIcon);
        }
        return true;
    }

    /**
     * Performs synthesis of new input events from unhandled input events.
     * 执行从未处理的输入事件中合成的新的输入事件
     */
    final class SyntheticInputStage extends InputStage {
        private final SyntheticTrackballHandler mTrackball = new SyntheticTrackballHandler();
        private final SyntheticJoystickHandler mJoystick = new SyntheticJoystickHandler();
        private final SyntheticTouchNavigationHandler mTouchNavigation =
                new SyntheticTouchNavigationHandler();
        private final SyntheticKeyboardHandler mKeyboard = new SyntheticKeyboardHandler();

        public SyntheticInputStage() {
            super(null);
        }

        @Override
        protected int onProcess(QueuedInputEvent q) {
            q.mFlags |= QueuedInputEvent.FLAG_RESYNTHESIZED;
            if (q.mEvent instanceof MotionEvent) {
                final MotionEvent event = (MotionEvent) q.mEvent;
                final int source = event.getSource();
                if ((source & InputDevice.SOURCE_CLASS_TRACKBALL) != 0) {
                    mTrackball.process(event);
                    return FINISH_HANDLED;
                } else if ((source & InputDevice.SOURCE_CLASS_JOYSTICK) != 0) {
                    mJoystick.process(event);
                    return FINISH_HANDLED;
                } else if ((source & InputDevice.SOURCE_TOUCH_NAVIGATION)
                        == InputDevice.SOURCE_TOUCH_NAVIGATION) {
                    mTouchNavigation.process(event);
                    return FINISH_HANDLED;
                }
            } else if ((q.mFlags & QueuedInputEvent.FLAG_UNHANDLED) != 0) {
                mKeyboard.process((KeyEvent) q.mEvent);
                return FINISH_HANDLED;
            }

            return FORWARD;
        }

        @Override
        protected void onDeliverToNext(QueuedInputEvent q) {
            if ((q.mFlags & QueuedInputEvent.FLAG_RESYNTHESIZED) == 0) {
                // Cancel related synthetic events if any prior stage has handled the event.
                if (q.mEvent instanceof MotionEvent) {
                    final MotionEvent event = (MotionEvent) q.mEvent;
                    final int source = event.getSource();
                    if ((source & InputDevice.SOURCE_CLASS_TRACKBALL) != 0) {
                        mTrackball.cancel(event);
                    } else if ((source & InputDevice.SOURCE_CLASS_JOYSTICK) != 0) {
                        mJoystick.cancel(event);
                    } else if ((source & InputDevice.SOURCE_TOUCH_NAVIGATION)
                            == InputDevice.SOURCE_TOUCH_NAVIGATION) {
                        mTouchNavigation.cancel(event);
                    }
                }
            }
            super.onDeliverToNext(q);
        }
    }

    /**
     * Creates dpad events from unhandled trackball movements.
     */
    final class SyntheticTrackballHandler {
        private final TrackballAxis mX = new TrackballAxis();
        private final TrackballAxis mY = new TrackballAxis();
        private long mLastTime;

        public void process(MotionEvent event) {
            // Translate the trackball event into DPAD keys and try to deliver those.
            long curTime = SystemClock.uptimeMillis();
            if ((mLastTime + MAX_TRACKBALL_DELAY) < curTime) {
                // It has been too long since the last movement,
                // so restart at the beginning.
                mX.reset(0);
                mY.reset(0);
                mLastTime = curTime;
            }

            final int action = event.getAction();
            final int metaState = event.getMetaState();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    mX.reset(2);
                    mY.reset(2);
                    enqueueInputEvent(new KeyEvent(curTime, curTime,
                            KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0, metaState,
                            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FALLBACK,
                            InputDevice.SOURCE_KEYBOARD));
                    break;
                case MotionEvent.ACTION_UP:
                    mX.reset(2);
                    mY.reset(2);
                    enqueueInputEvent(new KeyEvent(curTime, curTime,
                            KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0, metaState,
                            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FALLBACK,
                            InputDevice.SOURCE_KEYBOARD));
                    break;
            }

            if (DEBUG_TRACKBALL) Log.v(mTag, "TB X=" + mX.position + " step="
                    + mX.step + " dir=" + mX.dir + " acc=" + mX.acceleration
                    + " move=" + event.getX()
                    + " / Y=" + mY.position + " step="
                    + mY.step + " dir=" + mY.dir + " acc=" + mY.acceleration
                    + " move=" + event.getY());
            final float xOff = mX.collect(event.getX(), event.getEventTime(), "X");
            final float yOff = mY.collect(event.getY(), event.getEventTime(), "Y");

            // Generate DPAD events based on the trackball movement.
            // We pick the axis that has moved the most as the direction of
            // the DPAD.  When we generate DPAD events for one axis, then the
            // other axis is reset -- we don't want to perform DPAD jumps due
            // to slight movements in the trackball when making major movements
            // along the other axis.
            int keycode = 0;
            int movement = 0;
            float accel = 1;
            if (xOff > yOff) {
                movement = mX.generate();
                if (movement != 0) {
                    keycode = movement > 0 ? KeyEvent.KEYCODE_DPAD_RIGHT
                            : KeyEvent.KEYCODE_DPAD_LEFT;
                    accel = mX.acceleration;
                    mY.reset(2);
                }
            } else if (yOff > 0) {
                movement = mY.generate();
                if (movement != 0) {
                    keycode = movement > 0 ? KeyEvent.KEYCODE_DPAD_DOWN
                            : KeyEvent.KEYCODE_DPAD_UP;
                    accel = mY.acceleration;
                    mX.reset(2);
                }
            }

            if (keycode != 0) {
                if (movement < 0) movement = -movement;
                int accelMovement = (int) (movement * accel);
                if (DEBUG_TRACKBALL) Log.v(mTag, "Move: movement=" + movement
                        + " accelMovement=" + accelMovement
                        + " accel=" + accel);
                if (accelMovement > movement) {
                    if (DEBUG_TRACKBALL) Log.v(mTag, "Delivering fake DPAD: "
                            + keycode);
                    movement--;
                    int repeatCount = accelMovement - movement;
                    enqueueInputEvent(new KeyEvent(curTime, curTime,
                            KeyEvent.ACTION_MULTIPLE, keycode, repeatCount, metaState,
                            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FALLBACK,
                            InputDevice.SOURCE_KEYBOARD));
                }
                while (movement > 0) {
                    if (DEBUG_TRACKBALL) Log.v(mTag, "Delivering fake DPAD: "
                            + keycode);
                    movement--;
                    curTime = SystemClock.uptimeMillis();
                    enqueueInputEvent(new KeyEvent(curTime, curTime,
                            KeyEvent.ACTION_DOWN, keycode, 0, metaState,
                            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FALLBACK,
                            InputDevice.SOURCE_KEYBOARD));
                    enqueueInputEvent(new KeyEvent(curTime, curTime,
                            KeyEvent.ACTION_UP, keycode, 0, metaState,
                            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FALLBACK,
                            InputDevice.SOURCE_KEYBOARD));
                }
                mLastTime = curTime;
            }
        }

        public void cancel(MotionEvent event) {
            mLastTime = Integer.MIN_VALUE;

            // If we reach this, we consumed a trackball event.
            // Because we will not translate the trackball event into a key event,
            // touch mode will not exit, so we exit touch mode here.
            if (mView != null && mAdded) {
                ensureTouchMode(false);
            }
        }
    }

    /**
     * Maintains state information for a single trackball axis, generating
     * discrete (DPAD) movements based on raw trackball motion.
     */
    static final class TrackballAxis {
        /**
         * The maximum amount of acceleration we will apply.
         */
        static final float MAX_ACCELERATION = 20;

        /**
         * The maximum amount of time (in milliseconds) between events in order
         * for us to consider the user to be doing fast trackball movements,
         * and thus apply an acceleration.
         */
        static final long FAST_MOVE_TIME = 150;

        /**
         * Scaling factor to the time (in milliseconds) between events to how
         * much to multiple/divide the current acceleration.  When movement
         * is < FAST_MOVE_TIME this multiplies the acceleration; when >
         * FAST_MOVE_TIME it divides it.
         */
        static final float ACCEL_MOVE_SCALING_FACTOR = (1.0f / 40);

        static final float FIRST_MOVEMENT_THRESHOLD = 0.5f;
        static final float SECOND_CUMULATIVE_MOVEMENT_THRESHOLD = 2.0f;
        static final float SUBSEQUENT_INCREMENTAL_MOVEMENT_THRESHOLD = 1.0f;

        float position;
        float acceleration = 1;
        long lastMoveTime = 0;
        int step;
        int dir;
        int nonAccelMovement;

        void reset(int _step) {
            position = 0;
            acceleration = 1;
            lastMoveTime = 0;
            step = _step;
            dir = 0;
        }

        /**
         * Add trackball movement into the state.  If the direction of movement
         * has been reversed, the state is reset before adding the
         * movement (so that you don't have to compensate for any previously
         * collected movement before see the result of the movement in the
         * new direction).
         *
         * @return Returns the absolute value of the amount of movement
         * collected so far.
         */
        float collect(float off, long time, String axis) {
            long normTime;
            if (off > 0) {
                normTime = (long) (off * FAST_MOVE_TIME);
                if (dir < 0) {
                    if (DEBUG_TRACKBALL) Log.v(TAG, axis + " reversed to positive!");
                    position = 0;
                    step = 0;
                    acceleration = 1;
                    lastMoveTime = 0;
                }
                dir = 1;
            } else if (off < 0) {
                normTime = (long) ((-off) * FAST_MOVE_TIME);
                if (dir > 0) {
                    if (DEBUG_TRACKBALL) Log.v(TAG, axis + " reversed to negative!");
                    position = 0;
                    step = 0;
                    acceleration = 1;
                    lastMoveTime = 0;
                }
                dir = -1;
            } else {
                normTime = 0;
            }

            // The number of milliseconds between each movement that is
            // considered "normal" and will not result in any acceleration
            // or deceleration, scaled by the offset we have here.
            if (normTime > 0) {
                long delta = time - lastMoveTime;
                lastMoveTime = time;
                float acc = acceleration;
                if (delta < normTime) {
                    // The user is scrolling rapidly, so increase acceleration.
                    float scale = (normTime - delta) * ACCEL_MOVE_SCALING_FACTOR;
                    if (scale > 1) acc *= scale;
                    if (DEBUG_TRACKBALL) Log.v(TAG, axis + " accelerate: off="
                            + off + " normTime=" + normTime + " delta=" + delta
                            + " scale=" + scale + " acc=" + acc);
                    acceleration = acc < MAX_ACCELERATION ? acc : MAX_ACCELERATION;
                } else {
                    // The user is scrolling slowly, so decrease acceleration.
                    float scale = (delta - normTime) * ACCEL_MOVE_SCALING_FACTOR;
                    if (scale > 1) acc /= scale;
                    if (DEBUG_TRACKBALL) Log.v(TAG, axis + " deccelerate: off="
                            + off + " normTime=" + normTime + " delta=" + delta
                            + " scale=" + scale + " acc=" + acc);
                    acceleration = acc > 1 ? acc : 1;
                }
            }
            position += off;
            return Math.abs(position);
        }

        /**
         * Generate the number of discrete movement events appropriate for
         * the currently collected trackball movement.
         *
         * @return Returns the number of discrete movements, either positive
         * or negative, or 0 if there is not enough trackball movement yet
         * for a discrete movement.
         */
        int generate() {
            int movement = 0;
            nonAccelMovement = 0;
            do {
                final int dir = position >= 0 ? 1 : -1;
                switch (step) {
                    // If we are going to execute the first step, then we want
                    // to do this as soon as possible instead of waiting for
                    // a full movement, in order to make things look responsive.
                    case 0:
                        if (Math.abs(position) < FIRST_MOVEMENT_THRESHOLD) {
                            return movement;
                        }
                        movement += dir;
                        nonAccelMovement += dir;
                        step = 1;
                        break;
                    // If we have generated the first movement, then we need
                    // to wait for the second complete trackball motion before
                    // generating the second discrete movement.
                    case 1:
                        if (Math.abs(position) < SECOND_CUMULATIVE_MOVEMENT_THRESHOLD) {
                            return movement;
                        }
                        movement += dir;
                        nonAccelMovement += dir;
                        position -= SECOND_CUMULATIVE_MOVEMENT_THRESHOLD * dir;
                        step = 2;
                        break;
                    // After the first two, we generate discrete movements
                    // consistently with the trackball, applying an acceleration
                    // if the trackball is moving quickly.  This is a simple
                    // acceleration on top of what we already compute based
                    // on how quickly the wheel is being turned, to apply
                    // a longer increasing acceleration to continuous movement
                    // in one direction.
                    default:
                        if (Math.abs(position) < SUBSEQUENT_INCREMENTAL_MOVEMENT_THRESHOLD) {
                            return movement;
                        }
                        movement += dir;
                        position -= dir * SUBSEQUENT_INCREMENTAL_MOVEMENT_THRESHOLD;
                        float acc = acceleration;
                        acc *= 1.1f;
                        acceleration = acc < MAX_ACCELERATION ? acc : acceleration;
                        break;
                }
            } while (true);
        }
    }

    /**
     * Creates dpad events from unhandled joystick movements.
     */
    final class SyntheticJoystickHandler extends Handler {
        private final static String TAG = "SyntheticJoystickHandler";
        private final static int MSG_ENQUEUE_X_AXIS_KEY_REPEAT = 1;
        private final static int MSG_ENQUEUE_Y_AXIS_KEY_REPEAT = 2;

        private int mLastXDirection;
        private int mLastYDirection;
        private int mLastXKeyCode;
        private int mLastYKeyCode;

        public SyntheticJoystickHandler() {
            super(true);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_ENQUEUE_X_AXIS_KEY_REPEAT:
                case MSG_ENQUEUE_Y_AXIS_KEY_REPEAT: {
                    KeyEvent oldEvent = (KeyEvent) msg.obj;
                    KeyEvent e = KeyEvent.changeTimeRepeat(oldEvent,
                            SystemClock.uptimeMillis(),
                            oldEvent.getRepeatCount() + 1);
                    if (mAttachInfo.mHasWindowFocus) {
                        enqueueInputEvent(e);
                        Message m = obtainMessage(msg.what, e);
                        m.setAsynchronous(true);
                        sendMessageDelayed(m, ViewConfiguration.getKeyRepeatDelay());
                    }
                }
                break;
            }
        }

        public void process(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_CANCEL:
                    cancel(event);
                    break;
                case MotionEvent.ACTION_MOVE:
                    update(event, true);
                    break;
                default:
                    Log.w(mTag, "Unexpected action: " + event.getActionMasked());
            }
        }

        private void cancel(MotionEvent event) {
            removeMessages(MSG_ENQUEUE_X_AXIS_KEY_REPEAT);
            removeMessages(MSG_ENQUEUE_Y_AXIS_KEY_REPEAT);
            update(event, false);
        }

        private void update(MotionEvent event, boolean synthesizeNewKeys) {
            final long time = event.getEventTime();
            final int metaState = event.getMetaState();
            final int deviceId = event.getDeviceId();
            final int source = event.getSource();

            int xDirection = joystickAxisValueToDirection(
                    event.getAxisValue(MotionEvent.AXIS_HAT_X));
            if (xDirection == 0) {
                xDirection = joystickAxisValueToDirection(event.getX());
            }

            int yDirection = joystickAxisValueToDirection(
                    event.getAxisValue(MotionEvent.AXIS_HAT_Y));
            if (yDirection == 0) {
                yDirection = joystickAxisValueToDirection(event.getY());
            }

            if (xDirection != mLastXDirection) {
                if (mLastXKeyCode != 0) {
                    removeMessages(MSG_ENQUEUE_X_AXIS_KEY_REPEAT);
                    enqueueInputEvent(new KeyEvent(time, time,
                            KeyEvent.ACTION_UP, mLastXKeyCode, 0, metaState,
                            deviceId, 0, KeyEvent.FLAG_FALLBACK, source));
                    mLastXKeyCode = 0;
                }

                mLastXDirection = xDirection;

                if (xDirection != 0 && synthesizeNewKeys) {
                    mLastXKeyCode = xDirection > 0
                            ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT;
                    final KeyEvent e = new KeyEvent(time, time,
                            KeyEvent.ACTION_DOWN, mLastXKeyCode, 0, metaState,
                            deviceId, 0, KeyEvent.FLAG_FALLBACK, source);
                    enqueueInputEvent(e);
                    Message m = obtainMessage(MSG_ENQUEUE_X_AXIS_KEY_REPEAT, e);
                    m.setAsynchronous(true);
                    sendMessageDelayed(m, ViewConfiguration.getKeyRepeatTimeout());
                }
            }

            if (yDirection != mLastYDirection) {
                if (mLastYKeyCode != 0) {
                    removeMessages(MSG_ENQUEUE_Y_AXIS_KEY_REPEAT);
                    enqueueInputEvent(new KeyEvent(time, time,
                            KeyEvent.ACTION_UP, mLastYKeyCode, 0, metaState,
                            deviceId, 0, KeyEvent.FLAG_FALLBACK, source));
                    mLastYKeyCode = 0;
                }

                mLastYDirection = yDirection;

                if (yDirection != 0 && synthesizeNewKeys) {
                    mLastYKeyCode = yDirection > 0
                            ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_UP;
                    final KeyEvent e = new KeyEvent(time, time,
                            KeyEvent.ACTION_DOWN, mLastYKeyCode, 0, metaState,
                            deviceId, 0, KeyEvent.FLAG_FALLBACK, source);
                    enqueueInputEvent(e);
                    Message m = obtainMessage(MSG_ENQUEUE_Y_AXIS_KEY_REPEAT, e);
                    m.setAsynchronous(true);
                    sendMessageDelayed(m, ViewConfiguration.getKeyRepeatTimeout());
                }
            }
        }

        private int joystickAxisValueToDirection(float value) {
            if (value >= 0.5f) {
                return 1;
            } else if (value <= -0.5f) {
                return -1;
            } else {
                return 0;
            }
        }
    }

    /**
     * Creates dpad events from unhandled touch navigation movements.
     */
    final class SyntheticTouchNavigationHandler extends Handler {
        private static final String LOCAL_TAG = "SyntheticTouchNavigationHandler";
        private static final boolean LOCAL_DEBUG = false;

        // Assumed nominal width and height in millimeters of a touch navigation pad,
        // if no resolution information is available from the input system.
        private static final float DEFAULT_WIDTH_MILLIMETERS = 48;
        private static final float DEFAULT_HEIGHT_MILLIMETERS = 48;

        /* TODO: These constants should eventually be moved to ViewConfiguration. */

        // The nominal distance traveled to move by one unit.
        private static final int TICK_DISTANCE_MILLIMETERS = 12;

        // Minimum and maximum fling velocity in ticks per second.
        // The minimum velocity should be set such that we perform enough ticks per
        // second that the fling appears to be fluid.  For example, if we set the minimum
        // to 2 ticks per second, then there may be up to half a second delay between the next
        // to last and last ticks which is noticeably discrete and jerky.  This value should
        // probably not be set to anything less than about 4.
        // If fling accuracy is a problem then consider tuning the tick distance instead.
        private static final float MIN_FLING_VELOCITY_TICKS_PER_SECOND = 6f;
        private static final float MAX_FLING_VELOCITY_TICKS_PER_SECOND = 20f;

        // Fling velocity decay factor applied after each new key is emitted.
        // This parameter controls the deceleration and overall duration of the fling.
        // The fling stops automatically when its velocity drops below the minimum
        // fling velocity defined above.
        private static final float FLING_TICK_DECAY = 0.8f;

        /* The input device that we are tracking. */

        private int mCurrentDeviceId = -1;
        private int mCurrentSource;
        private boolean mCurrentDeviceSupported;

        /* Configuration for the current input device. */

        // The scaled tick distance.  A movement of this amount should generally translate
        // into a single dpad event in a given direction.
        private float mConfigTickDistance;

        // The minimum and maximum scaled fling velocity.
        private float mConfigMinFlingVelocity;
        private float mConfigMaxFlingVelocity;

        /* Tracking state. */

        // The velocity tracker for detecting flings.
        private VelocityTracker mVelocityTracker;

        // The active pointer id, or -1 if none.
        private int mActivePointerId = -1;

        // Location where tracking started.
        private float mStartX;
        private float mStartY;

        // Most recently observed position.
        private float mLastX;
        private float mLastY;

        // Accumulated movement delta since the last direction key was sent.
        private float mAccumulatedX;
        private float mAccumulatedY;

        // Set to true if any movement was delivered to the app.
        // Implies that tap slop was exceeded.
        private boolean mConsumedMovement;

        // The most recently sent key down event.
        // The keycode remains set until the direction changes or a fling ends
        // so that repeated key events may be generated as required.
        private long mPendingKeyDownTime;
        private int mPendingKeyCode = KeyEvent.KEYCODE_UNKNOWN;
        private int mPendingKeyRepeatCount;
        private int mPendingKeyMetaState;

        // The current fling velocity while a fling is in progress.
        private boolean mFlinging;
        private float mFlingVelocity;

        public SyntheticTouchNavigationHandler() {
            super(true);
        }

        public void process(MotionEvent event) {
            // Update the current device information.
            final long time = event.getEventTime();
            final int deviceId = event.getDeviceId();
            final int source = event.getSource();
            if (mCurrentDeviceId != deviceId || mCurrentSource != source) {
                finishKeys(time);
                finishTracking(time);
                mCurrentDeviceId = deviceId;
                mCurrentSource = source;
                mCurrentDeviceSupported = false;
                InputDevice device = event.getDevice();
                if (device != null) {
                    // In order to support an input device, we must know certain
                    // characteristics about it, such as its size and resolution.
                    InputDevice.MotionRange xRange = device.getMotionRange(MotionEvent.AXIS_X);
                    InputDevice.MotionRange yRange = device.getMotionRange(MotionEvent.AXIS_Y);
                    if (xRange != null && yRange != null) {
                        mCurrentDeviceSupported = true;

                        // Infer the resolution if it not actually known.
                        float xRes = xRange.getResolution();
                        if (xRes <= 0) {
                            xRes = xRange.getRange() / DEFAULT_WIDTH_MILLIMETERS;
                        }
                        float yRes = yRange.getResolution();
                        if (yRes <= 0) {
                            yRes = yRange.getRange() / DEFAULT_HEIGHT_MILLIMETERS;
                        }
                        float nominalRes = (xRes + yRes) * 0.5f;

                        // Precompute all of the configuration thresholds we will need.
                        mConfigTickDistance = TICK_DISTANCE_MILLIMETERS * nominalRes;
                        mConfigMinFlingVelocity =
                                MIN_FLING_VELOCITY_TICKS_PER_SECOND * mConfigTickDistance;
                        mConfigMaxFlingVelocity =
                                MAX_FLING_VELOCITY_TICKS_PER_SECOND * mConfigTickDistance;

                        if (LOCAL_DEBUG) {
                            Log.d(LOCAL_TAG, "Configured device " + mCurrentDeviceId
                                    + " (" + Integer.toHexString(mCurrentSource) + "): "
                                    + ", mConfigTickDistance=" + mConfigTickDistance
                                    + ", mConfigMinFlingVelocity=" + mConfigMinFlingVelocity
                                    + ", mConfigMaxFlingVelocity=" + mConfigMaxFlingVelocity);
                        }
                    }
                }
            }
            if (!mCurrentDeviceSupported) {
                return;
            }

            // Handle the event.
            final int action = event.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN: {
                    boolean caughtFling = mFlinging;
                    finishKeys(time);
                    finishTracking(time);
                    mActivePointerId = event.getPointerId(0);
                    mVelocityTracker = VelocityTracker.obtain();
                    mVelocityTracker.addMovement(event);
                    mStartX = event.getX();
                    mStartY = event.getY();
                    mLastX = mStartX;
                    mLastY = mStartY;
                    mAccumulatedX = 0;
                    mAccumulatedY = 0;

                    // If we caught a fling, then pretend that the tap slop has already
                    // been exceeded to suppress taps whose only purpose is to stop the fling.
                    mConsumedMovement = caughtFling;
                    break;
                }

                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_UP: {
                    if (mActivePointerId < 0) {
                        break;
                    }
                    final int index = event.findPointerIndex(mActivePointerId);
                    if (index < 0) {
                        finishKeys(time);
                        finishTracking(time);
                        break;
                    }

                    mVelocityTracker.addMovement(event);
                    final float x = event.getX(index);
                    final float y = event.getY(index);
                    mAccumulatedX += x - mLastX;
                    mAccumulatedY += y - mLastY;
                    mLastX = x;
                    mLastY = y;

                    // Consume any accumulated movement so far.
                    final int metaState = event.getMetaState();
                    consumeAccumulatedMovement(time, metaState);

                    // Detect taps and flings.
                    if (action == MotionEvent.ACTION_UP) {
                        if (mConsumedMovement && mPendingKeyCode != KeyEvent.KEYCODE_UNKNOWN) {
                            // It might be a fling.
                            mVelocityTracker.computeCurrentVelocity(1000, mConfigMaxFlingVelocity);
                            final float vx = mVelocityTracker.getXVelocity(mActivePointerId);
                            final float vy = mVelocityTracker.getYVelocity(mActivePointerId);
                            if (!startFling(time, vx, vy)) {
                                finishKeys(time);
                            }
                        }
                        finishTracking(time);
                    }
                    break;
                }

                case MotionEvent.ACTION_CANCEL: {
                    finishKeys(time);
                    finishTracking(time);
                    break;
                }
            }
        }

        public void cancel(MotionEvent event) {
            if (mCurrentDeviceId == event.getDeviceId()
                    && mCurrentSource == event.getSource()) {
                final long time = event.getEventTime();
                finishKeys(time);
                finishTracking(time);
            }
        }

        private void finishKeys(long time) {
            cancelFling();
            sendKeyUp(time);
        }

        private void finishTracking(long time) {
            if (mActivePointerId >= 0) {
                mActivePointerId = -1;
                mVelocityTracker.recycle();
                mVelocityTracker = null;
            }
        }

        private void consumeAccumulatedMovement(long time, int metaState) {
            final float absX = Math.abs(mAccumulatedX);
            final float absY = Math.abs(mAccumulatedY);
            if (absX >= absY) {
                if (absX >= mConfigTickDistance) {
                    mAccumulatedX = consumeAccumulatedMovement(time, metaState, mAccumulatedX,
                            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT);
                    mAccumulatedY = 0;
                    mConsumedMovement = true;
                }
            } else {
                if (absY >= mConfigTickDistance) {
                    mAccumulatedY = consumeAccumulatedMovement(time, metaState, mAccumulatedY,
                            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN);
                    mAccumulatedX = 0;
                    mConsumedMovement = true;
                }
            }
        }

        private float consumeAccumulatedMovement(long time, int metaState,
                                                 float accumulator, int negativeKeyCode, int positiveKeyCode) {
            while (accumulator <= -mConfigTickDistance) {
                sendKeyDownOrRepeat(time, negativeKeyCode, metaState);
                accumulator += mConfigTickDistance;
            }
            while (accumulator >= mConfigTickDistance) {
                sendKeyDownOrRepeat(time, positiveKeyCode, metaState);
                accumulator -= mConfigTickDistance;
            }
            return accumulator;
        }

        private void sendKeyDownOrRepeat(long time, int keyCode, int metaState) {
            if (mPendingKeyCode != keyCode) {
                sendKeyUp(time);
                mPendingKeyDownTime = time;
                mPendingKeyCode = keyCode;
                mPendingKeyRepeatCount = 0;
            } else {
                mPendingKeyRepeatCount += 1;
            }
            mPendingKeyMetaState = metaState;

            // Note: Normally we would pass FLAG_LONG_PRESS when the repeat count is 1
            // but it doesn't quite make sense when simulating the events in this way.
            if (LOCAL_DEBUG) {
                Log.d(LOCAL_TAG, "Sending key down: keyCode=" + mPendingKeyCode
                        + ", repeatCount=" + mPendingKeyRepeatCount
                        + ", metaState=" + Integer.toHexString(mPendingKeyMetaState));
            }
            enqueueInputEvent(new KeyEvent(mPendingKeyDownTime, time,
                    KeyEvent.ACTION_DOWN, mPendingKeyCode, mPendingKeyRepeatCount,
                    mPendingKeyMetaState, mCurrentDeviceId,
                    KeyEvent.FLAG_FALLBACK, mCurrentSource));
        }

        private void sendKeyUp(long time) {
            if (mPendingKeyCode != KeyEvent.KEYCODE_UNKNOWN) {
                if (LOCAL_DEBUG) {
                    Log.d(LOCAL_TAG, "Sending key up: keyCode=" + mPendingKeyCode
                            + ", metaState=" + Integer.toHexString(mPendingKeyMetaState));
                }
                enqueueInputEvent(new KeyEvent(mPendingKeyDownTime, time,
                        KeyEvent.ACTION_UP, mPendingKeyCode, 0, mPendingKeyMetaState,
                        mCurrentDeviceId, 0, KeyEvent.FLAG_FALLBACK,
                        mCurrentSource));
                mPendingKeyCode = KeyEvent.KEYCODE_UNKNOWN;
            }
        }

        private boolean startFling(long time, float vx, float vy) {
            if (LOCAL_DEBUG) {
                Log.d(LOCAL_TAG, "Considering fling: vx=" + vx + ", vy=" + vy
                        + ", min=" + mConfigMinFlingVelocity);
            }

            // Flings must be oriented in the same direction as the preceding movements.
            switch (mPendingKeyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (-vx >= mConfigMinFlingVelocity
                            && Math.abs(vy) < mConfigMinFlingVelocity) {
                        mFlingVelocity = -vx;
                        break;
                    }
                    return false;

                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (vx >= mConfigMinFlingVelocity
                            && Math.abs(vy) < mConfigMinFlingVelocity) {
                        mFlingVelocity = vx;
                        break;
                    }
                    return false;

                case KeyEvent.KEYCODE_DPAD_UP:
                    if (-vy >= mConfigMinFlingVelocity
                            && Math.abs(vx) < mConfigMinFlingVelocity) {
                        mFlingVelocity = -vy;
                        break;
                    }
                    return false;

                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (vy >= mConfigMinFlingVelocity
                            && Math.abs(vx) < mConfigMinFlingVelocity) {
                        mFlingVelocity = vy;
                        break;
                    }
                    return false;
            }

            // Post the first fling event.
            mFlinging = postFling(time);
            return mFlinging;
        }

        private boolean postFling(long time) {
            // The idea here is to estimate the time when the pointer would have
            // traveled one tick distance unit given the current fling velocity.
            // This effect creates continuity of motion.
            if (mFlingVelocity >= mConfigMinFlingVelocity) {
                long delay = (long) (mConfigTickDistance / mFlingVelocity * 1000);
                postAtTime(mFlingRunnable, time + delay);
                if (LOCAL_DEBUG) {
                    Log.d(LOCAL_TAG, "Posted fling: velocity="
                            + mFlingVelocity + ", delay=" + delay
                            + ", keyCode=" + mPendingKeyCode);
                }
                return true;
            }
            return false;
        }

        private void cancelFling() {
            if (mFlinging) {
                removeCallbacks(mFlingRunnable);
                mFlinging = false;
            }
        }

        private final Runnable mFlingRunnable = new Runnable() {
            @Override
            public void run() {
                final long time = SystemClock.uptimeMillis();
                sendKeyDownOrRepeat(time, mPendingKeyCode, mPendingKeyMetaState);
                mFlingVelocity *= FLING_TICK_DECAY;
                if (!postFling(time)) {
                    mFlinging = false;
                    finishKeys(time);
                }
            }
        };
    }

    final class SyntheticKeyboardHandler {
        public void process(KeyEvent event) {
            if ((event.getFlags() & KeyEvent.FLAG_FALLBACK) != 0) {
                return;
            }

            final KeyCharacterMap kcm = event.getKeyCharacterMap();
            final int keyCode = event.getKeyCode();
            final int metaState = event.getMetaState();

            // Check for fallback actions specified by the key character map.
            KeyCharacterMap.FallbackAction fallbackAction =
                    kcm.getFallbackAction(keyCode, metaState);
            if (fallbackAction != null) {
                final int flags = event.getFlags() | KeyEvent.FLAG_FALLBACK;
                KeyEvent fallbackEvent = KeyEvent.obtain(
                        event.getDownTime(), event.getEventTime(),
                        event.getAction(), fallbackAction.keyCode,
                        event.getRepeatCount(), fallbackAction.metaState,
                        event.getDeviceId(), event.getScanCode(),
                        flags, event.getSource(), null);
                fallbackAction.recycle();
                enqueueInputEvent(fallbackEvent);
            }
        }
    }

    /**
     * Returns true if the key is used for keyboard navigation.
     *
     * @param keyEvent The key event.
     * @return True if the key is used for keyboard navigation.
     */
    private static boolean isNavigationKey(KeyEvent keyEvent) {
        switch (keyEvent.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_MOVE_HOME:
            case KeyEvent.KEYCODE_MOVE_END:
            case KeyEvent.KEYCODE_TAB:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_ENTER:
                return true;
        }
        return false;
    }

    /**
     * Returns true if the key is used for typing.
     *
     * @param keyEvent The key event.
     * @return True if the key is used for typing.
     */
    private static boolean isTypingKey(KeyEvent keyEvent) {
        return keyEvent.getUnicodeChar() > 0;
    }

    /**
     * See if the key event means we should leave touch mode (and leave touch mode if so).
     *
     * @param event The key event.
     * @return Whether this key event should be consumed (meaning the act of
     * leaving touch mode alone is considered the event).
     */
    private boolean checkForLeavingTouchModeAndConsume(KeyEvent event) {
        // Only relevant in touch mode.
        if (!mAttachInfo.mInTouchMode) {
            return false;
        }

        // Only consider leaving touch mode on DOWN or MULTIPLE actions, never on UP.
        final int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_MULTIPLE) {
            return false;
        }

        // Don't leave touch mode if the IME told us not to.
        if ((event.getFlags() & KeyEvent.FLAG_KEEP_TOUCH_MODE) != 0) {
            return false;
        }

        // If the key can be used for keyboard navigation then leave touch mode
        // and select a focused view if needed (in ensureTouchMode).
        // When a new focused view is selected, we consume the navigation key because
        // navigation doesn't make much sense unless a view already has focus so
        // the key's purpose is to set focus.
        if (isNavigationKey(event)) {
            return ensureTouchMode(false);
        }

        // If the key can be used for typing then leave touch mode
        // and select a focused view if needed (in ensureTouchMode).
        // Always allow the view to process the typing key.
        if (isTypingKey(event)) {
            ensureTouchMode(false);
            return false;
        }

        return false;
    }

    /* drag/drop */
    void setLocalDragState(Object obj) {
        mLocalDragState = obj;
    }

    private void handleDragEvent(DragEvent event) {
        // From the root, only drag start/end/location are dispatched.  entered/exited
        // are determined and dispatched by the viewgroup hierarchy, who then report
        // that back here for ultimate reporting back to the framework.
        if (mView != null && mAdded) {
            final int what = event.mAction;

            // Cache the drag description when the operation starts, then fill it in
            // on subsequent calls as a convenience
            if (what == DragEvent.ACTION_DRAG_STARTED) {
                mCurrentDragView = null;    // Start the current-recipient tracking
                mDragDescription = event.mClipDescription;
            } else {
                event.mClipDescription = mDragDescription;
            }

            if (what == DragEvent.ACTION_DRAG_EXITED) {
                // A direct EXITED event means that the window manager knows we've just crossed
                // a window boundary, so the current drag target within this one must have
                // just been exited. Send the EXITED notification to the current drag view, if any.
                if (View.sCascadedDragDrop) {
                    mView.dispatchDragEnterExitInPreN(event);
                }
                setDragFocus(null, event);
            } else {
                // For events with a [screen] location, translate into window coordinates
                if ((what == DragEvent.ACTION_DRAG_LOCATION) || (what == DragEvent.ACTION_DROP)) {
                    mDragPoint.set(event.mX, event.mY);
                    if (mTranslator != null) {
                        mTranslator.translatePointInScreenToAppWindow(mDragPoint);
                    }

                    if (mCurScrollY != 0) {
                        mDragPoint.offset(0, mCurScrollY);
                    }

                    event.mX = mDragPoint.x;
                    event.mY = mDragPoint.y;
                }

                // Remember who the current drag target is pre-dispatch
                final View prevDragView = mCurrentDragView;

                // Now dispatch the drag/drop event
                boolean result = mView.dispatchDragEvent(event);

                if (what == DragEvent.ACTION_DRAG_LOCATION && !event.mEventHandlerWasCalled) {
                    // If the LOCATION event wasn't delivered to any handler, no view now has a drag
                    // focus.
                    setDragFocus(null, event);
                }

                // If we changed apparent drag target, tell the OS about it
                if (prevDragView != mCurrentDragView) {
                    try {
                        if (prevDragView != null) {
                            mWindowSession.dragRecipientExited(mWindow);
                        }
                        if (mCurrentDragView != null) {
                            mWindowSession.dragRecipientEntered(mWindow);
                        }
                    } catch (RemoteException e) {
                        Slog.e(mTag, "Unable to note drag target change");
                    }
                }

                // Report the drop result when we're done
                if (what == DragEvent.ACTION_DROP) {
                    mDragDescription = null;
                    try {
                        Log.i(mTag, "Reporting drop result: " + result);
                        mWindowSession.reportDropResult(mWindow, result);
                    } catch (RemoteException e) {
                        Log.e(mTag, "Unable to report drop result");
                    }
                }

                // When the drag operation ends, reset drag-related state
                if (what == DragEvent.ACTION_DRAG_ENDED) {
                    mCurrentDragView = null;
                    setLocalDragState(null);
                    mAttachInfo.mDragToken = null;
                    if (mAttachInfo.mDragSurface != null) {
                        mAttachInfo.mDragSurface.release();
                        mAttachInfo.mDragSurface = null;
                    }
                }
            }
        }
        event.recycle();
    }

    public void handleDispatchSystemUiVisibilityChanged(SystemUiVisibilityInfo args) {
        if (mSeq != args.seq) {
            // The sequence has changed, so we need to update our value and make
            // sure to do a traversal afterward so the window manager is given our
            // most recent data.
            mSeq = args.seq;
            mAttachInfo.mForceReportNewAttributes = true;
            scheduleTraversals();
        }
        if (mView == null) return;
        if (args.localChanges != 0) {
            mView.updateLocalSystemUiVisibility(args.localValue, args.localChanges);
        }

        int visibility = args.globalVisibility & View.SYSTEM_UI_CLEARABLE_FLAGS;
        if (visibility != mAttachInfo.mGlobalSystemUiVisibility) {
            mAttachInfo.mGlobalSystemUiVisibility = visibility;
            mView.dispatchSystemUiVisibilityChanged(visibility);
        }
    }

    public void handleDispatchWindowShown() {
        mAttachInfo.mTreeObserver.dispatchOnWindowShown();
    }

    public void handleRequestKeyboardShortcuts(IResultReceiver receiver, int deviceId) {
        Bundle data = new Bundle();
        ArrayList<KeyboardShortcutGroup> list = new ArrayList<>();
        if (mView != null) {
            mView.requestKeyboardShortcuts(list, deviceId);
        }
        data.putParcelableArrayList(WindowManager.PARCEL_KEY_SHORTCUTS_ARRAY, list);
        try {
            receiver.send(0, data);
        } catch (RemoteException e) {
        }
    }

    public void getLastTouchPoint(Point outLocation) {
        outLocation.x = (int) mLastTouchPoint.x;
        outLocation.y = (int) mLastTouchPoint.y;
    }

    public int getLastTouchSource() {
        return mLastTouchSource;
    }

    public void setDragFocus(View newDragTarget, DragEvent event) {
        if (mCurrentDragView != newDragTarget && !View.sCascadedDragDrop) {
            // Send EXITED and ENTERED notifications to the old and new drag focus views.

            final float tx = event.mX;
            final float ty = event.mY;
            final int action = event.mAction;
            // Position should not be available for ACTION_DRAG_ENTERED and ACTION_DRAG_EXITED.
            event.mX = 0;
            event.mY = 0;

            if (mCurrentDragView != null) {
                event.mAction = DragEvent.ACTION_DRAG_EXITED;
                mCurrentDragView.callDragEventHandler(event);
            }

            if (newDragTarget != null) {
                event.mAction = DragEvent.ACTION_DRAG_ENTERED;
                newDragTarget.callDragEventHandler(event);
            }

            event.mAction = action;
            event.mX = tx;
            event.mY = ty;
        }

        mCurrentDragView = newDragTarget;
    }

    private AudioManager getAudioManager() {
        if (mView == null) {
            throw new IllegalStateException("getAudioManager called when there is no mView");
        }
        if (mAudioManager == null) {
            mAudioManager = (AudioManager) mView.getContext().getSystemService(Context.AUDIO_SERVICE);
        }
        return mAudioManager;
    }

    public AccessibilityInteractionController getAccessibilityInteractionController() {
        if (mView == null) {
            throw new IllegalStateException("getAccessibilityInteractionController"
                    + " called when there is no mView");
        }
        if (mAccessibilityInteractionController == null) {
            mAccessibilityInteractionController = new AccessibilityInteractionController(this);
        }
        return mAccessibilityInteractionController;
    }

    // 通知WMS布局窗口
    private int relayoutWindow(WindowManager.LayoutParams params, int viewVisibility,
                               boolean insetsPending) throws RemoteException {

        float appScale = mAttachInfo.mApplicationScale;
        boolean restore = false;
        if (params != null && mTranslator != null) {
            restore = true;
            params.backup();
            mTranslator.translateWindowLayout(params);
        }
        if (params != null) {
            if (DBG) Log.d(mTag, "WindowLayout in layoutWindow:" + params);
        }
        mPendingConfiguration.seq = 0;
        //Log.d(mTag, ">>>>>> CALLING relayout");
        if (params != null && mOrigWindowType != params.type) {
            // For compatibility with old apps, don't crash here.
            if (mTargetSdkVersion < Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
                Slog.w(mTag, "Window type can not be changed after "
                        + "the window is added; ignoring change of " + mView);
                params.type = mOrigWindowType;
            }
        }
        int relayoutResult = mWindowSession.relayout(
                mWindow, mSeq, params,
                (int) (mView.getMeasuredWidth() * appScale + 0.5f),
                (int) (mView.getMeasuredHeight() * appScale + 0.5f),
                viewVisibility, insetsPending ? WindowManagerGlobal.RELAYOUT_INSETS_PENDING : 0,
                mWinFrame, mPendingOverscanInsets, mPendingContentInsets, mPendingVisibleInsets,
                mPendingStableInsets, mPendingOutsets, mPendingBackDropFrame, mPendingConfiguration,
                mSurface);

        mPendingAlwaysConsumeNavBar =
                (relayoutResult & WindowManagerGlobal.RELAYOUT_RES_CONSUME_ALWAYS_NAV_BAR) != 0;

        //Log.d(mTag, "<<<<<< BACK FROM relayout");
        if (restore) {
            params.restore();
        }

        if (mTranslator != null) {
            mTranslator.translateRectInScreenToAppWinFrame(mWinFrame);
            mTranslator.translateRectInScreenToAppWindow(mPendingOverscanInsets);
            mTranslator.translateRectInScreenToAppWindow(mPendingContentInsets);
            mTranslator.translateRectInScreenToAppWindow(mPendingVisibleInsets);
            mTranslator.translateRectInScreenToAppWindow(mPendingStableInsets);
        }
        return relayoutResult;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void playSoundEffect(int effectId) {
        checkThread();

        try {
            final AudioManager audioManager = getAudioManager();

            switch (effectId) {
                case SoundEffectConstants.CLICK:
                    audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK);
                    return;
                case SoundEffectConstants.NAVIGATION_DOWN:
                    audioManager.playSoundEffect(AudioManager.FX_FOCUS_NAVIGATION_DOWN);
                    return;
                case SoundEffectConstants.NAVIGATION_LEFT:
                    audioManager.playSoundEffect(AudioManager.FX_FOCUS_NAVIGATION_LEFT);
                    return;
                case SoundEffectConstants.NAVIGATION_RIGHT:
                    audioManager.playSoundEffect(AudioManager.FX_FOCUS_NAVIGATION_RIGHT);
                    return;
                case SoundEffectConstants.NAVIGATION_UP:
                    audioManager.playSoundEffect(AudioManager.FX_FOCUS_NAVIGATION_UP);
                    return;
                default:
                    throw new IllegalArgumentException("unknown effect id " + effectId +
                            " not defined in " + SoundEffectConstants.class.getCanonicalName());
            }
        } catch (IllegalStateException e) {
            // Exception thrown by getAudioManager() when mView is null
            Log.e(mTag, "FATAL EXCEPTION when attempting to play sound effect: " + e);
            e.printStackTrace();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean performHapticFeedback(int effectId, boolean always) {
        try {
            return mWindowSession.performHapticFeedback(mWindow, effectId, always);
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public View focusSearch(View focused, int direction) {
        checkThread();
        if (!(mView instanceof ViewGroup)) {
            return null;
        }
        return FocusFinder.getInstance().findNextFocus((ViewGroup) mView, focused, direction);
    }

    public void debug() {
        mView.debug();
    }

    public void dump(String prefix, FileDescriptor fd, PrintWriter writer, String[] args) {
        String innerPrefix = prefix + "  ";
        writer.print(prefix);
        writer.println("ViewRoot:");
        writer.print(innerPrefix);
        writer.print("mAdded=");
        writer.print(mAdded);
        writer.print(" mRemoved=");
        writer.println(mRemoved);
        writer.print(innerPrefix);
        writer.print("mConsumeBatchedInputScheduled=");
        writer.println(mConsumeBatchedInputScheduled);
        writer.print(innerPrefix);
        writer.print("mConsumeBatchedInputImmediatelyScheduled=");
        writer.println(mConsumeBatchedInputImmediatelyScheduled);
        writer.print(innerPrefix);
        writer.print("mPendingInputEventCount=");
        writer.println(mPendingInputEventCount);
        writer.print(innerPrefix);
        writer.print("mProcessInputEventsScheduled=");
        writer.println(mProcessInputEventsScheduled);
        writer.print(innerPrefix);
        writer.print("mTraversalScheduled=");
        writer.print(mTraversalScheduled);
        writer.print(innerPrefix);
        writer.print("mIsAmbientMode=");
        writer.print(mIsAmbientMode);
        if (mTraversalScheduled) {
            writer.print(" (barrier=");
            writer.print(mTraversalBarrier);
            writer.println(")");
        } else {
            writer.println();
        }
        mFirstInputStage.dump(innerPrefix, writer);

        mChoreographer.dump(prefix, writer);

        writer.print(prefix);
        writer.println("View Hierarchy:");
        dumpViewHierarchy(innerPrefix, writer, mView);
    }

    private void dumpViewHierarchy(String prefix, PrintWriter writer, View view) {
        writer.print(prefix);
        if (view == null) {
            writer.println("null");
            return;
        }
        writer.println(view.toString());
        if (!(view instanceof ViewGroup)) {
            return;
        }
        ViewGroup grp = (ViewGroup) view;
        final int N = grp.getChildCount();
        if (N <= 0) {
            return;
        }
        prefix = prefix + "  ";
        for (int i = 0; i < N; i++) {
            dumpViewHierarchy(prefix, writer, grp.getChildAt(i));
        }
    }

    public void dumpGfxInfo(int[] info) {
        info[0] = info[1] = 0;
        if (mView != null) {
            getGfxInfo(mView, info);
        }
    }

    private static void getGfxInfo(View view, int[] info) {
        RenderNode renderNode = view.mRenderNode;
        info[0]++;
        if (renderNode != null) {
            info[1] += renderNode.getDebugSize();
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;

            int count = group.getChildCount();
            for (int i = 0; i < count; i++) {
                getGfxInfo(group.getChildAt(i), info);
            }
        }
    }

    /**
     * @param immediate True, do now if not in traversal. False, put on queue and do later.
     * @return True, request has been queued. False, request has been completed.
     */
    boolean die(boolean immediate) {
        // Make sure we do execute immediately if we are in the middle of a traversal or the damage
        // done by dispatchDetachedFromWindow will cause havoc on return.
        if (immediate && !mIsInTraversal) {
            doDie();
            return false;
        }

        if (!mIsDrawing) {
            destroyHardwareRenderer();
        } else {
            Log.e(mTag, "Attempting to destroy the window while drawing!\n" +
                    "  window=" + this + ", title=" + mWindowAttributes.getTitle());
        }
        mHandler.sendEmptyMessage(MSG_DIE);
        return true;
    }

    /**
     * 1、遍历调用窗口脱离方法；
     * 2、销毁硬件渲染器；
     * 3、如果窗口布局参数发生改变，或者窗口的可视性发生了改变，则需要请求WMS重新布局窗口；
     * 4、销毁surface对象；
     * 5、通知WMS移除此窗口
     */
    void doDie() {
        checkThread();
        if (LOCAL_LOGV) Log.v(mTag, "DIE in " + this + " of " + mSurface);
        synchronized (this) {
            if (mRemoved) {
                return;
            }
            mRemoved = true;
            if (mAdded) {
                dispatchDetachedFromWindow();
            }

            if (mAdded && !mFirst) {
                destroyHardwareRenderer();

                if (mView != null) {
                    int viewVisibility = mView.getVisibility();
                    boolean viewVisibilityChanged = mViewVisibility != viewVisibility;
                    if (mWindowAttributesChanged || viewVisibilityChanged) {
                        // If layout params have been changed, first give them
                        // to the window manager to make sure it has the correct
                        // animation info.
                        try {
                            if ((relayoutWindow(mWindowAttributes, viewVisibility, false)
                                    & WindowManagerGlobal.RELAYOUT_RES_FIRST_TIME) != 0) {
                                mWindowSession.finishDrawing(mWindow);
                            }
                        } catch (RemoteException e) {
                        }
                    }

                    mSurface.release();
                }
            }

            mAdded = false;
        }
        WindowManagerGlobal.getInstance().doRemoveView(this);
    }

    public void requestUpdateConfiguration(Configuration config) {
        Message msg = mHandler.obtainMessage(MSG_UPDATE_CONFIGURATION, config);
        mHandler.sendMessage(msg);
    }

    public void loadSystemProperties() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                // Profiling
                mProfileRendering = SystemProperties.getBoolean(PROPERTY_PROFILE_RENDERING, false);
                profileRendering(mAttachInfo.mHasWindowFocus);

                // Hardware rendering
                if (mAttachInfo.mHardwareRenderer != null) {
                    if (mAttachInfo.mHardwareRenderer.loadSystemProperties()) {
                        invalidate();
                    }
                }

                // Layout debugging
                boolean layout = SystemProperties.getBoolean(View.DEBUG_LAYOUT_PROPERTY, false);
                if (layout != mAttachInfo.mDebugLayout) {
                    mAttachInfo.mDebugLayout = layout;
                    if (!mHandler.hasMessages(MSG_INVALIDATE_WORLD)) {
                        mHandler.sendEmptyMessageDelayed(MSG_INVALIDATE_WORLD, 200);
                    }
                }
            }
        });
    }

    private void destroyHardwareRenderer() {
        ThreadedRenderer hardwareRenderer = mAttachInfo.mHardwareRenderer;

        if (hardwareRenderer != null) {
            if (mView != null) {
                hardwareRenderer.destroyHardwareResources(mView);
            }
            hardwareRenderer.destroy();
            hardwareRenderer.setRequested(false);

            mAttachInfo.mHardwareRenderer = null;
            mAttachInfo.mHardwareAccelerated = false;
        }
    }

    /**
     * 由WMS通过Binder调用,用以调整大小
     *
     * @param frame               窗口大小？
     * @param overscanInsets      整个屏幕区域
     * @param contentInsets       内容区域
     * @param visibleInsets       可视性区域
     * @param stableInsets
     * @param outsets
     * @param reportDraw          是否报告了绘制
     * @param newConfig           新的配置对象
     * @param backDropFrame       整个屏幕？
     * @param forceLayout         是否强制布局
     * @param alwaysConsumeNavBar 是否总是消耗导航栏？
     */
    public void dispatchResized(Rect frame, Rect overscanInsets, Rect contentInsets,
                                Rect visibleInsets, Rect stableInsets, Rect outsets, boolean reportDraw,
                                Configuration newConfig, Rect backDropFrame, boolean forceLayout,
                                boolean alwaysConsumeNavBar) {
        if (DEBUG_LAYOUT) Log.v(mTag, "Resizing " + this + ": frame=" + frame.toShortString()
                + " contentInsets=" + contentInsets.toShortString()
                + " visibleInsets=" + visibleInsets.toShortString()
                + " reportDraw=" + reportDraw
                + " backDropFrame=" + backDropFrame);

        // Tell all listeners that we are resizing the window so that the chrome can get
        // updated as fast as possible on a separate thread,
        // 告诉所有的监听器，我们正在重新调整床的大小，以便chrome能够在一个独立的线程之中尽可能快的获取到
        // 更新
        if (mDragResizing) {
            // 是否是全屏
            boolean fullscreen = frame.equals(backDropFrame);
            synchronized (mWindowCallbacks) {
                for (int i = mWindowCallbacks.size() - 1; i >= 0; i--) {
                    mWindowCallbacks.get(i).onWindowSizeIsChanging(backDropFrame, fullscreen,
                            visibleInsets, stableInsets);
                }
            }
        }

        Message msg = mHandler.obtainMessage(reportDraw ? MSG_RESIZED_REPORT : MSG_RESIZED);
        if (mTranslator != null) {
            mTranslator.translateRectInScreenToAppWindow(frame);
            mTranslator.translateRectInScreenToAppWindow(overscanInsets);
            mTranslator.translateRectInScreenToAppWindow(contentInsets);
            mTranslator.translateRectInScreenToAppWindow(visibleInsets);
        }
        SomeArgs args = SomeArgs.obtain();
        final boolean sameProcessCall = (Binder.getCallingPid() == android.os.Process.myPid());
        args.arg1 = sameProcessCall ? new Rect(frame) : frame;
        args.arg2 = sameProcessCall ? new Rect(contentInsets) : contentInsets;
        args.arg3 = sameProcessCall ? new Rect(visibleInsets) : visibleInsets;
        args.arg4 = sameProcessCall && newConfig != null ? new Configuration(newConfig) : newConfig;
        args.arg5 = sameProcessCall ? new Rect(overscanInsets) : overscanInsets;
        args.arg6 = sameProcessCall ? new Rect(stableInsets) : stableInsets;
        args.arg7 = sameProcessCall ? new Rect(outsets) : outsets;
        args.arg8 = sameProcessCall ? new Rect(backDropFrame) : backDropFrame;
        args.argi1 = forceLayout ? 1 : 0;
        args.argi2 = alwaysConsumeNavBar ? 1 : 0;
        msg.obj = args;
        mHandler.sendMessage(msg);
    }

    public void dispatchMoved(int newX, int newY) {
        if (DEBUG_LAYOUT) Log.v(mTag, "Window moved " + this + ": newX=" + newX + " newY=" + newY);
        if (mTranslator != null) {
            PointF point = new PointF(newX, newY);
            mTranslator.translatePointInScreenToAppWindow(point);
            newX = (int) (point.x + 0.5);
            newY = (int) (point.y + 0.5);
        }
        Message msg = mHandler.obtainMessage(MSG_WINDOW_MOVED, newX, newY);
        mHandler.sendMessage(msg);
    }

    /**
     * Represents a pending input event that is waiting in a queue.
     * 代表一个即将（被处理）的，正在队列中等待的输入事件
     * <p>
     * Input events are processed in serial order by the timestamp specified by
     * {@link InputEvent#getEventTimeNano()}.  In general, the input dispatcher delivers
     * one input event to the application at a time and waits for the application
     * to finish handling it before delivering the next one.
     * 输入事件通过各自的时间戳，按照一条连续的顺序进行处理。
     * 一般而言，输入时间分发者每次传递一个输入事件给应用，并且在分发下一个事件之前，等待应用处理完此输入事件。
     * <p>
     * However, because the application or IME can synthesize and inject multiple
     * key events at a time without going through the input dispatcher, we end up
     * needing a queue on the application's side.
     * 然而，因为应用或者输入法每次都可以，不通过输入事件分发者，合成并注入多个按键信息，
     * 所以我们在应用端仍然需要一个队列
     */
    private static final class QueuedInputEvent {
        public static final int FLAG_DELIVER_POST_IME = 1 << 0;
        // 延迟处理
        public static final int FLAG_DEFERRED = 1 << 1;
        // 事件停止处理
        public static final int FLAG_FINISHED = 1 << 2;
        // 完成处理
        public static final int FLAG_FINISHED_HANDLED = 1 << 3;
        // 重新合成
        public static final int FLAG_RESYNTHESIZED = 1 << 4;
        // 未处理
        public static final int FLAG_UNHANDLED = 1 << 5;

        public QueuedInputEvent mNext;

        public InputEvent mEvent;
        public InputEventReceiver mReceiver;
        public int mFlags;

        public boolean shouldSkipIme() {
            if ((mFlags & FLAG_DELIVER_POST_IME) != 0) {
                return true;
            }
            return mEvent instanceof MotionEvent
                    && mEvent.isFromSource(InputDevice.SOURCE_CLASS_POINTER);
        }

        public boolean shouldSendToSynthesizer() {
            if ((mFlags & FLAG_UNHANDLED) != 0) {
                return true;
            }

            return false;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("QueuedInputEvent{flags=");
            boolean hasPrevious = false;
            hasPrevious = flagToString("DELIVER_POST_IME", FLAG_DELIVER_POST_IME, hasPrevious, sb);
            hasPrevious = flagToString("DEFERRED", FLAG_DEFERRED, hasPrevious, sb);
            hasPrevious = flagToString("FINISHED", FLAG_FINISHED, hasPrevious, sb);
            hasPrevious = flagToString("FINISHED_HANDLED", FLAG_FINISHED_HANDLED, hasPrevious, sb);
            hasPrevious = flagToString("RESYNTHESIZED", FLAG_RESYNTHESIZED, hasPrevious, sb);
            hasPrevious = flagToString("UNHANDLED", FLAG_UNHANDLED, hasPrevious, sb);
            if (!hasPrevious) {
                sb.append("0");
            }
            sb.append(", hasNextQueuedEvent=" + (mEvent != null ? "true" : "false"));
            sb.append(", hasInputEventReceiver=" + (mReceiver != null ? "true" : "false"));
            sb.append(", mEvent=" + mEvent + "}");
            return sb.toString();
        }

        private boolean flagToString(String name, int flag,
                                     boolean hasPrevious, StringBuilder sb) {
            if ((mFlags & flag) != 0) {
                if (hasPrevious) {
                    sb.append("|");
                }
                sb.append(name);
                return true;
            }
            return hasPrevious;
        }
    }

    private QueuedInputEvent obtainQueuedInputEvent(InputEvent event,
                                                    InputEventReceiver receiver, int flags) {
        QueuedInputEvent q = mQueuedInputEventPool;
        if (q != null) {
            mQueuedInputEventPoolSize -= 1;
            mQueuedInputEventPool = q.mNext;
            q.mNext = null;
        } else {
            q = new QueuedInputEvent();
        }

        q.mEvent = event;
        q.mReceiver = receiver;
        q.mFlags = flags;
        return q;
    }

    private void recycleQueuedInputEvent(QueuedInputEvent q) {
        q.mEvent = null;
        q.mReceiver = null;

        if (mQueuedInputEventPoolSize < MAX_QUEUED_INPUT_EVENT_POOL_SIZE) {
            mQueuedInputEventPoolSize += 1;
            q.mNext = mQueuedInputEventPool;
            mQueuedInputEventPool = q;
        }
    }

    void enqueueInputEvent(InputEvent event) {
        enqueueInputEvent(event, null, 0, false);
    }

    /**
     * 入队输入事件
     * WMS接受到消息会有此方法作为第一个处理入口？
     *
     * @param event              输入事件
     * @param receiver           输入事件接收器
     * @param flags              标志位
     * @param processImmediately 是否立即处理
     */
    void enqueueInputEvent(InputEvent event,
                           InputEventReceiver receiver, int flags, boolean processImmediately) {
        adjustInputEventForCompatibility(event);
        // 将原始的输入事件封装为一个可队列化的输入事件
        QueuedInputEvent q = obtainQueuedInputEvent(event, receiver, flags);

        // Always enqueue the input event in order, regardless of its time stamp.
        // We do this because the application or the IME may inject key events
        // in response to touch events and we want to ensure that the injected keys
        // are processed in the order they were received and we cannot trust that
        // the time stamp of injected events are monotonic.
        // 总是按顺序入队输入事件，而忽略它的时间戳。
        // 我们这样做是因为应用或者输入法窗口可能注入按键事件，用以相应触摸事件
        // 并且我们期望确保被注入的按键能够按照它们被接受的顺序被处理，
        // 并且我们不信任被注入事件的时间戳是单调递增的
        QueuedInputEvent last = mPendingInputEventTail;
        if (last == null) {
            mPendingInputEventHead = q;
            mPendingInputEventTail = q;
        } else {
            last.mNext = q;
            mPendingInputEventTail = q;
        }
        mPendingInputEventCount += 1;
        Trace.traceCounter(Trace.TRACE_TAG_INPUT, mPendingInputEventQueueLengthCounterName,
                mPendingInputEventCount);

        if (processImmediately) {
            doProcessInputEvents();
        } else {
            scheduleProcessInputEvents();
        }
    }

    private void scheduleProcessInputEvents() {
        if (!mProcessInputEventsScheduled) {
            mProcessInputEventsScheduled = true;
            Message msg = mHandler.obtainMessage(MSG_PROCESS_INPUT_EVENTS);
            msg.setAsynchronous(true);
            mHandler.sendMessage(msg);
        }
    }

    void doProcessInputEvents() {
        // Deliver all pending input events in the queue.
        // 分配队列之中所有即将处理的输入事件
        while (mPendingInputEventHead != null) {
            QueuedInputEvent q = mPendingInputEventHead;
            mPendingInputEventHead = q.mNext;
            if (mPendingInputEventHead == null) {
                mPendingInputEventTail = null;
            }
            q.mNext = null;

            mPendingInputEventCount -= 1;
            Trace.traceCounter(Trace.TRACE_TAG_INPUT, mPendingInputEventQueueLengthCounterName,
                    mPendingInputEventCount);

            long eventTime = q.mEvent.getEventTimeNano();
            long oldestEventTime = eventTime;
            if (q.mEvent instanceof MotionEvent) {
                MotionEvent me = (MotionEvent) q.mEvent;
                if (me.getHistorySize() > 0) {
                    oldestEventTime = me.getHistoricalEventTimeNano(0);
                }
            }
            mChoreographer.mFrameInfo.updateInputEventTime(eventTime, oldestEventTime);

            deliverInputEvent(q);
        }

        // We are done processing all input events that we can process right now
        // so we can clear the pending flag immediately.
        // 我们将处理所有马上能够处理的输入事件，所以我们可以立即清除即将处理的标识
        if (mProcessInputEventsScheduled) {
            mProcessInputEventsScheduled = false;
            mHandler.removeMessages(MSG_PROCESS_INPUT_EVENTS);
        }
    }

    // 将输入事件交给InputStage对象处理
    private void deliverInputEvent(QueuedInputEvent q) {
        Trace.asyncTraceBegin(Trace.TRACE_TAG_VIEW, "deliverInputEvent",
                q.mEvent.getSequenceNumber());
        if (mInputEventConsistencyVerifier != null) {
            mInputEventConsistencyVerifier.onInputEvent(q.mEvent, 0);
        }

        InputStage stage;
        if (q.shouldSendToSynthesizer()) {
            stage = mSyntheticInputStage;
        } else {
            stage = q.shouldSkipIme() ? mFirstPostImeInputStage : mFirstInputStage;
        }

        if (stage != null) {
            stage.deliver(q);
        } else {
            finishInputEvent(q);
        }
    }

    /**
     * 当没有InputStage对象处理输入事件后，
     * 还可以让InputReceiver对象进行最后的处理;
     * 最后，如果可以，回收输入事件
     *
     * @param q
     */
    private void finishInputEvent(QueuedInputEvent q) {
        Trace.asyncTraceEnd(Trace.TRACE_TAG_VIEW, "deliverInputEvent",
                q.mEvent.getSequenceNumber());

        if (q.mReceiver != null) {
            boolean handled = (q.mFlags & QueuedInputEvent.FLAG_FINISHED_HANDLED) != 0;
            q.mReceiver.finishInputEvent(q.mEvent, handled);
        } else {
            q.mEvent.recycleIfNeededAfterDispatch();
        }

        recycleQueuedInputEvent(q);
    }

    private void adjustInputEventForCompatibility(InputEvent e) {
        if (mTargetSdkVersion < Build.VERSION_CODES.M && e instanceof MotionEvent) {
            MotionEvent motion = (MotionEvent) e;
            final int mask =
                    MotionEvent.BUTTON_STYLUS_PRIMARY | MotionEvent.BUTTON_STYLUS_SECONDARY;
            final int buttonState = motion.getButtonState();
            final int compatButtonState = (buttonState & mask) >> 4;
            if (compatButtonState != 0) {
                motion.setButtonState(buttonState | compatButtonState);
            }
        }
    }

    /**
     * 是否是结束事件:按键事件的话就是按键被释放事件；
     * 触摸事件的话，就是ACTION_UP事件
     *
     * @param event
     * @return
     */
    static boolean isTerminalInputEvent(InputEvent event) {
        if (event instanceof KeyEvent) {
            final KeyEvent keyEvent = (KeyEvent) event;
            return keyEvent.getAction() == KeyEvent.ACTION_UP;
        } else {
            final MotionEvent motionEvent = (MotionEvent) event;
            final int action = motionEvent.getAction();
            return action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL
                    || action == MotionEvent.ACTION_HOVER_EXIT;
        }
    }

    void scheduleConsumeBatchedInput() {
        if (!mConsumeBatchedInputScheduled) {
            mConsumeBatchedInputScheduled = true;
            mChoreographer.postCallback(Choreographer.CALLBACK_INPUT,
                    mConsumedBatchedInputRunnable, null);
        }
    }

    void unscheduleConsumeBatchedInput() {
        if (mConsumeBatchedInputScheduled) {
            mConsumeBatchedInputScheduled = false;
            mChoreographer.removeCallbacks(Choreographer.CALLBACK_INPUT,
                    mConsumedBatchedInputRunnable, null);
        }
    }

    void scheduleConsumeBatchedInputImmediately() {
        if (!mConsumeBatchedInputImmediatelyScheduled) {
            unscheduleConsumeBatchedInput();
            mConsumeBatchedInputImmediatelyScheduled = true;
            mHandler.post(mConsumeBatchedInputImmediatelyRunnable);
        }
    }

    void doConsumeBatchedInput(long frameTimeNanos) {
        if (mConsumeBatchedInputScheduled) {
            mConsumeBatchedInputScheduled = false;
            if (mInputEventReceiver != null) {
                if (mInputEventReceiver.consumeBatchedInputEvents(frameTimeNanos)
                        && frameTimeNanos != -1) {
                    // If we consumed a batch here, we want to go ahead and schedule the
                    // consumption of batched input events on the next frame. Otherwise, we would
                    // wait until we have more input events pending and might get starved by other
                    // things occurring in the process. If the frame time is -1, however, then
                    // we're in a non-batching mode, so there's no need to schedule this.
                    // 如果我们在此处消耗了一批输入事件，则我们将期望回到头部
                    // 并且分配此消耗到喜爱一个帧。否则，我们将等待，直到我们
                    // 有更多的即将处理的输入事件，并且可能由进程中的其他正在
                    // 发生的事件开始。
                    // 如果帧的事件是-1，则我们将进入一个非批量模式，所以不需要进行调度
                    scheduleConsumeBatchedInput();
                }
            }
            doProcessInputEvents();
        }
    }

    final class TraversalRunnable implements Runnable {
        @Override
        public void run() {
            doTraversal();
        }
    }

    final TraversalRunnable mTraversalRunnable = new TraversalRunnable();

    final class WindowInputEventReceiver extends InputEventReceiver {
        public WindowInputEventReceiver(InputChannel inputChannel, Looper looper) {
            super(inputChannel, looper);
        }

        @Override
        public void onInputEvent(InputEvent event) {
            enqueueInputEvent(event, this, 0, true);
        }

        @Override
        public void onBatchedInputEventPending() {
            if (mUnbufferedInputDispatch) {
                super.onBatchedInputEventPending();
            } else {
                scheduleConsumeBatchedInput();
            }
        }

        @Override
        public void dispose() {
            unscheduleConsumeBatchedInput();
            super.dispose();
        }
    }

    WindowInputEventReceiver mInputEventReceiver;

    final class ConsumeBatchedInputRunnable implements Runnable {
        @Override
        public void run() {
            doConsumeBatchedInput(mChoreographer.getFrameTimeNanos());
        }
    }

    final ConsumeBatchedInputRunnable mConsumedBatchedInputRunnable =
            new ConsumeBatchedInputRunnable();
    boolean mConsumeBatchedInputScheduled;

    final class ConsumeBatchedInputImmediatelyRunnable implements Runnable {
        @Override
        public void run() {
            doConsumeBatchedInput(-1);
        }
    }

    final ConsumeBatchedInputImmediatelyRunnable mConsumeBatchedInputImmediatelyRunnable =
            new ConsumeBatchedInputImmediatelyRunnable();

    /**
     * 是否通过立即调度的方式，消耗批量的输入事件
     */
    boolean mConsumeBatchedInputImmediatelyScheduled;

    final class InvalidateOnAnimationRunnable implements Runnable {
        private boolean mPosted;
        private final ArrayList<View> mViews = new ArrayList<View>();
        private final ArrayList<AttachInfo.InvalidateInfo> mViewRects =
                new ArrayList<AttachInfo.InvalidateInfo>();
        private View[] mTempViews;
        private AttachInfo.InvalidateInfo[] mTempViewRects;

        public void addView(View view) {
            synchronized (this) {
                mViews.add(view);
                postIfNeededLocked();
            }
        }

        public void addViewRect(AttachInfo.InvalidateInfo info) {
            synchronized (this) {
                mViewRects.add(info);
                postIfNeededLocked();
            }
        }

        public void removeView(View view) {
            synchronized (this) {
                mViews.remove(view);

                for (int i = mViewRects.size(); i-- > 0; ) {
                    AttachInfo.InvalidateInfo info = mViewRects.get(i);
                    if (info.target == view) {
                        mViewRects.remove(i);
                        info.recycle();
                    }
                }

                if (mPosted && mViews.isEmpty() && mViewRects.isEmpty()) {
                    mChoreographer.removeCallbacks(Choreographer.CALLBACK_ANIMATION, this, null);
                    mPosted = false;
                }
            }
        }

        @Override
        public void run() {
            final int viewCount;
            final int viewRectCount;
            synchronized (this) {
                mPosted = false;

                viewCount = mViews.size();
                if (viewCount != 0) {
                    mTempViews = mViews.toArray(mTempViews != null
                            ? mTempViews : new View[viewCount]);
                    mViews.clear();
                }

                viewRectCount = mViewRects.size();
                if (viewRectCount != 0) {
                    mTempViewRects = mViewRects.toArray(mTempViewRects != null
                            ? mTempViewRects : new AttachInfo.InvalidateInfo[viewRectCount]);
                    mViewRects.clear();
                }
            }

            for (int i = 0; i < viewCount; i++) {
                mTempViews[i].invalidate();
                mTempViews[i] = null;
            }

            for (int i = 0; i < viewRectCount; i++) {
                final View.AttachInfo.InvalidateInfo info = mTempViewRects[i];
                info.target.invalidate(info.left, info.top, info.right, info.bottom);
                info.recycle();
            }
        }

        private void postIfNeededLocked() {
            if (!mPosted) {
                mChoreographer.postCallback(Choreographer.CALLBACK_ANIMATION, this, null);
                mPosted = true;
            }
        }
    }

    /**
     * 在下一帧被触发时，需要无效的视图及区域
     */
    final InvalidateOnAnimationRunnable mInvalidateOnAnimationRunnable =
            new InvalidateOnAnimationRunnable();

    public void dispatchInvalidateDelayed(View view, long delayMilliseconds) {
        Message msg = mHandler.obtainMessage(MSG_INVALIDATE, view);
        mHandler.sendMessageDelayed(msg, delayMilliseconds);
    }

    public void dispatchInvalidateRectDelayed(AttachInfo.InvalidateInfo info,
                                              long delayMilliseconds) {
        final Message msg = mHandler.obtainMessage(MSG_INVALIDATE_RECT, info);
        mHandler.sendMessageDelayed(msg, delayMilliseconds);
    }

    public void dispatchInvalidateOnAnimation(View view) {
        mInvalidateOnAnimationRunnable.addView(view);
    }

    public void dispatchInvalidateRectOnAnimation(AttachInfo.InvalidateInfo info) {
        mInvalidateOnAnimationRunnable.addViewRect(info);
    }

    public void cancelInvalidate(View view) {
        mHandler.removeMessages(MSG_INVALIDATE, view);
        // fixme: might leak the AttachInfo.InvalidateInfo objects instead of returning
        // them to the pool
        mHandler.removeMessages(MSG_INVALIDATE_RECT, view);
        mInvalidateOnAnimationRunnable.removeView(view);
    }

    public void dispatchInputEvent(InputEvent event) {
        dispatchInputEvent(event, null);
    }

    public void dispatchInputEvent(InputEvent event, InputEventReceiver receiver) {
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = event;
        args.arg2 = receiver;
        Message msg = mHandler.obtainMessage(MSG_DISPATCH_INPUT_EVENT, args);
        msg.setAsynchronous(true);
        mHandler.sendMessage(msg);
    }

    public void synthesizeInputEvent(InputEvent event) {
        Message msg = mHandler.obtainMessage(MSG_SYNTHESIZE_INPUT_EVENT, event);
        msg.setAsynchronous(true);
        mHandler.sendMessage(msg);
    }

    public void dispatchKeyFromIme(KeyEvent event) {
        Message msg = mHandler.obtainMessage(MSG_DISPATCH_KEY_FROM_IME, event);
        msg.setAsynchronous(true);
        mHandler.sendMessage(msg);
    }

    /**
     * Reinject unhandled {@link InputEvent}s in order to synthesize fallbacks events.
     * <p>
     * Note that it is the responsibility of the caller of this API to recycle the InputEvent it
     * passes in.
     */
    public void dispatchUnhandledInputEvent(InputEvent event) {
        if (event instanceof MotionEvent) {
            event = MotionEvent.obtain((MotionEvent) event);
        }
        synthesizeInputEvent(event);
    }

    public void dispatchAppVisibility(boolean visible) {
        Message msg = mHandler.obtainMessage(MSG_DISPATCH_APP_VISIBILITY);
        msg.arg1 = visible ? 1 : 0;
        mHandler.sendMessage(msg);
    }

    public void dispatchGetNewSurface() {
        Message msg = mHandler.obtainMessage(MSG_DISPATCH_GET_NEW_SURFACE);
        mHandler.sendMessage(msg);
    }

    public void windowFocusChanged(boolean hasFocus, boolean inTouchMode) {
        Message msg = Message.obtain();
        msg.what = MSG_WINDOW_FOCUS_CHANGED;
        msg.arg1 = hasFocus ? 1 : 0;
        msg.arg2 = inTouchMode ? 1 : 0;
        mHandler.sendMessage(msg);
    }

    public void dispatchWindowShown() {
        mHandler.sendEmptyMessage(MSG_DISPATCH_WINDOW_SHOWN);
    }

    public void dispatchCloseSystemDialogs(String reason) {
        Message msg = Message.obtain();
        msg.what = MSG_CLOSE_SYSTEM_DIALOGS;
        msg.obj = reason;
        mHandler.sendMessage(msg);
    }

    public void dispatchDragEvent(DragEvent event) {
        final int what;
        if (event.getAction() == DragEvent.ACTION_DRAG_LOCATION) {
            what = MSG_DISPATCH_DRAG_LOCATION_EVENT;
            mHandler.removeMessages(what);
        } else {
            what = MSG_DISPATCH_DRAG_EVENT;
        }
        Message msg = mHandler.obtainMessage(what, event);
        mHandler.sendMessage(msg);
    }

    public void updatePointerIcon(float x, float y) {
        final int what = MSG_UPDATE_POINTER_ICON;
        mHandler.removeMessages(what);
        final long now = SystemClock.uptimeMillis();
        final MotionEvent event = MotionEvent.obtain(
                0, now, MotionEvent.ACTION_HOVER_MOVE, x, y, 0);
        Message msg = mHandler.obtainMessage(what, event);
        mHandler.sendMessage(msg);
    }

    public void dispatchSystemUiVisibilityChanged(int seq, int globalVisibility,
                                                  int localValue, int localChanges) {
        SystemUiVisibilityInfo args = new SystemUiVisibilityInfo();
        args.seq = seq;
        args.globalVisibility = globalVisibility;
        args.localValue = localValue;
        args.localChanges = localChanges;
        mHandler.sendMessage(mHandler.obtainMessage(MSG_DISPATCH_SYSTEM_UI_VISIBILITY, args));
    }

    public void dispatchCheckFocus() {
        if (!mHandler.hasMessages(MSG_CHECK_FOCUS)) {
            // This will result in a call to checkFocus() below.
            mHandler.sendEmptyMessage(MSG_CHECK_FOCUS);
        }
    }

    public void dispatchRequestKeyboardShortcuts(IResultReceiver receiver, int deviceId) {
        mHandler.obtainMessage(
                MSG_REQUEST_KEYBOARD_SHORTCUTS, deviceId, 0, receiver).sendToTarget();
    }

    /**
     * Post a callback to send a
     * {@link AccessibilityEvent#TYPE_WINDOW_CONTENT_CHANGED} event.
     * This event is send at most once every
     * {@link ViewConfiguration#getSendRecurringAccessibilityEventsInterval()}.
     */
    private void postSendWindowContentChangedCallback(View source, int changeType) {
        if (mSendWindowContentChangedAccessibilityEvent == null) {
            mSendWindowContentChangedAccessibilityEvent =
                    new SendWindowContentChangedAccessibilityEvent();
        }
        mSendWindowContentChangedAccessibilityEvent.runOrPost(source, changeType);
    }

    /**
     * Remove a posted callback to send a
     * {@link AccessibilityEvent#TYPE_WINDOW_CONTENT_CHANGED} event.
     */
    private void removeSendWindowContentChangedCallback() {
        if (mSendWindowContentChangedAccessibilityEvent != null) {
            mHandler.removeCallbacks(mSendWindowContentChangedAccessibilityEvent);
        }
    }

    @Override
    public boolean showContextMenuForChild(View originalView) {
        return false;
    }

    @Override
    public boolean showContextMenuForChild(View originalView, float x, float y) {
        return false;
    }

    @Override
    public ActionMode startActionModeForChild(View originalView, ActionMode.Callback callback) {
        return null;
    }

    @Override
    public ActionMode startActionModeForChild(
            View originalView, ActionMode.Callback callback, int type) {
        return null;
    }

    @Override
    public void createContextMenu(ContextMenu menu) {
    }

    @Override
    public void childDrawableStateChanged(View child) {
    }

    @Override
    public boolean requestSendAccessibilityEvent(View child, AccessibilityEvent event) {
        if (mView == null || mStopped || mPausedForTransition) {
            return false;
        }
        // Intercept accessibility focus events fired by virtual nodes to keep
        // track of accessibility focus position in such nodes.
        final int eventType = event.getEventType();
        switch (eventType) {
            case AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED: {
                final long sourceNodeId = event.getSourceNodeId();
                final int accessibilityViewId = AccessibilityNodeInfo.getAccessibilityViewId(
                        sourceNodeId);
                View source = mView.findViewByAccessibilityId(accessibilityViewId);
                if (source != null) {
                    AccessibilityNodeProvider provider = source.getAccessibilityNodeProvider();
                    if (provider != null) {
                        final int virtualNodeId = AccessibilityNodeInfo.getVirtualDescendantId(
                                sourceNodeId);
                        final AccessibilityNodeInfo node;
                        if (virtualNodeId == AccessibilityNodeInfo.UNDEFINED_ITEM_ID) {
                            node = provider.createAccessibilityNodeInfo(
                                    AccessibilityNodeProvider.HOST_VIEW_ID);
                        } else {
                            node = provider.createAccessibilityNodeInfo(virtualNodeId);
                        }
                        setAccessibilityFocus(source, node);
                    }
                }
            }
            break;
            case AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED: {
                final long sourceNodeId = event.getSourceNodeId();
                final int accessibilityViewId = AccessibilityNodeInfo.getAccessibilityViewId(
                        sourceNodeId);
                View source = mView.findViewByAccessibilityId(accessibilityViewId);
                if (source != null) {
                    AccessibilityNodeProvider provider = source.getAccessibilityNodeProvider();
                    if (provider != null) {
                        setAccessibilityFocus(null, null);
                    }
                }
            }
            break;


            case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED: {
                handleWindowContentChangedEvent(event);
            }
            break;
        }
        mAccessibilityManager.sendAccessibilityEvent(event);
        return true;
    }

    /**
     * Updates the focused virtual view, when necessary, in response to a
     * content changed event.
     * <p>
     * This is necessary to get updated bounds after a position change.
     *
     * @param event an accessibility event of type
     *              {@link AccessibilityEvent#TYPE_WINDOW_CONTENT_CHANGED}
     */
    private void handleWindowContentChangedEvent(AccessibilityEvent event) {
        final View focusedHost = mAccessibilityFocusedHost;
        if (focusedHost == null || mAccessibilityFocusedVirtualView == null) {
            // No virtual view focused, nothing to do here.
            return;
        }

        final AccessibilityNodeProvider provider = focusedHost.getAccessibilityNodeProvider();
        if (provider == null) {
            // Error state: virtual view with no provider. Clear focus.
            mAccessibilityFocusedHost = null;
            mAccessibilityFocusedVirtualView = null;
            focusedHost.clearAccessibilityFocusNoCallbacks(0);
            return;
        }

        // We only care about change types that may affect the bounds of the
        // focused virtual view.
        final int changes = event.getContentChangeTypes();
        if ((changes & AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE) == 0
                && changes != AccessibilityEvent.CONTENT_CHANGE_TYPE_UNDEFINED) {
            return;
        }

        final long eventSourceNodeId = event.getSourceNodeId();
        final int changedViewId = AccessibilityNodeInfo.getAccessibilityViewId(eventSourceNodeId);

        // Search up the tree for subtree containment.
        boolean hostInSubtree = false;
        View root = mAccessibilityFocusedHost;
        while (root != null && !hostInSubtree) {
            if (changedViewId == root.getAccessibilityViewId()) {
                hostInSubtree = true;
            } else {
                final ViewParent parent = root.getParent();
                if (parent instanceof View) {
                    root = (View) parent;
                } else {
                    root = null;
                }
            }
        }

        // We care only about changes in subtrees containing the host view.
        if (!hostInSubtree) {
            return;
        }

        final long focusedSourceNodeId = mAccessibilityFocusedVirtualView.getSourceNodeId();
        int focusedChildId = AccessibilityNodeInfo.getVirtualDescendantId(focusedSourceNodeId);
        if (focusedChildId == AccessibilityNodeInfo.UNDEFINED_ITEM_ID) {
            // TODO: Should we clear the focused virtual view?
            focusedChildId = AccessibilityNodeProvider.HOST_VIEW_ID;
        }

        // Refresh the node for the focused virtual view.
        final Rect oldBounds = mTempRect;
        mAccessibilityFocusedVirtualView.getBoundsInScreen(oldBounds);
        mAccessibilityFocusedVirtualView = provider.createAccessibilityNodeInfo(focusedChildId);
        if (mAccessibilityFocusedVirtualView == null) {
            // Error state: The node no longer exists. Clear focus.
            mAccessibilityFocusedHost = null;
            focusedHost.clearAccessibilityFocusNoCallbacks(0);

            // This will probably fail, but try to keep the provider's internal
            // state consistent by clearing focus.
            provider.performAction(focusedChildId,
                    AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS.getId(), null);
            invalidateRectOnScreen(oldBounds);
        } else {
            // The node was refreshed, invalidate bounds if necessary.
            final Rect newBounds = mAccessibilityFocusedVirtualView.getBoundsInScreen();
            if (!oldBounds.equals(newBounds)) {
                oldBounds.union(newBounds);
                invalidateRectOnScreen(oldBounds);
            }
        }
    }

    @Override
    public void notifySubtreeAccessibilityStateChanged(View child, View source, int changeType) {
        postSendWindowContentChangedCallback(source, changeType);
    }

    @Override
    public boolean canResolveLayoutDirection() {
        return true;
    }

    @Override
    public boolean isLayoutDirectionResolved() {
        return true;
    }

    @Override
    public int getLayoutDirection() {
        return View.LAYOUT_DIRECTION_RESOLVED_DEFAULT;
    }

    @Override
    public boolean canResolveTextDirection() {
        return true;
    }

    @Override
    public boolean isTextDirectionResolved() {
        return true;
    }

    @Override
    public int getTextDirection() {
        return View.TEXT_DIRECTION_RESOLVED_DEFAULT;
    }

    @Override
    public boolean canResolveTextAlignment() {
        return true;
    }

    @Override
    public boolean isTextAlignmentResolved() {
        return true;
    }

    @Override
    public int getTextAlignment() {
        return View.TEXT_ALIGNMENT_RESOLVED_DEFAULT;
    }

    private View getCommonPredecessor(View first, View second) {
        if (mTempHashSet == null) {
            mTempHashSet = new HashSet<View>();
        }
        HashSet<View> seen = mTempHashSet;
        seen.clear();
        View firstCurrent = first;
        while (firstCurrent != null) {
            seen.add(firstCurrent);
            ViewParent firstCurrentParent = firstCurrent.mParent;
            if (firstCurrentParent instanceof View) {
                firstCurrent = (View) firstCurrentParent;
            } else {
                firstCurrent = null;
            }
        }
        View secondCurrent = second;
        while (secondCurrent != null) {
            if (seen.contains(secondCurrent)) {
                seen.clear();
                return secondCurrent;
            }
            ViewParent secondCurrentParent = secondCurrent.mParent;
            if (secondCurrentParent instanceof View) {
                secondCurrent = (View) secondCurrentParent;
            } else {
                secondCurrent = null;
            }
        }
        seen.clear();
        return null;
    }

    void checkThread() {
        if (mThread != Thread.currentThread()) {
            throw new CalledFromWrongThreadException(
                    "Only the original thread that created a view hierarchy can touch its views.");
        }
    }

    @Override
    public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        // ViewAncestor never intercepts touch event, so this can be a no-op
    }

    @Override
    public boolean requestChildRectangleOnScreen(View child, Rect rectangle, boolean immediate) {
        if (rectangle == null) {
            return scrollToRectOrFocus(null, immediate);
        }
        rectangle.offset(child.getLeft() - child.getScrollX(),
                child.getTop() - child.getScrollY());
        final boolean scrolled = scrollToRectOrFocus(rectangle, immediate);
        mTempRect.set(rectangle);
        mTempRect.offset(0, -mCurScrollY);
        mTempRect.offset(mAttachInfo.mWindowLeft, mAttachInfo.mWindowTop);
        try {
            mWindowSession.onRectangleOnScreenRequested(mWindow, mTempRect);
        } catch (RemoteException re) {
            /* ignore */
        }
        return scrolled;
    }

    @Override
    public void childHasTransientStateChanged(View child, boolean hasTransientState) {
        // Do nothing.
    }

    @Override
    public boolean onStartNestedScroll(View child, View target, int nestedScrollAxes) {
        return false;
    }

    @Override
    public void onStopNestedScroll(View target) {
    }

    @Override
    public void onNestedScrollAccepted(View child, View target, int nestedScrollAxes) {
    }

    @Override
    public void onNestedScroll(View target, int dxConsumed, int dyConsumed,
                               int dxUnconsumed, int dyUnconsumed) {
    }

    @Override
    public void onNestedPreScroll(View target, int dx, int dy, int[] consumed) {
    }

    @Override
    public boolean onNestedFling(View target, float velocityX, float velocityY, boolean consumed) {
        return false;
    }

    @Override
    public boolean onNestedPreFling(View target, float velocityX, float velocityY) {
        return false;
    }

    @Override
    public boolean onNestedPrePerformAccessibilityAction(View target, int action, Bundle args) {
        return false;
    }

    /**
     * Force the window to report its next draw.
     * <p>
     * This method is only supposed to be used to speed up the interaction from SystemUI and window
     * manager when waiting for the first frame to be drawn when turning on the screen. DO NOT USE
     * unless you fully understand this interaction.
     *
     * @hide
     */
    public void setReportNextDraw() {
        mReportNextDraw = true;
        invalidate();
    }

    void changeCanvasOpacity(boolean opaque) {
        Log.d(mTag, "changeCanvasOpacity: opaque=" + opaque);
        if (mAttachInfo.mHardwareRenderer != null) {
            mAttachInfo.mHardwareRenderer.setOpaque(opaque);
        }
    }

    class TakenSurfaceHolder extends BaseSurfaceHolder {
        @Override
        public boolean onAllowLockCanvas() {
            return mDrawingAllowed;
        }

        @Override
        public void onRelayoutContainer() {
            // Not currently interesting -- from changing between fixed and layout size.
        }

        @Override
        public void setFormat(int format) {
            ((RootViewSurfaceTaker) mView).setSurfaceFormat(format);
        }

        @Override
        public void setType(int type) {
            ((RootViewSurfaceTaker) mView).setSurfaceType(type);
        }

        @Override
        public void onUpdateSurface() {
            // We take care of format and type changes on our own.
            throw new IllegalStateException("Shouldn't be here");
        }

        @Override
        public boolean isCreating() {
            return mIsCreating;
        }

        @Override
        public void setFixedSize(int width, int height) {
            throw new UnsupportedOperationException(
                    "Currently only support sizing from layout");
        }

        @Override
        public void setKeepScreenOn(boolean screenOn) {
            ((RootViewSurfaceTaker) mView).setSurfaceKeepScreenOn(screenOn);
        }
    }

    // 由WMS主动发起的相关回调
    static class W extends IWindow.Stub {
        private final WeakReference<ViewRootImpl> mViewAncestor;
        private final IWindowSession mWindowSession;

        W(ViewRootImpl viewAncestor) {
            mViewAncestor = new WeakReference<ViewRootImpl>(viewAncestor);
            mWindowSession = viewAncestor.mWindowSession;
        }

        /**
         * 重新计算大小
         *
         * @param frame               窗口大小？
         * @param overscanInsets      整个屏幕区域
         * @param contentInsets       内容区域
         * @param visibleInsets       可视性区域
         * @param stableInsets
         * @param outsets
         * @param reportDraw          是否报告了绘制
         * @param newConfig           新的配置对象
         * @param backDropFrame       整个屏幕？
         * @param forceLayout         是否强制布局
         * @param alwaysConsumeNavBar 是否总是消耗导航栏？
         */
        @Override
        public void resized(Rect frame, Rect overscanInsets, Rect contentInsets,
                            Rect visibleInsets, Rect stableInsets, Rect outsets, boolean reportDraw,
                            Configuration newConfig, Rect backDropFrame, boolean forceLayout,
                            boolean alwaysConsumeNavBar) {
            final ViewRootImpl viewAncestor = mViewAncestor.get();
            if (viewAncestor != null) {
                viewAncestor.dispatchResized(frame, overscanInsets, contentInsets,
                        visibleInsets, stableInsets, outsets, reportDraw, newConfig, backDropFrame,
                        forceLayout, alwaysConsumeNavBar);
            }
        }

        /**
         * 移动窗口
         *
         * @param newX
         * @param newY
         */
        @Override
        public void moved(int newX, int newY) {
            final ViewRootImpl viewAncestor = mViewAncestor.get();
            if (viewAncestor != null) {
                viewAncestor.dispatchMoved(newX, newY);
            }
        }

        /**
         * 分配最新的窗口可见性
         *
         * @param visible
         */
        @Override
        public void dispatchAppVisibility(boolean visible) {
            final ViewRootImpl viewAncestor = mViewAncestor.get();
            if (viewAncestor != null) {
                viewAncestor.dispatchAppVisibility(visible);
            }
        }

        /**
         * 有一个新的Surface对象
         */
        @Override
        public void dispatchGetNewSurface() {
            final ViewRootImpl viewAncestor = mViewAncestor.get();
            if (viewAncestor != null) {
                viewAncestor.dispatchGetNewSurface();
            }
        }

        /**
         * 窗口焦点改变
         *
         * @param hasFocus    是否拥有焦点
         * @param inTouchMode 是否还处于触摸模式之中
         */
        @Override
        public void windowFocusChanged(boolean hasFocus, boolean inTouchMode) {
            final ViewRootImpl viewAncestor = mViewAncestor.get();
            if (viewAncestor != null) {
                viewAncestor.windowFocusChanged(hasFocus, inTouchMode);
            }
        }

        /**
         * 检测相应的权限
         *
         * @param permission
         * @return
         */
        private static int checkCallingPermission(String permission) {
            try {
                return ActivityManagerNative.getDefault().checkPermission(
                        permission, Binder.getCallingPid(), Binder.getCallingUid());
            } catch (RemoteException e) {
                return PackageManager.PERMISSION_DENIED;
            }
        }

        @Override
        public void executeCommand(String command, String parameters, ParcelFileDescriptor out) {
            final ViewRootImpl viewAncestor = mViewAncestor.get();
            if (viewAncestor != null) {
                final View view = viewAncestor.mView;
                if (view != null) {
                    if (checkCallingPermission(Manifest.permission.DUMP) !=
                            PackageManager.PERMISSION_GRANTED) {
                        throw new SecurityException("Insufficient permissions to invoke"
                                + " executeCommand() from pid=" + Binder.getCallingPid()
                                + ", uid=" + Binder.getCallingUid());
                    }

                    OutputStream clientStream = null;
                    try {
                        clientStream = new ParcelFileDescriptor.AutoCloseOutputStream(out);
                        ViewDebug.dispatchCommand(view, command, parameters, clientStream);
                    } catch (IOException e) {
                        e.printStackTrace();
                    } finally {
                        if (clientStream != null) {
                            try {
                                clientStream.close();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
            }
        }

        /**
         * 关闭系统对话框
         *
         * @param reason 原因
         */
        @Override
        public void closeSystemDialogs(String reason) {
            final ViewRootImpl viewAncestor = mViewAncestor.get();
            if (viewAncestor != null) {
                viewAncestor.dispatchCloseSystemDialogs(reason);
            }
        }

        /**
         * 墙纸偏移量
         *
         * @param x
         * @param y
         * @param xStep
         * @param yStep
         * @param sync
         */
        @Override
        public void dispatchWallpaperOffsets(float x, float y, float xStep, float yStep,
                                             boolean sync) {
            if (sync) {
                try {
                    mWindowSession.wallpaperOffsetsComplete(asBinder());
                } catch (RemoteException e) {
                }
            }
        }

        @Override
        public void dispatchWallpaperCommand(String action, int x, int y,
                                             int z, Bundle extras, boolean sync) {
            if (sync) {
                try {
                    mWindowSession.wallpaperCommandComplete(asBinder(), null);
                } catch (RemoteException e) {
                }
            }
        }

        /* Drag/drop */

        /**
         * 拖拽事件
         *
         * @param event
         */
        @Override
        public void dispatchDragEvent(DragEvent event) {
            final ViewRootImpl viewAncestor = mViewAncestor.get();
            if (viewAncestor != null) {
                viewAncestor.dispatchDragEvent(event);
            }
        }

        /**
         * 更新鼠标图标
         *
         * @param x
         * @param y
         */
        @Override
        public void updatePointerIcon(float x, float y) {
            final ViewRootImpl viewAncestor = mViewAncestor.get();
            if (viewAncestor != null) {
                viewAncestor.updatePointerIcon(x, y);
            }
        }

        /**
         * 传递系统UI的可见性改变事件
         *
         * @param seq
         * @param globalVisibility
         * @param localValue
         * @param localChanges
         */
        @Override
        public void dispatchSystemUiVisibilityChanged(int seq, int globalVisibility,
                                                      int localValue, int localChanges) {
            final ViewRootImpl viewAncestor = mViewAncestor.get();
            if (viewAncestor != null) {
                viewAncestor.dispatchSystemUiVisibilityChanged(seq, globalVisibility,
                        localValue, localChanges);
            }
        }

        /**
         * 分配系统显示事件
         */
        @Override
        public void dispatchWindowShown() {
            final ViewRootImpl viewAncestor = mViewAncestor.get();
            if (viewAncestor != null) {
                viewAncestor.dispatchWindowShown();
            }
        }

        @Override
        public void requestAppKeyboardShortcuts(IResultReceiver receiver, int deviceId) {
            ViewRootImpl viewAncestor = mViewAncestor.get();
            if (viewAncestor != null) {
                viewAncestor.dispatchRequestKeyboardShortcuts(receiver, deviceId);
            }
        }
    }

    public static final class CalledFromWrongThreadException extends AndroidRuntimeException {
        public CalledFromWrongThreadException(String msg) {
            super(msg);
        }
    }

    static HandlerActionQueue getRunQueue() {
        HandlerActionQueue rq = sRunQueues.get();
        if (rq != null) {
            return rq;
        }
        rq = new HandlerActionQueue();
        sRunQueues.set(rq);
        return rq;
    }

    /**
     * Start a drag resizing which will inform all listeners that a window resize is taking place.
     */
    private void startDragResizing(Rect initialBounds, boolean fullscreen, Rect systemInsets,
                                   Rect stableInsets, int resizeMode) {
        if (!mDragResizing) {
            mDragResizing = true;
            for (int i = mWindowCallbacks.size() - 1; i >= 0; i--) {
                mWindowCallbacks.get(i).onWindowDragResizeStart(initialBounds, fullscreen,
                        systemInsets, stableInsets, resizeMode);
            }
            mFullRedrawNeeded = true;
        }
    }

    /**
     * End a drag resize which will inform all listeners that a window resize has ended.
     */
    private void endDragResizing() {
        if (mDragResizing) {
            mDragResizing = false;
            for (int i = mWindowCallbacks.size() - 1; i >= 0; i--) {
                mWindowCallbacks.get(i).onWindowDragResizeEnd();
            }
            mFullRedrawNeeded = true;
        }
    }

    private boolean updateContentDrawBounds() {
        boolean updated = false;
        for (int i = mWindowCallbacks.size() - 1; i >= 0; i--) {
            updated |= mWindowCallbacks.get(i).onContentDrawn(
                    mWindowAttributes.surfaceInsets.left,
                    mWindowAttributes.surfaceInsets.top,
                    mWidth, mHeight);
        }
        return updated | (mDragResizing && mReportNextDraw);
    }

    private void requestDrawWindow() {
        if (mReportNextDraw) {
            mWindowDrawCountDown = new CountDownLatch(mWindowCallbacks.size());
        }
        for (int i = mWindowCallbacks.size() - 1; i >= 0; i--) {
            mWindowCallbacks.get(i).onRequestDraw(mReportNextDraw);
        }
    }

    /**
     * Tells this instance that its corresponding activity has just relaunched. In this case, we
     * need to force a relayout of the window to make sure we get the correct bounds from window
     * manager.
     */
    public void reportActivityRelaunched() {
        mActivityRelaunched = true;
    }

    /**
     * Class for managing the accessibility interaction connection
     * based on the global accessibility state.
     */
    final class AccessibilityInteractionConnectionManager
            implements AccessibilityStateChangeListener {
        @Override
        public void onAccessibilityStateChanged(boolean enabled) {
            if (enabled) {
                ensureConnection();
                if (mAttachInfo.mHasWindowFocus) {
                    mView.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
                    View focusedView = mView.findFocus();
                    if (focusedView != null && focusedView != mView) {
                        focusedView.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED);
                    }
                }
            } else {
                ensureNoConnection();
                mHandler.obtainMessage(MSG_CLEAR_ACCESSIBILITY_FOCUS_HOST).sendToTarget();
            }
        }

        public void ensureConnection() {
            final boolean registered =
                    mAttachInfo.mAccessibilityWindowId != AccessibilityNodeInfo.UNDEFINED_ITEM_ID;
            if (!registered) {
                mAttachInfo.mAccessibilityWindowId =
                        mAccessibilityManager.addAccessibilityInteractionConnection(mWindow,
                                new AccessibilityInteractionConnection(ViewRootImpl.this));
            }
        }

        public void ensureNoConnection() {
            final boolean registered =
                    mAttachInfo.mAccessibilityWindowId != AccessibilityNodeInfo.UNDEFINED_ITEM_ID;
            if (registered) {
                mAttachInfo.mAccessibilityWindowId = AccessibilityNodeInfo.UNDEFINED_ITEM_ID;
                mAccessibilityManager.removeAccessibilityInteractionConnection(mWindow);
            }
        }
    }

    final class HighContrastTextManager implements HighTextContrastChangeListener {
        HighContrastTextManager() {
            mAttachInfo.mHighContrastText = mAccessibilityManager.isHighTextContrastEnabled();
        }

        @Override
        public void onHighTextContrastStateChanged(boolean enabled) {
            mAttachInfo.mHighContrastText = enabled;

            // Destroy Displaylists so they can be recreated with high contrast recordings
            destroyHardwareResources();

            // Schedule redraw, which will rerecord + redraw all text
            invalidate();
        }
    }

    /**
     * This class is an interface this ViewAncestor provides to the
     * AccessibilityManagerService to the latter can interact with
     * the view hierarchy in this ViewAncestor.
     */
    static final class AccessibilityInteractionConnection
            extends IAccessibilityInteractionConnection.Stub {
        private final WeakReference<ViewRootImpl> mViewRootImpl;

        AccessibilityInteractionConnection(ViewRootImpl viewRootImpl) {
            mViewRootImpl = new WeakReference<ViewRootImpl>(viewRootImpl);
        }

        @Override
        public void findAccessibilityNodeInfoByAccessibilityId(long accessibilityNodeId,
                                                               Region interactiveRegion, int interactionId,
                                                               IAccessibilityInteractionConnectionCallback callback, int flags,
                                                               int interrogatingPid, long interrogatingTid, MagnificationSpec spec) {
            ViewRootImpl viewRootImpl = mViewRootImpl.get();
            if (viewRootImpl != null && viewRootImpl.mView != null) {
                viewRootImpl.getAccessibilityInteractionController()
                        .findAccessibilityNodeInfoByAccessibilityIdClientThread(accessibilityNodeId,
                                interactiveRegion, interactionId, callback, flags, interrogatingPid,
                                interrogatingTid, spec);
            } else {
                // We cannot make the call and notify the caller so it does not wait.
                try {
                    callback.setFindAccessibilityNodeInfosResult(null, interactionId);
                } catch (RemoteException re) {
                    /* best effort - ignore */
                }
            }
        }

        @Override
        public void performAccessibilityAction(long accessibilityNodeId, int action,
                                               Bundle arguments, int interactionId,
                                               IAccessibilityInteractionConnectionCallback callback, int flags,
                                               int interrogatingPid, long interrogatingTid) {
            ViewRootImpl viewRootImpl = mViewRootImpl.get();
            if (viewRootImpl != null && viewRootImpl.mView != null) {
                viewRootImpl.getAccessibilityInteractionController()
                        .performAccessibilityActionClientThread(accessibilityNodeId, action, arguments,
                                interactionId, callback, flags, interrogatingPid, interrogatingTid);
            } else {
                // We cannot make the call and notify the caller so it does not wait.
                try {
                    callback.setPerformAccessibilityActionResult(false, interactionId);
                } catch (RemoteException re) {
                    /* best effort - ignore */
                }
            }
        }

        @Override
        public void findAccessibilityNodeInfosByViewId(long accessibilityNodeId,
                                                       String viewId, Region interactiveRegion, int interactionId,
                                                       IAccessibilityInteractionConnectionCallback callback, int flags,
                                                       int interrogatingPid, long interrogatingTid, MagnificationSpec spec) {
            ViewRootImpl viewRootImpl = mViewRootImpl.get();
            if (viewRootImpl != null && viewRootImpl.mView != null) {
                viewRootImpl.getAccessibilityInteractionController()
                        .findAccessibilityNodeInfosByViewIdClientThread(accessibilityNodeId,
                                viewId, interactiveRegion, interactionId, callback, flags,
                                interrogatingPid, interrogatingTid, spec);
            } else {
                // We cannot make the call and notify the caller so it does not wait.
                try {
                    callback.setFindAccessibilityNodeInfoResult(null, interactionId);
                } catch (RemoteException re) {
                    /* best effort - ignore */
                }
            }
        }

        @Override
        public void findAccessibilityNodeInfosByText(long accessibilityNodeId, String text,
                                                     Region interactiveRegion, int interactionId,
                                                     IAccessibilityInteractionConnectionCallback callback, int flags,
                                                     int interrogatingPid, long interrogatingTid, MagnificationSpec spec) {
            ViewRootImpl viewRootImpl = mViewRootImpl.get();
            if (viewRootImpl != null && viewRootImpl.mView != null) {
                viewRootImpl.getAccessibilityInteractionController()
                        .findAccessibilityNodeInfosByTextClientThread(accessibilityNodeId, text,
                                interactiveRegion, interactionId, callback, flags, interrogatingPid,
                                interrogatingTid, spec);
            } else {
                // We cannot make the call and notify the caller so it does not wait.
                try {
                    callback.setFindAccessibilityNodeInfosResult(null, interactionId);
                } catch (RemoteException re) {
                    /* best effort - ignore */
                }
            }
        }

        @Override
        public void findFocus(long accessibilityNodeId, int focusType, Region interactiveRegion,
                              int interactionId, IAccessibilityInteractionConnectionCallback callback, int flags,
                              int interrogatingPid, long interrogatingTid, MagnificationSpec spec) {
            ViewRootImpl viewRootImpl = mViewRootImpl.get();
            if (viewRootImpl != null && viewRootImpl.mView != null) {
                viewRootImpl.getAccessibilityInteractionController()
                        .findFocusClientThread(accessibilityNodeId, focusType, interactiveRegion,
                                interactionId, callback, flags, interrogatingPid, interrogatingTid,
                                spec);
            } else {
                // We cannot make the call and notify the caller so it does not wait.
                try {
                    callback.setFindAccessibilityNodeInfoResult(null, interactionId);
                } catch (RemoteException re) {
                    /* best effort - ignore */
                }
            }
        }

        @Override
        public void focusSearch(long accessibilityNodeId, int direction, Region interactiveRegion,
                                int interactionId, IAccessibilityInteractionConnectionCallback callback, int flags,
                                int interrogatingPid, long interrogatingTid, MagnificationSpec spec) {
            ViewRootImpl viewRootImpl = mViewRootImpl.get();
            if (viewRootImpl != null && viewRootImpl.mView != null) {
                viewRootImpl.getAccessibilityInteractionController()
                        .focusSearchClientThread(accessibilityNodeId, direction, interactiveRegion,
                                interactionId, callback, flags, interrogatingPid, interrogatingTid,
                                spec);
            } else {
                // We cannot make the call and notify the caller so it does not wait.
                try {
                    callback.setFindAccessibilityNodeInfoResult(null, interactionId);
                } catch (RemoteException re) {
                    /* best effort - ignore */
                }
            }
        }
    }

    private class SendWindowContentChangedAccessibilityEvent implements Runnable {
        private int mChangeTypes = 0;

        public View mSource;
        public long mLastEventTimeMillis;

        @Override
        public void run() {
            // The accessibility may be turned off while we were waiting so check again.
            if (AccessibilityManager.getInstance(mContext).isEnabled()) {
                mLastEventTimeMillis = SystemClock.uptimeMillis();
                AccessibilityEvent event = AccessibilityEvent.obtain();
                event.setEventType(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
                event.setContentChangeTypes(mChangeTypes);
                mSource.sendAccessibilityEventUnchecked(event);
            } else {
                mLastEventTimeMillis = 0;
            }
            // In any case reset to initial state.
            mSource.resetSubtreeAccessibilityStateChanged();
            mSource = null;
            mChangeTypes = 0;
        }

        public void runOrPost(View source, int changeType) {
            if (mSource != null) {
                // If there is no common predecessor, then mSource points to
                // a removed view, hence in this case always prefer the source.
                View predecessor = getCommonPredecessor(mSource, source);
                mSource = (predecessor != null) ? predecessor : source;
                mChangeTypes |= changeType;
                return;
            }
            mSource = source;
            mChangeTypes = changeType;
            final long timeSinceLastMillis = SystemClock.uptimeMillis() - mLastEventTimeMillis;
            final long minEventIntevalMillis =
                    ViewConfiguration.getSendRecurringAccessibilityEventsInterval();
            if (timeSinceLastMillis >= minEventIntevalMillis) {
                mSource.removeCallbacks(this);
                run();
            } else {
                mSource.postDelayed(this, minEventIntevalMillis - timeSinceLastMillis);
            }
        }
    }
}

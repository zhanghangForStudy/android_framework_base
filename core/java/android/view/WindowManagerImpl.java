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

import android.annotation.NonNull;
import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.internal.os.IResultReceiver;

import java.util.List;

/**
 * Provides low-level communication with the system window manager for
 * operations that are bound to a particular（特别的，详尽的） context, display or parent window.
 * Instances of this object are sensitive(灵敏的、敏感的) to the compatibility（兼容、通用） info associated
 * with the running application.
 * <p>
 * <p>提供了与系统窗口管理者进行的一种底层交流，这种交流包含了限定于特别的上下午、显示器以及父窗口等相关的操作。
 * 此对象的实例对于与当前应用相关的兼容信息十分的敏感
 * <p>
 * This object implements the {@link ViewManager} interface,
 * allowing you to add any View subclass as a top-level window on the screen.
 * Additional window manager specific layout parameters are defined for
 * control over how windows are displayed.  It also implements the {@link WindowManager}
 * interface, allowing you to control the displays attached to the device.
 * <p>此对象执行了{@link ViewManager}接口，运行添加任何View的子类作为屏幕上的顶级窗口。
 * 可扩展的特属于窗口管理的布局参数被定义，用来控制窗口是如何显示的。
 * 此对象也实现了{@link WindowManager}接口，运行控制粘贴在设备上的显示器。
 * <p>Applications will not normally use WindowManager directly, instead relying
 * on the higher-level facilities in {@link android.app.Activity} and
 * {@link android.app.Dialog}.
 * <p>应用一般不会直接使用WindowManager，而是依赖于更高层的工具，例如activity与dialog
 * <p>Even for low-level window manager access, it is almost never correct to use
 * this class.  For example, {@link android.app.Activity#getWindowManager}
 * provides a window manager for adding windows that are associated with that
 * activity -- the window manager will not normally allow you to add arbitrary（任意的、随意的）
 * windows that are not associated with an activity.
 * 即使对底层的WM访问，也几乎从不使用此类。例如{@link android.app.Activity#getWindowManager}
 * 提供了一个窗口管理器，用来添加与此activity相关的窗口--这个窗口管理一般不会允许添加任意的、与
 * 此activity没有联系的窗口
 *
 * @hide
 * @see WindowManager
 * @see WindowManagerGlobal
 */
public final class WindowManagerImpl implements WindowManager {
    private final WindowManagerGlobal mGlobal = WindowManagerGlobal.getInstance();
    private final Context mContext;

    /**
     * 父窗口
     */
    private final Window mParentWindow;

    private IBinder mDefaultToken;

    public WindowManagerImpl(Context context) {
        this(context, null);
    }

    private WindowManagerImpl(Context context, Window parentWindow) {
        mContext = context;
        mParentWindow = parentWindow;
    }

    public WindowManagerImpl createLocalWindowManager(Window parentWindow) {
        return new WindowManagerImpl(mContext, parentWindow);
    }

    public WindowManagerImpl createPresentationWindowManager(Context displayContext) {
        return new WindowManagerImpl(displayContext, mParentWindow);
    }

    /**
     * Sets the window token to assign when none is specified by the client or
     * available from the parent window.
     * 设置窗口的远程引用，要么由客户端指定，要么来自于父窗口
     *
     * @param token The default token to assign.
     */
    public void setDefaultToken(IBinder token) {
        mDefaultToken = token;
    }

    @Override
    public void addView(@NonNull View view, @NonNull ViewGroup.LayoutParams params) {
        applyDefaultToken(params);
        mGlobal.addView(view, params, mContext.getDisplay(), mParentWindow);
    }

    @Override
    public void updateViewLayout(@NonNull View view, @NonNull ViewGroup.LayoutParams params) {
        applyDefaultToken(params);
        mGlobal.updateViewLayout(view, params);
    }

    // 如果布局参数之中的token为空，则将默认的token赋值给布局参数之中的token
    private void applyDefaultToken(@NonNull ViewGroup.LayoutParams params) {
        // Only use the default token if we don't have a parent window.
        // 如果没有父窗口，直接使用默认的token
        if (mDefaultToken != null && mParentWindow == null) {
            if (!(params instanceof WindowManager.LayoutParams)) {
                throw new IllegalArgumentException("Params must be WindowManager.LayoutParams");
            }

            // Only use the default token if we don't already have a token.
            final WindowManager.LayoutParams wparams = (WindowManager.LayoutParams) params;
            if (wparams.token == null) {
                wparams.token = mDefaultToken;
            }
        }
    }

    @Override
    public void removeView(View view) {
        mGlobal.removeView(view, false);
    }

    @Override
    public void removeViewImmediate(View view) {
        mGlobal.removeView(view, true);
    }

    @Override
    public void requestAppKeyboardShortcuts(
            final KeyboardShortcutsReceiver receiver, int deviceId) {
        IResultReceiver resultReceiver = new IResultReceiver.Stub() {
            @Override
            public void send(int resultCode, Bundle resultData) throws RemoteException {
                List<KeyboardShortcutGroup> result =
                        resultData.getParcelableArrayList(PARCEL_KEY_SHORTCUTS_ARRAY);
                receiver.onKeyboardShortcutsReceived(result);
            }
        };
        try {
            WindowManagerGlobal.getWindowManagerService()
                    .requestAppKeyboardShortcuts(resultReceiver, deviceId);
        } catch (RemoteException e) {
        }
    }

    @Override
    public Display getDefaultDisplay() {
        return mContext.getDisplay();
    }
}

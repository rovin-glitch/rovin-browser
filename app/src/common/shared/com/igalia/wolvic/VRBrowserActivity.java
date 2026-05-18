/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic;

import static com.igalia.wolvic.ui.widgets.UIWidget.REMOVE_WIDGET;

import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentController;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleOwnerKt;
import androidx.lifecycle.LifecycleRegistry;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModelStore;
import androidx.lifecycle.ViewModelStoreOwner;
import androidx.preference.PreferenceManager;

import com.igalia.wolvic.audio.AndroidMediaPlayer;
import com.igalia.wolvic.audio.AndroidMediaPlayerSoundPool;
import com.igalia.wolvic.audio.AudioEngine;
import com.igalia.wolvic.browser.Accounts;
import com.igalia.wolvic.browser.Media;
import com.igalia.wolvic.browser.PermissionDelegate;
import com.igalia.wolvic.browser.SettingsStore;
import com.igalia.wolvic.browser.api.WRuntime;
import com.igalia.wolvic.browser.api.WSession;
import com.igalia.wolvic.browser.engine.EngineProvider;
import com.igalia.wolvic.browser.engine.Session;
import com.igalia.wolvic.browser.engine.SessionStore;
import com.igalia.wolvic.crashreporting.CrashReporterService;
import com.igalia.wolvic.crashreporting.GlobalExceptionHandler;
import com.igalia.wolvic.geolocation.GeolocationWrapper;
import com.igalia.wolvic.input.MotionEventGenerator;
import com.igalia.wolvic.search.SearchEngineWrapper;
import com.igalia.wolvic.speech.SpeechRecognizer;
import com.igalia.wolvic.speech.SpeechServices;
import com.igalia.wolvic.telemetry.OpenTelemetry;
import com.igalia.wolvic.telemetry.TelemetryService;
import com.igalia.wolvic.ui.OffscreenDisplay;
import com.igalia.wolvic.ui.adapters.Language;
import com.igalia.wolvic.ui.widgets.AbstractTabsBar;
import com.igalia.wolvic.ui.widgets.AppServicesProvider;
import com.igalia.wolvic.ui.widgets.HorizontalTabsBar;
import com.igalia.wolvic.ui.widgets.KeyboardWidget;
import com.igalia.wolvic.ui.widgets.NavigationBarWidget;
import com.igalia.wolvic.ui.widgets.OverlayContentWidget;
import com.igalia.wolvic.ui.widgets.RootWidget;
import com.igalia.wolvic.ui.widgets.TrayWidget;
import com.igalia.wolvic.ui.widgets.UISurfaceTextureRenderer;
import com.igalia.wolvic.ui.widgets.UIWidget;
import com.igalia.wolvic.ui.widgets.VerticalTabsBar;
import com.igalia.wolvic.ui.widgets.WebXRInterstitialWidget;
import com.igalia.wolvic.ui.widgets.Widget;
import com.igalia.wolvic.ui.widgets.WidgetManagerDelegate;
import com.igalia.wolvic.ui.widgets.WidgetPlacement;
import com.igalia.wolvic.ui.widgets.WindowWidget;
import com.igalia.wolvic.ui.widgets.Windows;
import com.igalia.wolvic.ui.widgets.dialogs.CrashDialogWidget;
import com.igalia.wolvic.ui.widgets.dialogs.LegalDocumentDialogWidget;
import com.igalia.wolvic.ui.widgets.dialogs.PromptDialogWidget;
import com.igalia.wolvic.ui.widgets.dialogs.RovinLandingDialogWidget;
import com.igalia.wolvic.ui.widgets.dialogs.SendTabDialogWidget;
import com.igalia.wolvic.ui.widgets.dialogs.WhatsNewWidget;
import com.igalia.wolvic.ui.widgets.menus.VideoProjectionMenuWidget;
import com.igalia.wolvic.utils.BitmapCache;
import com.igalia.wolvic.utils.ConnectivityReceiver;
import com.igalia.wolvic.utils.DeviceType;
import com.igalia.wolvic.utils.LocaleUtils;
import com.igalia.wolvic.utils.RovinAssetHttpServer;
import com.igalia.wolvic.utils.StringUtils;
import com.igalia.wolvic.utils.SystemUtils;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import kotlinx.coroutines.CoroutineScope;

public class VRBrowserActivity extends PlatformActivity implements WidgetManagerDelegate,
        ComponentCallbacks2, LifecycleOwner, ViewModelStoreOwner, SharedPreferences.OnSharedPreferenceChangeListener,
        PlatformActivityPlugin.PlatformActivityPluginListener {

    public static final String CUSTOM_URI_SCHEME = "wolvic";
    public static final String CUSTOM_URI_HOST = "com.igalia.wolvic";
    public static final String EXTRA_INTENT_CMD = "intent_cmd";
    public static final String JSON_OVR_SOCIAL_LAUNCH = "ovr_social_launch";
    public static final String JSON_DEEPLINK_MESSAGE = "deeplink_message";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_OPEN_IN_BACKGROUND = "background";
    public static final String EXTRA_CREATE_NEW_WINDOW = "create_new_window";
    public static final String EXTRA_HIDE_WEBXR_INTERSTITIAL = "hide_webxr_interstitial";
    public static final String EXTRA_HIDE_WHATS_NEW = "hide_whats_new";
    public static final String EXTRA_KIOSK = "kiosk";
    private static final String ROVIN_PAUSE_BRIDGE_QUERY_PARAM = "rovinPauseBridge";
    private static final String ROVIN_NATIVE_HAPTICS_BRIDGE_QUERY_PARAM = "rovinNativeHaptics";
    private static final String ROVIN_SAVE_BRIDGE_QUERY_PARAM = "rovinSaveBridge";
    private static final String ROVIN_RUNTIME_QUERY_PARAM = "rovinRuntime";
    private static final String ROVIN_RENDER_SCALE_QUERY_PARAM = "renderScale";
    private static final String ROVIN_RENDER_SCALE_DEFAULT = "1.3";
    private static final long BATTERY_UPDATE_INTERVAL = 60 * 1_000_000_000L; // 60 seconds

    private boolean mLaunchImmersive = false;
    private boolean mRovinReady = false;
    private boolean mHasAttemptedInitialRelaunch = false;
    private boolean mIsLaunchingVr = false;
    private boolean mRovinImmersiveActiveConfirmed = false;
    private boolean mRovinTransitionTriggered = false;
    private boolean mRovinNativeVrFocused = false;
    private boolean mRovinPendingNativeFocusStart = false;
    private int mRovinXrStartFailureCount = 0;
    private Handler mRovinStartupBridgeHandler = null;
    private Runnable mPendingRovinStartupBridge = null;
    private Runnable mPendingRovinWarmResumeFallback = null;
    public static final String EXTRA_LAUNCH_IMMERSIVE = "launch_immersive";
    private static final int ROVIN_STARTUP_POLLING_INTERVAL_MS = 1000;
    private static final int ROVIN_STARTUP_MAX_ATTEMPTS = 10;
    private static final String ROVIN_READY_TITLE_MARKER = "__rovin_ready__";
    private static final String ROVIN_POLL_LOADED_JS = "javascript:void(function(){var loaded=window.__rovin_is_fully_loaded__===true;var stage=window.__rovin_boot_stage__||'unset';var error=window.__rovin_boot_error__||'';window.prompt('__rovin_is_fully_loaded__',loaded?'true':('false|'+stage+(error?'|'+error:'')));}())";
    private static final String ROVIN_TRIGGER_START_JS = "javascript:void(function(){if(window.triggerStartCta){window.triggerStartCta('native-cold-boot');}else{window.prompt('__rovin_log__:Native trigger failed: triggerStartCta missing','ok');}}())";
    private static final String ROVIN_TRIGGER_RESUME_JS = "javascript:void(function(){if(window.rovin_auto_resume){window.rovin_auto_resume('native-warm-resume');}else if(window.triggerStartCta){window.triggerStartCta('native-warm-resume');}else{window.prompt('__rovin_log__:Native warm resume failed: startup bridge missing','ok');}}())";
    // Element where a click would be simulated to launch the WebXR experience.
    public static final String EXTRA_LAUNCH_IMMERSIVE_PARENT_XPATH = "launch_immersive_parent_xpath";
    public static final String EXTRA_LAUNCH_IMMERSIVE_ELEMENT_XPATH = "launch_immersive_element_xpath";
    private static final long ROVIN_AUTO_LAUNCH_DELAY_MS = 200L;
    private static final long ROVIN_NATIVE_FOCUS_START_SETTLE_DELAY_MS = 2500L;
    private static final long ROVIN_XR_START_RETRY_DELAY_MS = 5000L;
    private static final int ROVIN_XR_START_MAX_FAILURES = 8;
    private static final long ROVIN_WARM_RESUME_FALLBACK_DELAY_MS = 12000L;
    private static final long[] ROVIN_INPUT_PRIME_DELAYS_MS = {0L, 250L, 1000L};
    private static final String ROVIN_APP_BUTTON_JS = "javascript:(function(){window.dispatchEvent(new CustomEvent('rovin-app-button'));})();";
    private static final String ROVIN_APP_FOCUS_LOST_JS = "javascript:(function(){window.dispatchEvent(new CustomEvent('rovin-app-focus-lost'));})();";
    private static final String ROVIN_APP_FOCUS_GAINED_JS = "javascript:(function(){window.dispatchEvent(new CustomEvent('rovin-app-focus-gained'));})();";

    private static class RovinLaunchTarget {
        final String url;
        final String statusMessage;
        final boolean bundledPreferred;
        final boolean bundledAvailable;
        final boolean valid;

        private RovinLaunchTarget(String url, String statusMessage, boolean bundledPreferred, boolean bundledAvailable,
                boolean valid) {
            this.url = url;
            this.statusMessage = statusMessage;
            this.bundledPreferred = bundledPreferred;
            this.bundledAvailable = bundledAvailable;
            this.valid = valid;
        }

        static RovinLaunchTarget ready(String url, String statusMessage, boolean bundledPreferred,
                boolean bundledAvailable) {
            return new RovinLaunchTarget(url, statusMessage, bundledPreferred, bundledAvailable, true);
        }

        static RovinLaunchTarget error(String statusMessage, boolean bundledPreferred, boolean bundledAvailable) {
            return new RovinLaunchTarget(null, statusMessage, bundledPreferred, bundledAvailable, false);
        }
    }

    private boolean shouldRestoreHeadLockOnVRVideoExit;

    private BroadcastReceiver mCrashReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ((intent.getAction() != null) && intent.getAction().equals(CrashReporterService.CRASH_ACTION)) {
                Intent crashIntent;
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.S_V2) {
                    crashIntent = intent.getParcelableExtra(CrashReporterService.DATA_TAG, Intent.class);
                } else {
                    crashIntent = intent.getParcelableExtra(CrashReporterService.DATA_TAG);
                }
                handleContentCrashIntent(crashIntent);
            }
        }
    };

    private LifecycleRegistry mLifeCycle;

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        // Ensure that mLifeCycle is initialized, because this method
        // may be called early by a superclass at construction time.
        return getLifecycleRegistry();
    }

    private LifecycleRegistry getLifecycleRegistry() {
        if (mLifeCycle == null) {
            mLifeCycle = new LifecycleRegistry(this);
        }
        return mLifeCycle;
    }

    public CoroutineScope getCoroutineScope() {
        return LifecycleOwnerKt.getLifecycleScope(this);
    }

    private final ViewModelStore mViewModelStore;

    @NonNull
    @Override
    public ViewModelStore getViewModelStore() {
        return mViewModelStore;
    }

    public VRBrowserActivity() {
        getLifecycleRegistry().setCurrentState(Lifecycle.State.INITIALIZED);

        mViewModelStore = new ViewModelStore();
    }

    class SwipeRunnable implements Runnable {
        boolean mCanceled = false;

        @Override
        public void run() {
            if (!mCanceled) {
                mLastGesture = NoGesture;
            }
        }
    }

    // Used to load the 'native-lib' library on application startup.
    static {
        System.loadLibrary("native-lib");
    }

    static final int NoGesture = -1;
    static final int GestureSwipeLeft = 0;
    static final int GestureSwipeRight = 1;
    private int mLastGesture = NoGesture;

    private int mProbeAttempts = 0;
    static final int SwipeDelay = 1000; // milliseconds
    static final long RESET_CRASH_COUNT_DELAY = 5000;
    static final int UPDATE_NATIVE_WIDGETS_DELAY = 50; // milliseconds

    static final String LOGTAG = "VRB"; // Forced to VRB for release visibility
    ConcurrentHashMap<Integer, Widget> mWidgets;
    private int mWidgetHandleIndex = 1;
    AudioEngine mAudioEngine;
    OffscreenDisplay mOffscreenDisplay;
    FrameLayout mWidgetContainer;
    SwipeRunnable mLastRunnable;
    Handler mHandler = new Handler(Looper.getMainLooper());
    Runnable mAudioUpdateRunnable;
    Windows mWindows;
    RootWidget mRootWidget;
    KeyboardWidget mKeyboard;
    NavigationBarWidget mNavigationBar;
    AbstractTabsBar mTabsBar;
    CrashDialogWidget mCrashDialog;
    TrayWidget mTray;
    WhatsNewWidget mWhatsNewWidget = null;
    RovinLandingDialogWidget mRovinLandingWidget = null;
    WebXRInterstitialWidget mWebXRInterstitial;
    PermissionDelegate mPermissionDelegate;
    LinkedList<UpdateListener> mWidgetUpdateListeners;
    LinkedList<PermissionListener> mPermissionListeners;
    LinkedList<FocusChangeListener> mFocusChangeListeners;
    LinkedList<WorldClickListener> mWorldClickListeners;
    LinkedList<WebXRListener> mWebXRListeners;
    LinkedList<Runnable> mBackHandlers;
    private final MutableLiveData<Boolean> mIsPresentingImmersive = new MutableLiveData<>(false);
    private boolean mIsBackgrounding = false;
    private Thread mUiThread;
    private LinkedList<Pair<Object, Float>> mBrightnessQueue;
    private Pair<Object, Float> mCurrentBrightness;
    private SearchEngineWrapper mSearchEngineWrapper;
    private SettingsStore mSettings;
    private SharedPreferences mPrefs;
    private boolean mConnectionAvailable = true;
    private Widget mActiveDialog;
    private Set<String> mPoorPerformanceAllowList;
    private float mCurrentCylinderDensity = 0;
    private static final boolean ROVIN_DISABLE_WEBXR_INTERSTITIAL = true;
    private boolean mHideWebXRIntersitial = true;
    private FragmentController mFragmentController;
    private LinkedHashMap<Integer, WidgetPlacement> mPendingNativeWidgetUpdates = new LinkedHashMap<>();
    private ScheduledThreadPoolExecutor mPendingNativeWidgetUpdatesExecutor = new ScheduledThreadPoolExecutor(1);
    private ScheduledFuture<?> mNativeWidgetUpdatesTask = null;
    private Media mPrevActiveMedia = null;
    private boolean mIsPassthroughEnabled = false;
    private boolean mIsHandTrackingEnabled = true;
    private boolean mIsHandTrackingSupported = false;
    private boolean mAreControllersAvailable = false;
    private long mLastBatteryUpdate = System.nanoTime();
    private int mLastBatteryLevel = -1;
    private PlatformActivityPlugin mPlatformPlugin;
    private int mLastMotionEventWidgetHandle;
    private boolean mIsEyeTrackingSupported;
    private String mImmersiveParentElementXPath;
    private String mImmersiveTargetElementXPath;
    private OptionalInt mMaxCompositionLayers = OptionalInt.empty();
    private LinkedList<CheckCompositionLayersCallback> mCompositionLayersPendingCallbacks;
    private Runnable mPendingRovinAutoLaunch;
    private RovinAssetHttpServer mRovinAssetHttpServer;
    private Handler mStartupPollingHandler;
    private Runnable mStartupPollingRunnable;
    private int mStartupPollingCount = 0;

    private ViewTreeObserver.OnGlobalFocusChangeListener globalFocusListener = new ViewTreeObserver.OnGlobalFocusChangeListener() {
        @Override
        public void onGlobalFocusChanged(View oldFocus, View newFocus) {
            Log.d(LOGTAG, "======> OnGlobalFocusChangeListener: old(" + oldFocus + ") new(" + newFocus + ")");
            // TODO: Which controller should we send the haptic feedback to ?
            triggerHapticFeedback(0);
            for (FocusChangeListener listener : mFocusChangeListeners) {
                listener.onGlobalFocusChanged(oldFocus, newFocus);
            }
        }
    };

    @Override
    protected void attachBaseContext(Context base) {
        Context newContext = LocaleUtils.init(base);
        super.attachBaseContext(newContext);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        mFragmentController = FragmentController
                .createController(new FragmentControllerCallbacks(this, new Handler(Looper.getMainLooper()), 0));
        mFragmentController.attachHost(null);
        mFragmentController.dispatchActivityCreated();

        SettingsStore.getInstance(getBaseContext()).setPid(Process.myPid());
        ((VRBrowserApplication) getApplication()).onActivityCreate(this);

        if (!DeviceType.isHVRBuild() && SettingsStore.getInstance(getBaseContext()).isTelemetryEnabled()) {
            TelemetryService.setService(new OpenTelemetry(getApplication()));
        }

        // Fix for infinite restart on startup crashes.
        long count = SettingsStore.getInstance(getBaseContext()).getCrashRestartCount();
        boolean cancelRestart = count > CrashReporterService.MAX_RESTART_COUNT;
        if (cancelRestart) {
            super.onCreate(savedInstanceState);
            Log.e(LOGTAG, "Cancel Restart");
            finish();
            return;
        }
        SettingsStore.getInstance(getBaseContext()).incrementCrashRestartCount();
        mHandler.postDelayed(() -> SettingsStore.getInstance(getBaseContext()).resetCrashRestartCount(),
                RESET_CRASH_COUNT_DELAY);
        // Set a global exception handler as soon as possible
        GlobalExceptionHandler.register(this.getApplicationContext());

        if (DeviceType.isOculusBuild()) {
            workaroundGeckoSigAction();
        }
        mUiThread = Thread.currentThread();

        BitmapCache.getInstance(this).onCreate();

        WRuntime runtime = EngineProvider.INSTANCE.getOrCreateRuntime(this);
        runtime.appendAppNotesToCrashReport("Wolvic " + BuildConfig.VERSION_NAME + "-" + BuildConfig.VERSION_CODE + "-"
                + BuildConfig.FLAVOR + "-" + BuildConfig.BUILD_TYPE + " (" + BuildConfig.GIT_HASH + ")");

        // Create broadcast receiver for getting crash messages from crash process
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(CrashReporterService.CRASH_ACTION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            registerReceiver(mCrashReceiver, intentFilter,
                    BuildConfig.APPLICATION_ID + "." + getString(R.string.app_permission_name), null,
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mCrashReceiver, intentFilter,
                    BuildConfig.APPLICATION_ID + "." + getString(R.string.app_permission_name), null);
        }

        mLaunchImmersive = false;
        mLastGesture = NoGesture;
        mWidgetUpdateListeners = new LinkedList<>();
        mPermissionListeners = new LinkedList<>();
        mFocusChangeListeners = new LinkedList<>();
        mWorldClickListeners = new LinkedList<>();
        mWebXRListeners = new LinkedList<>();
        mBackHandlers = new LinkedList<>();
        mBrightnessQueue = new LinkedList<>();
        mCompositionLayersPendingCallbacks = new LinkedList<>();
        mCurrentBrightness = Pair.create(null, 1.0f);
        mWidgets = new ConcurrentHashMap<>();

        mIsPresentingImmersive.observe(this, this::onPresentingImmersiveChange);

        super.onCreate(savedInstanceState);

        mWidgetContainer = new FrameLayout(this);
        runtime.setContainerView(mWidgetContainer);

        mPermissionDelegate = new PermissionDelegate(this, this);

        mAudioEngine = new AudioEngine(this,
                BuildConfig.USE_SOUNDPOOL
                        ? new AndroidMediaPlayerSoundPool(getBaseContext())
                        : new AndroidMediaPlayer(getBaseContext()));
        mAudioEngine.setEnabled(SettingsStore.getInstance(this).isAudioEnabled());
        mAudioEngine.preloadAsync(() -> {
            Log.i(LOGTAG, "AudioEngine sounds preloaded!");
            // mAudioEngine.playSound(AudioEngine.Sound.AMBIENT, true);
        });
        mAudioUpdateRunnable = () -> mAudioEngine.update();

        mSettings = SettingsStore.getInstance(this);
        mSettings.initModel(this);
        mSettings.setEnvironmentOverrideEnabled(false);
        mSettings.setEnvironment(SettingsStore.ENV_DEFAULT);
        mSettings.setTermsServiceAccepted(true);
        mSettings.setPrivacyPolicyAccepted(true);

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        mPrefs.registerOnSharedPreferenceChangeListener(this);

        queueRunnable(() -> {
            createOffscreenDisplay();
            createCaptureSurface();
        });
        final String tempPath = getCacheDir().getAbsolutePath();
        queueRunnable(() -> setTemporaryFilePath(tempPath));

        initializeWidgets();

        loadFromIntent(getIntent());

        // Setup the search engine
        mSearchEngineWrapper = SearchEngineWrapper.get(this);
        mSearchEngineWrapper.registerForUpdates();

        getServicesProvider().getConnectivityReceiver().addListener(mConnectivityDelegate);

        if (RovinProduct.shouldInitializeGeolocation()) {
            GeolocationWrapper.INSTANCE.update(this);
        }

        if (RovinProduct.shouldInitializeSpeechRecognizer()) {
            initializeSpeechRecognizer();
        } else {
            ((VRBrowserApplication) getApplication()).setSpeechRecognizer(null);
        }

        mPoorPerformanceAllowList = new HashSet<>();

        // FIXME: We don't have any crash report analysis tool, so we need to disable
        // this for the time being.
        if (false)
            checkForCrash();

        setLockMode(mSettings.isHeadLockEnabled() ? WidgetManagerDelegate.HEAD_LOCK : WidgetManagerDelegate.NO_LOCK);
        if (mSettings.getPointerMode() == WidgetManagerDelegate.TRACKED_EYE)
            checkEyeTrackingPermissions(aPermissionGranted -> setPointerMode(
                    aPermissionGranted ? WidgetManagerDelegate.TRACKED_EYE : WidgetManagerDelegate.TRACKED_POINTER));
        else
            setPointerMode(mSettings.getPointerMode());

        // Show the launch dialogs, if needed.
        if (!showTermsServiceDialogIfNeeded()) {
            if (!showPrivacyDialogIfNeeded()) {
                showWhatsNewDialogIfNeeded();
            }
        }

        getLifecycleRegistry().setCurrentState(Lifecycle.State.CREATED);
    }

    protected void initializeWidgets() {
        UISurfaceTextureRenderer.setRenderActive(true);

        // Empty widget just for handling focus on empty space
        mRootWidget = new RootWidget(this);
        mRootWidget.setClickCallback(() -> {
            for (WorldClickListener listener : mWorldClickListeners) {
                listener.onWorldClick();
            }
        });

        // Create Browser navigation widget
        mNavigationBar = new NavigationBarWidget(this);

        // Create keyboard widget
        mKeyboard = new KeyboardWidget(this);

        if (!ROVIN_DISABLE_WEBXR_INTERSTITIAL) {
            mWebXRInterstitial = new WebXRInterstitialWidget(this);
        }

        // Windows
        mWindows = new Windows(this);
        mWindows.setDelegate(new Windows.Delegate() {
            @Override
            public void onFocusedWindowChanged(@NonNull WindowWidget aFocusedWindow,
                    @Nullable WindowWidget aPrevFocusedWindow) {
                attachToWindow(aFocusedWindow, aPrevFocusedWindow);
                mTray.setAddWindowVisible(mWindows.canOpenNewWindow());
                mNavigationBar.hideAllNotifications();
            }

            @Override
            public void onWindowBorderChanged(@NonNull WindowWidget aChangeWindow) {
                mKeyboard.proxifyLayerIfNeeded(mWindows.getCurrentWindows());
            }

            @Override
            public void onWindowsMoved() {
                mNavigationBar.hideAllNotifications();
                updateWidget(mTray);
            }

            @Override
            public void onWindowClosed() {
                mTray.setAddWindowVisible(mWindows.canOpenNewWindow());
                mNavigationBar.hideAllNotifications();
                updateWidget(mTray);
            }

            @Override
            public void onWindowVideoAvailabilityChanged(@NonNull WindowWidget aWindow) {
                @CPULevelFlags
                int cpuLevel = mWindows.isVideoAvailable() ? WidgetManagerDelegate.CPU_LEVEL_HIGH
                        : WidgetManagerDelegate.CPU_LEVEL_NORMAL;

                queueRunnable(() -> setCPULevelNative(cpuLevel));

                if (mPlatformPlugin != null) {
                    mPlatformPlugin.onVideoAvailabilityChange();
                }
            }

            @Override
            public void onIsWindowFullscreenChanged(boolean isFullscreen) {
                if (mPlatformPlugin != null) {
                    mPlatformPlugin.onIsFullscreenChange(isFullscreen);
                }
            }
        });

        // Create the tray
        mTray = new TrayWidget(this);
        mTray.addListeners(mWindows);
        mTray.setAddWindowVisible(mWindows.canOpenNewWindow());

        // Create Tabs bar widget
        if (mSettings.getTabsLocation() == SettingsStore.TABS_LOCATION_HORIZONTAL) {
            mTabsBar = new HorizontalTabsBar(this, mWindows);
        } else if (mSettings.getTabsLocation() == SettingsStore.TABS_LOCATION_VERTICAL) {
            mTabsBar = new VerticalTabsBar(this, mWindows);
        } else {
            mTabsBar = null;
        }

        attachToWindow(mWindows.getFocusedWindow(), null);

        if (mWebXRInterstitial != null) {
            addWidgets(Arrays.asList(mRootWidget, mNavigationBar, mKeyboard, mTray, mTabsBar, mWebXRInterstitial));
        } else {
            addWidgets(Arrays.asList(mRootWidget, mNavigationBar, mKeyboard, mTray, mTabsBar));
        }

        // Create the platform plugin after widgets are created to be extra safe.
        mPlatformPlugin = createPlatformPlugin(this);
        if (mPlatformPlugin != null)
            mPlatformPlugin.registerListener(this);

        mWindows.restoreSessions();
    }

    private void onPresentingImmersiveChange(boolean presenting) {
        if (mPlatformPlugin != null) {
            mPlatformPlugin.onIsPresentingImmersiveChange(presenting);
        }
    }

    private void attachToWindow(@NonNull WindowWidget aWindow, @Nullable WindowWidget aPrevWindow) {
        mPermissionDelegate.setParentWidgetHandle(aWindow.getHandle());
        mNavigationBar.attachToWindow(aWindow);
        mKeyboard.attachToWindow(aWindow);
        mTray.attachToWindow(aWindow);

        if (mTabsBar != null) {
            mTabsBar.attachToWindow(aWindow);
        }
        mWindows.adjustWindowOffsets();

        if (aPrevWindow != null) {
            updateWidget(mNavigationBar);
            updateWidget(mKeyboard);
            updateWidget(mTray);
            if (mTabsBar != null) {
                updateWidget(mTabsBar);
            }
        }
    }

    WRuntime.CrashReportIntent getCrashReportIntent() {
        return EngineProvider.INSTANCE.getOrCreateRuntime(this).getCrashReportIntent();
    }

    private void initializeSpeechRecognizer() {
        try {
            String speechService = SettingsStore.getInstance(this).getVoiceSearchService();
            SpeechRecognizer speechRecognizer = SpeechServices.getInstance(this, speechService);
            ((VRBrowserApplication) getApplication()).setSpeechRecognizer(speechRecognizer);
        } catch (Exception e) {
            Log.e(LOGTAG, "Exception creating the speech recognizer: " + e);
            ((VRBrowserApplication) getApplication()).setSpeechRecognizer(null);
        }
    }

    // Returns true if the dialog was shown, false otherwise.
    private boolean showTermsServiceDialogIfNeeded() {
        if (SettingsStore.getInstance(this).isTermsServiceAccepted()) {
            return false;
        }

        LegalDocumentDialogWidget termsServiceDialog = new LegalDocumentDialogWidget(this,
                LegalDocumentDialogWidget.LegalDocument.TERMS_OF_SERVICE);

        termsServiceDialog.setDelegate(response -> {
            if (response) {
                SettingsStore.getInstance(this).setTermsServiceAccepted(true);
                if (!showPrivacyDialogIfNeeded()) {
                    showWhatsNewDialogIfNeeded();
                }
            } else {
                // TODO ask for confirmation ("are you really sure that you want to close
                // Wolvic?")
                Log.w(LOGTAG, "The user rejected the privacy policy, closing the app.");
                finish();
            }
        });
        termsServiceDialog.attachToWindow(mWindows.getFocusedWindow());
        termsServiceDialog.show(UIWidget.REQUEST_FOCUS);
        return true;
    }

    // Returns true if the dialog was shown, false otherwise.
    private boolean showPrivacyDialogIfNeeded() {
        if (SettingsStore.getInstance(this).isPrivacyPolicyAccepted()) {
            return false;
        }

        LegalDocumentDialogWidget privacyPolicyDialog = new LegalDocumentDialogWidget(this,
                LegalDocumentDialogWidget.LegalDocument.PRIVACY_POLICY);
        privacyPolicyDialog.setDelegate(response -> {
            if (response) {
                SettingsStore.getInstance(this).setPrivacyPolicyAccepted(true);
                showWhatsNewDialogIfNeeded();
            } else {
                // TODO ask for confirmation ("are you really sure that you want to close
                // Wolvic?")
                Log.w(LOGTAG, "The user rejected the privacy policy, closing the app.");
                finish();
            }
        });
        privacyPolicyDialog.attachToWindow(mWindows.getFocusedWindow());
        privacyPolicyDialog.show(UIWidget.REQUEST_FOCUS);
        return true;
    }

    private void showWhatsNewDialogIfNeeded() {
        if (!RovinProduct.shouldShowWhatsNew()
                || SettingsStore.getInstance(this).isWhatsNewDisplayed() || mWindows.getFocusedWindow().isKioskMode()
                || BuildConfig.FLAVOR_backend.equals("chromium")) {
            return;
        }

        mWhatsNewWidget = new WhatsNewWidget(this);
        mWhatsNewWidget.setLoginOrigin(Accounts.LoginOrigin.NONE);
        mWhatsNewWidget.getPlacement().parentHandle = mWindows.getFocusedWindow().getHandle();
        mWhatsNewWidget.show(UIWidget.REQUEST_FOCUS);
    }

    @Override
    protected void onStart() {
        SettingsStore.getInstance(getBaseContext()).setPid(Process.myPid());
        super.onStart();
        mFragmentController.dispatchStart();
        getLifecycleRegistry().setCurrentState(Lifecycle.State.STARTED);
        if (mTray == null) {
            Log.e(LOGTAG, "Failed to start Tray clock");
        } else {
            mTray.start(this);
        }
    }

    @Override
    protected void onStop() {
        SettingsStore.getInstance(getBaseContext()).setPid(0);
        super.onStop();
        mFragmentController.dispatchStop();
        TelemetryService.sessionStop();
        if (mTray != null) {
            mTray.stop(this);
        }
    }

    public void flushBackHandlers() {
        int backCount = mBackHandlers.size();
        while (backCount > 0) {
            mBackHandlers.getLast().run();
            int newBackCount = mBackHandlers.size();
            if (newBackCount == backCount) {
                Log.e(LOGTAG, "Back counter is not decreasing,");
                break;
            }
            backCount = newBackCount;
        }
    }

    @Override
    protected void onPause() {
        mIsBackgrounding = true;
        Log.i("VRB", "RovinProduct: onPause lifecycle reordered");
        mWindows.onPause();
        if (mIsPresentingImmersive.getValue()) {
            exitImmersiveSync();
            mIsPresentingImmersive.setValue(false);
        }
        mAudioEngine.pauseEngine();
        mFragmentController.dispatchPause();

        for (Widget widget : mWidgets.values()) {
            widget.onPause();
        }
        // Reset so the dialog will show again on resume.
        mConnectionAvailable = true;
        if (mOffscreenDisplay != null) {
            mOffscreenDisplay.onPause();
        }
        mWidgetContainer.getViewTreeObserver().removeOnGlobalFocusChangeListener(globalFocusListener);
        super.onPause();
        UISurfaceTextureRenderer.setRenderActive(false);
    }

    @Override
    protected void onResume() {
        mIsBackgrounding = false;
        UISurfaceTextureRenderer.setRenderActive(true);
        MotionEventGenerator.clearDevices();
        mWidgetContainer.getViewTreeObserver().addOnGlobalFocusChangeListener(globalFocusListener);
        if (mOffscreenDisplay != null) {
            mOffscreenDisplay.onResume();
        }

        mFragmentController.dispatchResume();
        mWindows.onResume();

        mAudioEngine.resumeEngine();
        for (Widget widget : mWidgets.values()) {
            widget.onResume();
        }

        // If we're signed-in, poll for any new device events (e.g. received tabs) on
        // activity resume.
        // There's no push support right now, so this helps with the perception of
        // speedy tab delivery.
        ((VRBrowserApplication) getApplicationContext()).getAccounts().refreshDevicesAsync();
        ((VRBrowserApplication) getApplicationContext()).getAccounts().pollForEventsAsync();

        super.onResume();
        ((VRBrowserApplication) getApplication()).setCurrentActivity(this);
        getLifecycleRegistry().setCurrentState(Lifecycle.State.RESUMED);

        // The native auto-resume is now disabled to prevent collisions with the Rovin
        // Product's
        // internal initialization. We rely on the unified JS handshake instead.
        // if (isLaunchImmersive() && !mIsPresentingImmersive.getValue()) {
        // relaunchImmersiveMode();
        // }
        primeRovinRuntimeControllerInput("activity-resume");
        resumeRovinImmersiveIfNeeded("activity-resume");
    }

    private boolean shouldPreserveRovinWarmIntent() {
        return !isFinishing() && (mLaunchImmersive || mRovinImmersiveActiveConfirmed
                || (mIsPresentingImmersive != null && Boolean.TRUE.equals(mIsPresentingImmersive.getValue())));
    }

    private void resumeRovinImmersiveIfNeeded(@NonNull final String reason) {
        if (!shouldPreserveRovinWarmIntent()) {
            return;
        }
        if (mIsPresentingImmersive != null && Boolean.TRUE.equals(mIsPresentingImmersive.getValue())) {
            Log.i(LOGTAG, "Rovin Runtime: Warm resume skipped; immersive is already presenting. reason=" + reason);
            return;
        }

        runOnUiThread(() -> {
            WindowWidget focusedWindow = mWindows != null ? mWindows.getFocusedWindow() : null;
            if (focusedWindow == null || focusedWindow.getSession() == null) {
                Log.w(LOGTAG, "Rovin Runtime: Warm resume requested but no focused session is available. reason=" + reason);
                return;
            }

            Log.i(LOGTAG, "Rovin Runtime: Warm resume preserving existing page. reason=" + reason);
            stopRovinStartupPolling();
            onDismissWebXRInterstitial();
            setPrimaryBrowserChromeVisible(false);
            showRovinWarmResumeLoading(reason);
            mRovinReady = true;
            mRovinTransitionTriggered = true;
            mIsLaunchingVr = true;
            focusedWindow.getSession().loadUri(ROVIN_TRIGGER_RESUME_JS);
            new Handler(Looper.getMainLooper()).postDelayed(() -> mIsLaunchingVr = false, 5000);
        });
    }

    private RovinLandingDialogWidget ensureRovinLandingWidget() {
        if (mRovinLandingWidget == null) {
            mRovinLandingWidget = new RovinLandingDialogWidget(this);
            mRovinLandingWidget.setDelegate(new RovinLandingDialogWidget.Delegate() {
                @Override
                public void onPlayRequested() {
                    launchRovinExperience(resolveRovinLaunchTarget());
                }

                @Override
                public void onRecoveryBrowserRequested() {
                    openRecoveryBrowser(resolveRovinLaunchTarget());
                }

                @Override
                public void onRetryRequested() {
                    showRovinLanding();
                }
            });
        }
        return mRovinLandingWidget;
    }

    private void showRovinWarmResumeLoading(@NonNull final String reason) {
        cancelPendingRovinWarmResumeFallback();
        RovinLandingDialogWidget landingWidget = ensureRovinLandingWidget();
        landingWidget.bindLoading(BuildConfig.ROVIN_PRODUCT_TITLE, "Returning to immersive mode...");
        landingWidget.show(UIWidget.REQUEST_FOCUS);

        mPendingRovinWarmResumeFallback = () -> {
            mPendingRovinWarmResumeFallback = null;
            if (mIsPresentingImmersive != null && Boolean.TRUE.equals(mIsPresentingImmersive.getValue())) {
                return;
            }
            if (mRovinLandingWidget == null) {
                return;
            }
            Log.w(LOGTAG, "Rovin Runtime: Warm resume fallback timeout. reason=" + reason);
            mRovinLandingWidget.bindReady(
                    BuildConfig.ROVIN_PRODUCT_TITLE,
                    "Resume took longer than expected. Press Play to retry.");
            mRovinLandingWidget.show(UIWidget.REQUEST_FOCUS);
        };
        mHandler.postDelayed(mPendingRovinWarmResumeFallback, ROVIN_WARM_RESUME_FALLBACK_DELAY_MS);
    }

    private void cancelPendingRovinWarmResumeFallback() {
        if (mPendingRovinWarmResumeFallback != null) {
            mHandler.removeCallbacks(mPendingRovinWarmResumeFallback);
            mPendingRovinWarmResumeFallback = null;
        }
    }

    private void relaunchImmersiveMode() {
        if (mIsLaunchingVr || mRovinImmersiveActiveConfirmed || mRovinTransitionTriggered) {
            return;
        }

        if (mWindows == null || mWindows.getFocusedWindow() == null
                || mWindows.getFocusedWindow().getSession() == null) {
            return;
        }

        String currentUriStr = mWindows.getFocusedWindow().getSession().getCurrentUri();
        if (currentUriStr != null && currentUriStr.contains("immersiveTargetElementXPath=")) {
            Log.i(LOGTAG, "Rovin Runtime: Immersive parameters already present in URL. Awaiting auto-click.");
            mRovinTransitionTriggered = true;
            return;
        }

        // ROVIN: Use Native Authority for the VR transition.
        mRovinTransitionTriggered = true;
        mIsLaunchingVr = true;
        runOnUiThread(() -> {
            Uri targetUri = getIntent().getData();
            if (targetUri == null) {
                targetUri = Uri.parse(currentUriStr);
            }

            Log.i(LOGTAG, "Rovin Runtime: Triggering NATIVE immersive launch (One-Shot)");
            mWindows.openInImmersiveMode(targetUri, mImmersiveParentElementXPath, mImmersiveTargetElementXPath);

            // Cooldown to prevent multiple simultaneous launch attempts
            new Handler(Looper.getMainLooper()).postDelayed(() -> mIsLaunchingVr = false, 5000);
        });
    }

    public void signalReadyForVr() {
        runOnUiThread(() -> {
            if (mRovinReady) {
                Log.d(LOGTAG, "Rovin Runtime: signalReadyForVr called but mRovinReady is already true. Ignoring.");
                return;
            }
            mRovinReady = true;
            Log.i(LOGTAG, "Rovin Runtime: Received READY signal. Handshake complete.");

            onDismissWebXRInterstitial();
            setPrimaryBrowserChromeVisible(false);

            if (mRovinLandingWidget != null) {
                mRovinLandingWidget.hide(REMOVE_WIDGET);
            }

            WindowWidget focusedWindow = mWindows != null ? mWindows.getFocusedWindow() : null;
            if (focusedWindow == null || focusedWindow.getSession() == null) {
                Log.w(LOGTAG, "Rovin Runtime: READY received but no focused session is available.");
                mRovinReady = false;
                mRovinTransitionTriggered = false;
                mRovinPendingNativeFocusStart = false;
                resetRovinStartupBridgeState();
                return;
            }

            if (!mRovinNativeVrFocused) {
                Log.i(LOGTAG, "Rovin Runtime: READY received before native VR focus; deferring JS startup bridge.");
                rovinLog("System: Waiting for native VR focus before WebXR request.");
                mRovinPendingNativeFocusStart = true;
                stopRovinStartupPolling();
                return;
            }

            scheduleRovinStartOnFocusedWindow(focusedWindow, "ready");
        });
    }

    private Handler getRovinStartupBridgeHandler() {
        if (mRovinStartupBridgeHandler == null) {
            mRovinStartupBridgeHandler = new Handler(Looper.getMainLooper());
        }
        return mRovinStartupBridgeHandler;
    }

    private void cancelPendingRovinStartupBridge() {
        if (mRovinStartupBridgeHandler != null && mPendingRovinStartupBridge != null) {
            mRovinStartupBridgeHandler.removeCallbacks(mPendingRovinStartupBridge);
        }
        mPendingRovinStartupBridge = null;
    }

    private void resetRovinStartupBridgeState() {
        cancelPendingRovinStartupBridge();
    }

    private void scheduleRovinStartOnFocusedWindow(WindowWidget focusedWindow, @NonNull String reason) {
        if (focusedWindow == null || focusedWindow.getSession() == null) {
            Log.w(LOGTAG, "Rovin Runtime: Cannot schedule startup bridge; no focused session. reason=" + reason);
            mRovinReady = false;
            mRovinTransitionTriggered = false;
            mRovinPendingNativeFocusStart = false;
            resetRovinStartupBridgeState();
            return;
        }
        if (mRovinTransitionTriggered || mRovinImmersiveActiveConfirmed) {
            Log.d(LOGTAG, "Rovin Runtime: Startup bridge already handled. reason=" + reason);
            return;
        }
        if (!mRovinNativeVrFocused) {
            Log.i(LOGTAG, "Rovin Runtime: Native VR focus unavailable; deferring startup bridge. reason=" + reason);
            mRovinPendingNativeFocusStart = true;
            return;
        }

        long delayMs = ROVIN_NATIVE_FOCUS_START_SETTLE_DELAY_MS;

        cancelPendingRovinStartupBridge();
        mRovinPendingNativeFocusStart = false;
        mPendingRovinStartupBridge = () -> {
            mPendingRovinStartupBridge = null;
            if (!mRovinNativeVrFocused) {
                Log.i(LOGTAG, "Rovin Runtime: Native VR focus lost before startup bridge fired.");
                mRovinPendingNativeFocusStart = mRovinReady && !mRovinTransitionTriggered
                        && !mRovinImmersiveActiveConfirmed;
                return;
            }
            WindowWidget currentWindow = mWindows != null ? mWindows.getFocusedWindow() : null;
            triggerRovinStartOnFocusedWindow(currentWindow, reason + "-settled");
        };

        Log.i(LOGTAG, "Rovin Runtime: Scheduling JS startup bridge in " + delayMs
                + "ms after native VR focus. reason=" + reason);
        rovinLog("System: Native VR focus ready; starting WebXR after "
                + delayMs + "ms settle.");
        getRovinStartupBridgeHandler().postDelayed(mPendingRovinStartupBridge, delayMs);
    }

    private void triggerRovinStartOnFocusedWindow(WindowWidget focusedWindow, @NonNull String reason) {
        if (focusedWindow == null || focusedWindow.getSession() == null) {
            Log.w(LOGTAG, "Rovin Runtime: Cannot trigger startup bridge; no focused session. reason=" + reason);
            mRovinReady = false;
            mRovinTransitionTriggered = false;
            mRovinPendingNativeFocusStart = false;
            resetRovinStartupBridgeState();
            return;
        }
        if (mRovinTransitionTriggered || mRovinImmersiveActiveConfirmed) {
            Log.d(LOGTAG, "Rovin Runtime: Startup bridge already handled. reason=" + reason);
            return;
        }

        cancelPendingRovinStartupBridge();
        Log.i(LOGTAG, "Rovin Runtime: Handshake success. Calling JS startup bridge.");
        mStartupPollingCount = 0;
        mRovinPendingNativeFocusStart = false;
        mRovinTransitionTriggered = true;
        stopRovinStartupPolling();
        focusedWindow.getSession().loadUri(ROVIN_TRIGGER_START_JS);
    }

    public void rovinLog(String message) {
        String logLine = "[" + new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date()) + "] " + message;
        Log.i(LOGTAG, "Rovin Console: " + logLine);
        runOnUiThread(() -> {
            if (mRovinLandingWidget != null) {
                mRovinLandingWidget.appendLog(logLine);
            }
            if (mWebXRInterstitial != null) {
                mWebXRInterstitial.appendLog(logLine);
            }
        });
    }

    public void startRovinStartupPolling() {
        Log.i(LOGTAG, "Rovin Runtime: startRovinStartupPolling() called.");
        runOnUiThread(() -> {
            Log.i(LOGTAG, "Rovin Runtime: Starting native-led readiness polling...");
            mStartupPollingCount = 0;
            mRovinReady = false;
            mIsLaunchingVr = false;
            mRovinImmersiveActiveConfirmed = false;
            mRovinTransitionTriggered = false;
            mRovinPendingNativeFocusStart = false;
            mRovinXrStartFailureCount = 0;
            resetRovinStartupBridgeState();
            if (mStartupPollingHandler == null) {
                mStartupPollingHandler = new Handler(Looper.getMainLooper());
            }
            if (mStartupPollingRunnable == null) {
                mStartupPollingRunnable = new Runnable() {
                    @Override
                    public void run() {
                        pollRovinLoadedStatus();
                    }
                };
            }
            mStartupPollingHandler.removeCallbacks(mStartupPollingRunnable);
            mStartupPollingHandler.postDelayed(mStartupPollingRunnable, ROVIN_STARTUP_POLLING_INTERVAL_MS);
        });
    }


    private void saveRovinLogs() {
        try {
            java.io.File path = android.os.Environment
                    .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
            java.io.File file = new java.io.File(path, "NeonChuck_Startup_Log.txt");
            java.io.FileWriter writer = new java.io.FileWriter(file, true);
            writer.write("--- LOG DUMP: " + new java.util.Date().toString() + " ---\n");
            writer.write("Startup sequence initiated.\n");
            writer.close();
            rovinLog("System: Logs saved to Downloads/NeonChuck_Startup_Log.txt");
        } catch (Exception e) {
            Log.e(LOGTAG, "Failed to save logs", e);
        }
    }

    public void handleRovinImmersiveActive() {
        Log.i(LOGTAG, "Rovin Runtime: JS signaled Immersive Active. Killing all startup hooks.");
        mRovinImmersiveActiveConfirmed = true;
        mRovinPendingNativeFocusStart = false;
        mRovinXrStartFailureCount = 0;
        resetRovinStartupBridgeState();
        cancelPendingRovinWarmResumeFallback();
        if (mRovinLandingWidget != null && mRovinLandingWidget.isVisible()) {
            mRovinLandingWidget.hide(REMOVE_WIDGET);
            mRovinLandingWidget = null;
        }
        stopRovinStartupPolling();
    }

    public void handleRovinXrStartFailed(@Nullable String payload) {
        runOnUiThread(() -> {
            if (mRovinImmersiveActiveConfirmed
                    || (mIsPresentingImmersive != null && Boolean.TRUE.equals(mIsPresentingImmersive.getValue()))) {
                Log.i(LOGTAG, "Rovin Runtime: Ignoring XR start failure because immersive is already active.");
                return;
            }

            mRovinXrStartFailureCount += 1;
            mIsLaunchingVr = false;
            mRovinTransitionTriggered = false;
            mRovinPendingNativeFocusStart = true;
            mRovinReady = true;
            cancelPendingRovinStartupBridge();
            stopRovinStartupPolling();
            onDismissWebXRInterstitial();
            setPrimaryBrowserChromeVisible(false);

            Log.w(LOGTAG, "Rovin Runtime: JS WebXR start failed; scheduling native focus-gated retry "
                    + mRovinXrStartFailureCount + "/" + ROVIN_XR_START_MAX_FAILURES
                    + ". payload=" + payload);
            rovinLog("System: WebXR start failed; waiting before native retry "
                    + mRovinXrStartFailureCount + "/" + ROVIN_XR_START_MAX_FAILURES + ".");

            if (mRovinXrStartFailureCount >= ROVIN_XR_START_MAX_FAILURES) {
                Log.e(LOGTAG, "Rovin Runtime: WebXR start failed too many times; leaving page alive.");
                rovinLog("System: WebXR retry limit reached; leaving the loading page alive.");
                return;
            }

            WindowWidget focusedWindow = mWindows != null ? mWindows.getFocusedWindow() : null;
            if (focusedWindow == null || focusedWindow.getSession() == null) {
                Log.w(LOGTAG, "Rovin Runtime: XR failure retry deferred; no focused session is available.");
                return;
            }

            if (!mRovinNativeVrFocused) {
                Log.i(LOGTAG, "Rovin Runtime: XR failure retry deferred until native VR focus returns.");
                return;
            }

            mPendingRovinStartupBridge = () -> {
                mPendingRovinStartupBridge = null;
                WindowWidget currentWindow = mWindows != null ? mWindows.getFocusedWindow() : null;
                if (!mRovinNativeVrFocused) {
                    Log.i(LOGTAG, "Rovin Runtime: Native VR focus lost before XR failure retry.");
                    mRovinPendingNativeFocusStart = true;
                    return;
                }
                scheduleRovinStartOnFocusedWindow(currentWindow, "xr-start-failed");
            };
            getRovinStartupBridgeHandler().postDelayed(mPendingRovinStartupBridge, ROVIN_XR_START_RETRY_DELAY_MS);
        });
    }

    public void stopRovinStartupPolling() {
        runOnUiThread(() -> {
            if (mStartupPollingHandler != null && mStartupPollingRunnable != null) {
                Log.i(LOGTAG, "Rovin Runtime: Stopping readiness polling.");
                mStartupPollingHandler.removeCallbacks(mStartupPollingRunnable);
            }
        });
    }

    private void pollRovinLoadedStatus() {
        mStartupPollingCount++;
        Log.d(LOGTAG, "Rovin Runtime: Polling readiness... Attempt " + mStartupPollingCount);

        if (mRovinTransitionTriggered) {
            Log.i(LOGTAG, "Rovin Runtime: Startup bridge already triggered. Stopping readiness polling.");
            stopRovinStartupPolling();
            return;
        }

        if (mIsPresentingImmersive != null && Boolean.TRUE.equals(mIsPresentingImmersive.getValue())) {
            Log.i(LOGTAG, "Rovin Runtime: Immersive mode detected. Stopping startup polling.");
            stopRovinStartupPolling();
            return;
        }

        WindowWidget focusedWindow = mWindows.getFocusedWindow();
        if (focusedWindow != null && focusedWindow.getSession() != null) {
            String currentTitle = focusedWindow.getSession().getCurrentTitle();
            if (currentTitle != null && currentTitle.contains(ROVIN_READY_TITLE_MARKER)) {
                Log.i(LOGTAG, "Rovin Runtime: Readiness title marker detected.");
                handleRovinLoadedResult(true);
                return;
            }
            focusedWindow.getSession().loadUri(ROVIN_POLL_LOADED_JS);
        }

        if (mStartupPollingCount >= ROVIN_STARTUP_MAX_ATTEMPTS) {
            Log.w(LOGTAG, "Rovin Runtime: Polling timeout exceeded (" + ROVIN_STARTUP_MAX_ATTEMPTS
                    + "s). Attempting JS startup bridge without native fallback UI.");
            if (focusedWindow != null && focusedWindow.getSession() != null) {
                rovinLog("System: Native readiness timed out; attempting JS startup bridge.");
                if (mRovinNativeVrFocused) {
                    mRovinReady = true;
                    scheduleRovinStartOnFocusedWindow(focusedWindow, "readiness-timeout");
                } else {
                    Log.i(LOGTAG, "Rovin Runtime: Timeout reached before native VR focus; deferring JS startup bridge.");
                    rovinLog("System: Waiting for native VR focus before timeout fallback request.");
                    mRovinReady = true;
                    mRovinPendingNativeFocusStart = true;
                }
            } else {
                mRovinReady = false;
                mIsLaunchingVr = false;
                mRovinImmersiveActiveConfirmed = false;
                mRovinTransitionTriggered = false;
                mRovinPendingNativeFocusStart = false;
                resetRovinStartupBridgeState();
                cancelPendingRovinWarmResumeFallback();
                cancelPendingRovinAutoLaunch();
            }
            stopRovinStartupPolling();
            return;
        }

        if (mStartupPollingHandler != null && mStartupPollingRunnable != null) {
            mStartupPollingHandler.postDelayed(mStartupPollingRunnable, ROVIN_STARTUP_POLLING_INTERVAL_MS);
        }
    }
    public void handleRovinLoadedResult(boolean loaded) {
        runOnUiThread(() -> {
            if (loaded) {
                Log.i(LOGTAG, "Rovin Runtime: Polling success! Engine is fully loaded.");
                rovinLog("System: Polling success! Triggering VR handshake.");
                signalReadyForVr();
            }
        });
    }

    private void showStartupFallbackUI() {
        runOnUiThread(() -> {
            mRovinReady = false;
            mIsLaunchingVr = false;
            mRovinImmersiveActiveConfirmed = false;
            mRovinTransitionTriggered = false;
            mRovinPendingNativeFocusStart = false;
            mRovinXrStartFailureCount = 0;
            resetRovinStartupBridgeState();
            cancelPendingRovinWarmResumeFallback();
            cancelPendingRovinAutoLaunch();

            if (mWebXRInterstitial != null) {
                Log.i(LOGTAG, "Rovin Runtime: Showing fallback 'Enter Game' button.");
                mWebXRInterstitial.showFallbackButton();
                return;
            }

            Log.i(LOGTAG, "Rovin Runtime: Showing Rovin landing fallback for manual retry.");
            final RovinLaunchTarget target = resolveRovinLaunchTarget();
            setPrimaryBrowserChromeVisible(false);

            if (mRovinLandingWidget == null) {
                mRovinLandingWidget = new RovinLandingDialogWidget(this);
                mRovinLandingWidget.setDelegate(new RovinLandingDialogWidget.Delegate() {
                    @Override
                    public void onPlayRequested() {
                        retryRovinStartupFromFallback();
                    }

                    @Override
                    public void onRecoveryBrowserRequested() {
                        openRecoveryBrowser(resolveRovinLaunchTarget());
                    }

                    @Override
                    public void onRetryRequested() {
                        showRovinLanding();
                    }
                });
            }

            if (target.valid) {
                mRovinLandingWidget.bindReady(
                        BuildConfig.ROVIN_PRODUCT_TITLE,
                        "Startup took longer than expected. Press Play to retry.");
            } else {
                mRovinLandingWidget.bindError(BuildConfig.ROVIN_PRODUCT_TITLE, target.statusMessage);
            }
            mRovinLandingWidget.show(UIWidget.REQUEST_FOCUS);
        });
    }

    private void retryRovinStartupFromFallback() {
        runOnUiThread(() -> {
            WindowWidget focusedWindow = mWindows != null ? mWindows.getFocusedWindow() : null;
            if (focusedWindow != null && focusedWindow.getSession() != null) {
                Log.i(LOGTAG, "Rovin Runtime: Retrying startup readiness on current page.");
                if (mRovinLandingWidget != null) {
                    mRovinLandingWidget.hide(REMOVE_WIDGET);
                }
                onDismissWebXRInterstitial();
                setPrimaryBrowserChromeVisible(false);
                startRovinStartupPolling();
                return;
            }

            Log.w(LOGTAG, "Rovin Runtime: Fallback retry has no focused page; relaunching target.");
            launchRovinExperience(resolveRovinLaunchTarget());
        });
    }
    @Override
    protected void onDestroy() {
        ((VRBrowserApplication) getApplication()).onActivityDestroy();
        SettingsStore.getInstance(getBaseContext()).setPid(0);
        // Unregister the crash service broadcast receiver
        unregisterReceiver(mCrashReceiver);
        mSearchEngineWrapper.unregisterForUpdates();
        if (mPlatformPlugin != null)
            mPlatformPlugin.unregisterListener(this);

        mFragmentController.dispatchDestroy();

        for (Widget widget : mWidgets.values()) {
            widget.releaseWidget();
        }

        if (mOffscreenDisplay != null) {
            mOffscreenDisplay.release();
        }
        if (mAudioEngine != null) {
            mAudioEngine.release();
        }
        if (mPermissionDelegate != null) {
            mPermissionDelegate.release();
        }

        mTray.removeListeners(mWindows);

        // Remove all widget listeners
        mWindows.onDestroy();

        BitmapCache.getInstance(this).onDestroy();

        SessionStore.get().onDestroy();

        if (mRovinAssetHttpServer != null) {
            mRovinAssetHttpServer.stop();
            mRovinAssetHttpServer = null;
        }

        getServicesProvider().getConnectivityReceiver().removeListener(mConnectivityDelegate);

        mPrefs.unregisterOnSharedPreferenceChangeListener(this);

        super.onDestroy();
        getLifecycleRegistry().setCurrentState(Lifecycle.State.DESTROYED);
        mViewModelStore.clear();
        // Always exit to work around
        // https://github.com/MozillaReality/FirefoxReality/issues/3363
        finish();
        System.exit(0);
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        Log.d(LOGTAG, "VRBrowserActivity onNewIntent");
        super.onNewIntent(intent);
        setIntent(intent);

        if (getCrashReportIntent().action_crashed.equals(intent.getAction())) {
            Log.e(LOGTAG, "Restarted after a crash");
        } else if (shouldPreserveRovinWarmIntent()) {
            Log.i("VRB", "NeonChuck: warm intent while runtime is active; preserving existing immersive page");
            resumeRovinImmersiveIfNeeded("warm-intent");
        } else if (isLaunchImmersive() && !isFinishing()) {
            Log.i("VRB", "NeonChuck: onNewIntent warm-start detected");
            Uri targetUri = intent.getData();
            String currentUri = (mWindows != null && mWindows.getFocusedWindow() != null)
                    ? mWindows.getFocusedWindow().getSession().getCurrentUri()
                    : "";
            if (targetUri != null && !currentUri.isEmpty() && currentUri.startsWith(targetUri.toString())) {
                Log.i("VRB", "NeonChuck: Same site in warm-start, skipping relaunch loop");
            } else {
                loadFromIntent(intent);
            }
        } else if (isLaunchImmersive()) {
            finish();
            startActivity(intent);
        } else {
            loadFromIntent(intent);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        Language language = LocaleUtils.getDisplayLanguage(this);
        newConfig.setLocale(language.getLocale());
        // TODO: Deprecated updateConfiguration(Configuration,DisplayMetrics),
        // see https://github.com/Igalia/wolvic/issues/797
        getBaseContext().getResources().updateConfiguration(newConfig,
                getBaseContext().getResources().getDisplayMetrics());

        LocaleUtils.update(this, language);

        SessionStore.get().onConfigurationChanged(newConfig);
        mWidgets.forEach((i, widget) -> widget.onConfigurationChanged(newConfig));
        SendTabDialogWidget.getInstance(this).onConfigurationChanged(newConfig);

        SearchEngineWrapper s = SearchEngineWrapper.get(this);
        s.setupPreferredSearchEngine();
        s.setCurrentSearchEngine(null);

        super.onConfigurationChanged(newConfig);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (Objects.equals(key, getString(R.string.settings_key_voice_search_service))) {
            initializeSpeechRecognizer();
        } else if (Objects.equals(key, getString(R.string.settings_key_head_lock))) {
            boolean isHeadLockEnabled = mSettings.isHeadLockEnabled();
            setLockMode(isHeadLockEnabled ? WidgetManagerDelegate.HEAD_LOCK : WidgetManagerDelegate.NO_LOCK);
            if (!isHeadLockEnabled)
                recenterUIYaw(WidgetManagerDelegate.YAW_TARGET_ALL);
        } else if (Objects.equals(key, getString(R.string.settings_key_tabs_location))) {
            // remove the previous widget
            if (mTabsBar != null) {
                removeWidget(mTabsBar);
                mTabsBar.releaseWidget();
            }

            switch (mSettings.getTabsLocation()) {
                case SettingsStore.TABS_LOCATION_HORIZONTAL:
                    mTabsBar = new HorizontalTabsBar(this, mWindows);
                    break;
                case SettingsStore.TABS_LOCATION_VERTICAL:
                    mTabsBar = new VerticalTabsBar(this, mWindows);
                    break;
                case SettingsStore.TABS_LOCATION_TRAY:
                default:
                    mTabsBar = null;
                    mWindows.adjustWindowOffsets();
                    return;
            }
            addWidget(mTabsBar);
            mTabsBar.attachToWindow(mWindows.getFocusedWindow());
            updateWidget(mTabsBar);
            mWindows.adjustWindowOffsets();
        }
    }

    void loadFromIntent(final Intent intent) {
        if (mHideWebXRIntersitial) {
            setWebXRIntersitialState(WEBXR_INTERSTITIAL_HIDDEN);
        }

        if (getCrashReportIntent().action_crashed.equals(intent.getAction())) {
            Log.e(LOGTAG, "Loading from crash Intent");
        }

        // FIXME https://github.com/MozillaReality/FirefoxReality/issues/3066
        if (DeviceType.isOculusBuild()) {
            Bundle bundle = intent.getExtras();
            if (bundle != null) {
                String cmd = bundle.getString(EXTRA_INTENT_CMD);
                if ((cmd != null) && (cmd.length() > 0)) {
                    try {
                        JSONObject object = new JSONObject(cmd);
                        JSONObject launch = object.getJSONObject(JSON_OVR_SOCIAL_LAUNCH);
                        String msg = launch.getString(JSON_DEEPLINK_MESSAGE);
                        Log.d(LOGTAG, "deeplink message: " + msg);
                        onAppLink(msg);
                        return;
                    } catch (Exception ex) {
                        Log.e(LOGTAG, "Error parsing deeplink JSON: " + ex.toString());
                    }
                }
            }
        }

        boolean openInWindow = false;
        boolean openInBackground = false;
        boolean openInKioskMode = false;

        Uri dataUri = intent.getData();
        Uri targetUri = null;
        Bundle extras;

        if (dataUri != null && dataUri.getScheme().equals(CUSTOM_URI_SCHEME)
                && dataUri.getHost().equals(CUSTOM_URI_HOST)) {
            Log.d(LOGTAG, "Parsing custom URI from intent: " + dataUri);

            extras = new Bundle();
            Set<String> keys = dataUri.getQueryParameterNames();
            for (String key : keys) {
                String queryParameter = dataUri.getQueryParameter(key);
                if (queryParameter == null)
                    continue;

                // all supported parameters are booleans, except "url"
                String lowerCaseKey = key.toLowerCase();
                if (lowerCaseKey.equals(EXTRA_URL))
                    extras.putString(lowerCaseKey, queryParameter);
                else
                    extras.putBoolean(lowerCaseKey, Boolean.parseBoolean(queryParameter));
            }
        } else {
            targetUri = intent.getData();
            extras = intent.getExtras();
        }

        if (extras != null) {
            // targetUri will be null here if the data URI is empty or contains a custom
            // URI;
            // in that case, we will use the "url" parameter if it exists
            if (targetUri == null && extras.containsKey(EXTRA_URL)) {
                targetUri = Uri.parse(extras.getString(EXTRA_URL));
            }
            // SEND Actions received WebBrowser share dialogs
            if (targetUri == null && extras.containsKey(Intent.EXTRA_TEXT)) {
                String text = extras.getString(Intent.EXTRA_TEXT, "");
                int i = text.indexOf("https://");
                if (i < 0) {
                    i = text.indexOf("http://");
                }
                if (i >= 0) {
                    targetUri = Uri.parse(text.substring(i));
                }
            }

            // Open the tab in background/foreground, if there is no URL provided we just
            // open the homepage
            if (extras.containsKey(EXTRA_OPEN_IN_BACKGROUND)) {
                openInBackground = extras.getBoolean(EXTRA_OPEN_IN_BACKGROUND, false);
                if (targetUri == null) {
                    targetUri = Uri.parse(SettingsStore.getInstance(this).getHomepage());
                }
            }

            // Open the provided URL in a new window, if there is no URL provided we just
            // open the homepage
            if (extras.containsKey(EXTRA_CREATE_NEW_WINDOW)) {
                openInWindow = extras.getBoolean(EXTRA_CREATE_NEW_WINDOW, false);
                if (targetUri == null) {
                    targetUri = Uri.parse(SettingsStore.getInstance(this).getHomepage());
                }
            }

            if (extras.containsKey(EXTRA_HIDE_WEBXR_INTERSTITIAL)) {
                mHideWebXRIntersitial = extras.getBoolean(EXTRA_HIDE_WEBXR_INTERSTITIAL, false);
                if (mHideWebXRIntersitial) {
                    setWebXRIntersitialState(WEBXR_INTERSTITIAL_HIDDEN);
                }
            }

            if (extras.containsKey(EXTRA_HIDE_WHATS_NEW)) {
                boolean hideWhatsNew = extras.getBoolean(EXTRA_HIDE_WHATS_NEW, false);
                if (hideWhatsNew && mWhatsNewWidget != null) {
                    mWhatsNewWidget.hide(REMOVE_WIDGET);
                }
            }

            openInKioskMode = extras.getBoolean(EXTRA_KIOSK, false);

            if (extras.getBoolean(EXTRA_LAUNCH_IMMERSIVE)) {
                mImmersiveParentElementXPath = extras.getString(EXTRA_LAUNCH_IMMERSIVE_PARENT_XPATH);
                mImmersiveTargetElementXPath = extras.getString(EXTRA_LAUNCH_IMMERSIVE_ELEMENT_XPATH);

                // ROVIN: Disable native-forced auto-immersive launch during startup.
                // We want the game to load in 2D first, then use our polling handshake
                // to trigger VR when the JS engine is actually ready.
                // mLaunchImmersive |= targetUri != null && mImmersiveTargetElementXPath != null;
                Log.i(LOGTAG, "Rovin Runtime: EXTRA_LAUNCH_IMMERSIVE detected but suppressed for handshake stability.");
            }
        }

        // If there is a target URI we open it
        if (targetUri != null) {
            Log.d(LOGTAG, "Loading URI from intent: " + targetUri);

            int location = Windows.OPEN_IN_FOREGROUND;

            if (openInKioskMode) {
                // FIXME this might not work as expected if the app was already running
                mWindows.openInKioskMode(addRovinBridgeCapabilities(targetUri.toString()));
            }
            if (mLaunchImmersive) {
                mWindows.openInImmersiveMode(targetUri, mImmersiveParentElementXPath, mImmersiveTargetElementXPath);
            } else {
                if (openInWindow) {
                    location = Windows.OPEN_IN_NEW_WINDOW;
                } else if (openInBackground) {
                    location = Windows.OPEN_IN_BACKGROUND;
                }
                if (location == Windows.OPEN_IN_FOREGROUND) {
                    mWindows.findTabAndSelect(addRovinBridgeCapabilities(targetUri.toString()));
                } else {
                    mWindows.openNewTabAfterRestore(addRovinBridgeCapabilities(targetUri.toString()), location);
                }
            }
        } else if (mWindows.getFocusedWindow() != null && mWindows.getFocusedWindow().isCurrentUriBlank()) {
            showRovinLanding();
        } else {
            // ROVIN: Port Repair Logic
            // If the tab is already open with a Rovin Product URL but on a different port
            // (from a previous session),
            // we must force-redirect it to the new port to prevent "Connection Refused"
            // blackouts.
            WindowWidget focusedWindow = mWindows.getFocusedWindow();
            Session session = focusedWindow != null ? focusedWindow.getSession() : null;
            String currentUri = session != null ? session.getCurrentUri() : null;
            RovinLaunchTarget target = resolveRovinLaunchTarget();

            if (currentUri != null && target != null && target.valid && !StringUtils.isEmpty(target.url)) {
                Uri current = Uri.parse(currentUri);
                Uri latest = Uri.parse(target.url);

                // If it's the same host but different port, force reload with the new port.
                if ("127.0.0.1".equals(current.getHost()) && current.getPort() != latest.getPort()) {
                    Log.i(LOGTAG, "Rovin Port Repair: Redirecting stale tab from port " + current.getPort() + " to "
                            + latest.getPort());
                    launchRovinExperience(target);
                    return;
                }
            }
            Log.d(LOGTAG, "Skipping Rovin landing because focused window is not blank. Current URI=" + currentUri);
        }
    }

    private void showRovinLanding() {
        final RovinLaunchTarget target = resolveRovinLaunchTarget();
        cancelPendingRovinAutoLaunch();
        cancelPendingRovinWarmResumeFallback();
        setPrimaryBrowserChromeVisible(false);

        if (mRovinLandingWidget == null) {
            mRovinLandingWidget = new RovinLandingDialogWidget(this);
            mRovinLandingWidget.setDelegate(new RovinLandingDialogWidget.Delegate() {
                @Override
                public void onPlayRequested() {
                    launchRovinExperience(resolveRovinLaunchTarget());
                }

                @Override
                public void onRecoveryBrowserRequested() {
                    openRecoveryBrowser(resolveRovinLaunchTarget());
                }

                @Override
                public void onRetryRequested() {
                    showRovinLanding();
                }
            });
        }

        if (target.valid) {
            mRovinLandingWidget.bindReady(BuildConfig.ROVIN_PRODUCT_TITLE, target.statusMessage);
            scheduleRovinAutoLaunch(target);
        } else {
            mRovinLandingWidget.bindError(BuildConfig.ROVIN_PRODUCT_TITLE, target.statusMessage);
        }

        mRovinLandingWidget.show(UIWidget.REQUEST_FOCUS);
    }

    private void launchRovinExperience(@NonNull RovinLaunchTarget target) {
        cancelPendingRovinAutoLaunch();
        cancelPendingRovinWarmResumeFallback();
        if (!target.valid || StringUtils.isEmpty(target.url)) {
            showRovinLanding();
            return;
        }

        if (mRovinLandingWidget != null && mRovinLandingWidget.isVisible()) {
            mRovinLandingWidget.hide(REMOVE_WIDGET);
            mRovinLandingWidget = null;
        }

        setPrimaryBrowserChromeVisible(false);
        mWindows.openInKioskMode(target.url);
        startRovinStartupPolling();
    }

    private void openRecoveryBrowser(@NonNull RovinLaunchTarget target) {
        cancelPendingRovinAutoLaunch();
        cancelPendingRovinWarmResumeFallback();
        final String recoveryUrl = getRecoveryBrowserUrl(target);
        if (StringUtils.isEmpty(recoveryUrl)) {
            showRovinLanding();
            return;
        }

        if (mRovinLandingWidget != null && mRovinLandingWidget.isVisible()) {
            mRovinLandingWidget.hide(REMOVE_WIDGET);
            mRovinLandingWidget = null;
        }

        WindowWidget focusedWindow = mWindows.getFocusedWindow();
        if (focusedWindow == null || focusedWindow.getSession() == null) {
            showRovinLanding();
            return;
        }

        focusedWindow.setKioskMode(false);
        attachToWindow(focusedWindow, null);
        setPrimaryBrowserChromeVisible(true);
        focusedWindow.getSession().loadUri(recoveryUrl, WSession.LOAD_FLAGS_REPLACE_HISTORY);
    }

    private void setPrimaryBrowserChromeVisible(boolean visible) {
        if (mNavigationBar != null) {
            mNavigationBar.setVisible(visible);
            updateWidget(mNavigationBar);
        }
        if (mTray != null) {
            mTray.setVisible(visible);
            updateWidget(mTray);
        }
        if (mTabsBar != null) {
            mTabsBar.setVisible(visible);
            updateWidget(mTabsBar);
        }
    }

    @Nullable
    private RovinLaunchTarget resolveRovinLaunchTarget() {
        final boolean bundledPreferred = BuildConfig.ROVIN_BUNDLED_CONTENT_ENABLED;
        final boolean bundledAvailable = hasBundledGameAsset();

        if (bundledPreferred && bundledAvailable) {
            final String bundledUrl = getBundledGameUrl();
            if (StringUtils.isEmpty(bundledUrl)) {
                return RovinLaunchTarget.error(
                        getString(R.string.rovin_landing_error_missing_target),
                        true,
                        true);
            }
            return RovinLaunchTarget.ready(
                    addRovinBridgeCapabilities(bundledUrl),
                    getString(R.string.rovin_landing_description_bundled),
                    true,
                    true);
        }

        if (!StringUtils.isEmpty(BuildConfig.ROVIN_FIXED_TARGET_URL)) {
            final String status = bundledPreferred && !bundledAvailable
                    ? getString(R.string.rovin_landing_error_bundled_missing)
                    : getString(R.string.rovin_landing_description);
            return RovinLaunchTarget.ready(addRovinBridgeCapabilities(BuildConfig.ROVIN_FIXED_TARGET_URL), status,
                    bundledPreferred, bundledAvailable);
        }

        return RovinLaunchTarget.error(
                getString(R.string.rovin_landing_error_missing_target),
                bundledPreferred,
                bundledAvailable);
    }

    private boolean hasBundledGameAsset() {
        if (!BuildConfig.ROVIN_BUNDLED_CONTENT_ENABLED || StringUtils.isEmpty(BuildConfig.ROVIN_BUNDLED_ENTRY_PATH)) {
            return false;
        }

        try (InputStream ignored = getAssets().open(BuildConfig.ROVIN_BUNDLED_ENTRY_PATH)) {
            return true;
        } catch (IOException e) {
            Log.w(LOGTAG, "Bundled runtime asset is missing: " + BuildConfig.ROVIN_BUNDLED_ENTRY_PATH, e);
            return false;
        }
    }

    @Nullable
    private String getBundledGameUrl() {
        if (!hasBundledGameAsset()) {
            return null;
        }

        if (mRovinAssetHttpServer == null) {
            mRovinAssetHttpServer = new RovinAssetHttpServer(getAssets(), getBundledAssetRoot());
        }

        final String baseUrl = mRovinAssetHttpServer.start();
        if (StringUtils.isEmpty(baseUrl)) {
            Log.e(LOGTAG, "Failed to start bundled runtime asset server");
            return null;
        }

        return baseUrl + getBundledEntryRequestPath();
    }

    @Nullable
    private String getRecoveryBrowserUrl(@NonNull RovinLaunchTarget target) {
        if (!StringUtils.isEmpty(BuildConfig.ROVIN_FIXED_TARGET_URL)) {
            return BuildConfig.ROVIN_FIXED_TARGET_URL;
        }

        if (target.valid && !StringUtils.isEmpty(target.url)) {
            return target.url;
        }

        final String homepage = SettingsStore.getInstance(this).getHomepage();
        return StringUtils.isEmpty(homepage) ? null : homepage;
    }

    @NonNull
    private String addRovinBridgeCapabilities(@NonNull String url) {
        final Uri parsed = Uri.parse(url);
        final Uri.Builder builder = parsed.buildUpon()
                .appendQueryParameter(ROVIN_PAUSE_BRIDGE_QUERY_PARAM, "1")
                .appendQueryParameter(ROVIN_NATIVE_HAPTICS_BRIDGE_QUERY_PARAM, "1")
                .appendQueryParameter(ROVIN_SAVE_BRIDGE_QUERY_PARAM, "1")
                .appendQueryParameter(ROVIN_RUNTIME_QUERY_PARAM, "1");

        if (!parsed.getQueryParameterNames().contains(ROVIN_RENDER_SCALE_QUERY_PARAM)) {
            builder.appendQueryParameter(ROVIN_RENDER_SCALE_QUERY_PARAM, ROVIN_RENDER_SCALE_DEFAULT);
        }

        return builder.build().toString();
    }

    @NonNull
    private String getBundledAssetRoot() {
        final int separatorIndex = BuildConfig.ROVIN_BUNDLED_ENTRY_PATH.lastIndexOf('/');
        if (separatorIndex <= 0) {
            return "";
        }
        return BuildConfig.ROVIN_BUNDLED_ENTRY_PATH.substring(0, separatorIndex);
    }

    @NonNull
    private String getBundledEntryRequestPath() {
        final int separatorIndex = BuildConfig.ROVIN_BUNDLED_ENTRY_PATH.lastIndexOf('/');
        final String relativePath = separatorIndex >= 0
                ? BuildConfig.ROVIN_BUNDLED_ENTRY_PATH.substring(separatorIndex + 1)
                : BuildConfig.ROVIN_BUNDLED_ENTRY_PATH;
        return Uri.encode(relativePath, "/");
    }

    private void scheduleRovinAutoLaunch(@NonNull RovinLaunchTarget target) {
        if (!target.valid || StringUtils.isEmpty(target.url)) {
            return;
        }

        mPendingRovinAutoLaunch = () -> {
            mPendingRovinAutoLaunch = null;
            if (mRovinLandingWidget != null && mRovinLandingWidget.isVisible()) {
                launchRovinExperience(target);
            }
        };
        mHandler.postDelayed(mPendingRovinAutoLaunch, ROVIN_AUTO_LAUNCH_DELAY_MS);
    }

    private void cancelPendingRovinAutoLaunch() {
        if (mPendingRovinAutoLaunch != null) {
            mHandler.removeCallbacks(mPendingRovinAutoLaunch);
            mPendingRovinAutoLaunch = null;
        }
    }

    private ConnectivityReceiver.Delegate mConnectivityDelegate = connected -> {
        mConnectionAvailable = connected;
    };

    private void checkForCrash() {
        final ArrayList<String> files = CrashReporterService.findCrashFiles(getBaseContext());
        if (files.isEmpty()) {
            Log.d(LOGTAG, "No crash files found.");
            return;
        }
        boolean isCrashReportingEnabled = SettingsStore.getInstance(this).isCrashReportingEnabled();
        if (isCrashReportingEnabled) {
            SystemUtils.postCrashFiles(this, files);

        } else {
            if (mCrashDialog == null) {
                mCrashDialog = new CrashDialogWidget(this, files);
            }
            mCrashDialog.show(UIWidget.REQUEST_FOCUS);
        }
    }

    private void handleContentCrashIntent(@NonNull final Intent intent) {
        Log.e(LOGTAG, "Got content crashed intent");
        final String dumpFile = intent.getStringExtra(getCrashReportIntent().extra_minidump_path);
        final String extraFile = intent.getStringExtra(getCrashReportIntent().extra_extras_path);
        Log.d(LOGTAG, "Dump File: " + dumpFile);
        Log.d(LOGTAG, "Extras File: " + extraFile);
        Log.d(LOGTAG, "Fatal: " + intent.getBooleanExtra(getCrashReportIntent().extra_crash_fatal, false));

        boolean isCrashReportingEnabled = SettingsStore.getInstance(this).isCrashReportingEnabled();
        if (isCrashReportingEnabled) {
            SystemUtils.postCrashFiles(this, dumpFile, extraFile);

        } else {
            if (mCrashDialog == null) {
                mCrashDialog = new CrashDialogWidget(this, dumpFile, extraFile);
            }
            mCrashDialog.show(UIWidget.REQUEST_FOCUS);
        }
    }

    FrameLayout getWidgetContainer() {
        return mWidgetContainer;
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);

        // Determine which lifecycle or system event was raised.
        switch (level) {

            case ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN:
            case ComponentCallbacks2.TRIM_MEMORY_BACKGROUND:
            case ComponentCallbacks2.TRIM_MEMORY_MODERATE:
            case ComponentCallbacks2.TRIM_MEMORY_COMPLETE:
                // Curently ignore these levels. They are handled somewhere else.
                break;
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE:
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW:
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL:
                // It looks like these come in all at the same time so just always suspend
                // inactive Sessions.
                Log.i("VRB",
                        "RovinProduct: Memory pressure (TRIM_MEMORY_RUNNING_CRITICAL), suspending inactive sessions.");
                try {
                    if (SessionStore.get() != null) {
                        SessionStore.get().suspendAllInactiveSessions();
                    }
                } catch (Exception e) {
                    Log.e("VRB", "RovinProduct: Failed to suspend sessions during memory pressure: " + e.getMessage());
                }
                break;
            default:
                Log.e(LOGTAG, "onTrimMemory unknown level: " + level);
                break;
        }
    }

    private void showAppExitDialog() {
        mWindows.getFocusedWindow().showConfirmPrompt(
                getString(R.string.app_name),
                getString(R.string.exit_confirm_dialog_body, getString(R.string.app_name)),
                new String[] {
                        getString(R.string.exit_confirm_dialog_button_cancel),
                        getString(R.string.exit_confirm_dialog_button_quit),
                }, (index, isChecked) -> {
                    if (index == PromptDialogWidget.POSITIVE) {
                        VRBrowserActivity.super.onBackPressed();
                        finishAndRemoveTask();
                    }
                });
    }

    @Override
    @Deprecated
    public void onBackPressed() {
        if (mPlatformPlugin != null && mPlatformPlugin.onBackPressed()) {
            return;
        }
        if (mIsPresentingImmersive.getValue()) {
            queueRunnable(this::exitImmersiveNative);
            return;
        }
        if (mBackHandlers.size() > 0) {
            mBackHandlers.getLast().run();
            return;
        }
        if (!mWindows.handleBack()) {
            showAppExitDialog();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (isNotSpecialKey(event) && mKeyboard.dispatchKeyEvent(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    final Object mWaitLock = new Object();

    final Runnable mExitImmersive = new Runnable() {
        @Override
        public void run() {
            exitImmersiveNative();
            synchronized (mWaitLock) {
                mWaitLock.notifyAll();
            }
        }
    };

    private void exitImmersiveSync() {
        synchronized (mWaitLock) {
            queueRunnable(mExitImmersive);
            try {
                mWaitLock.wait();
            } catch (InterruptedException e) {
                Log.e(LOGTAG, "Waiting for exit immersive onPause interrupted");
            }
        }
    }

    @Keep
    @SuppressWarnings("unused")
    void dispatchCreateWidget(final int aHandle, final SurfaceTexture aTexture, final int aWidth, final int aHeight) {
        runOnUiThread(() -> {
            final Widget widget = mWidgets.get(aHandle);
            if (widget == null) {
                Log.e(LOGTAG, "Widget " + aHandle + " not found");
                return;
            }
            if (aTexture == null) {
                Log.d(LOGTAG,
                        "Widget: " + aHandle + " (" + aWidth + "x" + aHeight + ") received a null surface texture.");
            } else {
                Runnable aFirstDrawCallback = () -> {
                    if (!widget.isFirstPaintReady()) {
                        widget.setFirstPaintReady(true);
                        updateWidget(widget);
                    }
                };
                widget.setSurfaceTexture(aTexture, aWidth, aHeight, aFirstDrawCallback);
            }
            // Add widget to a virtual display for invalidation
            View view = (View) widget;
            if (view.getParent() == null) {
                mWidgetContainer.addView(view, new FrameLayout.LayoutParams(widget.getPlacement().viewWidth(),
                        widget.getPlacement().viewHeight()));
            }
        });
    }

    @Keep
    @SuppressWarnings("unused")
    void dispatchCreateWidgetLayer(final int aHandle, final Surface aSurface, final int aWidth, final int aHeight,
            final long aNativeCallback) {
        runOnUiThread(() -> {
            final Widget widget = mWidgets.get(aHandle);
            if (widget == null) {
                Log.e(LOGTAG, "Widget " + aHandle + " not found");
                return;
            }

            FinalizerRunnable firstDrawCallback = new FinalizerRunnable(() -> {
                if (aNativeCallback != 0) {
                    queueRunnable(() -> runCallbackNative(aNativeCallback));
                }
                if (aSurface != null && !widget.isFirstPaintReady()) {
                    widget.setFirstPaintReady(true);
                    updateWidget(widget);
                }
            },
                    () -> {
                        if (aNativeCallback != 0) {
                            queueRunnable(() -> deleteCallbackNative(aNativeCallback));
                        }
                    });

            widget.setSurface(aSurface, aWidth, aHeight, firstDrawCallback);

            UIWidget view = (UIWidget) widget;
            // Add widget to a virtual display for invalidation
            if (aSurface != null && view.getParent() == null) {
                mWidgetContainer.addView(view, new FrameLayout.LayoutParams(widget.getPlacement().viewWidth(),
                        widget.getPlacement().viewHeight()));
            } else if (aSurface == null && view.getParent() != null) {
                mWidgetContainer.removeView(view);
            }
            view.setResizing(false);
            view.postInvalidate();
        });
    }

    @Keep
    @SuppressWarnings("unused")
    void handleMotionEvent(final int aHandle, final int aDevice, final boolean aFocused, final boolean aPressed,
            final float aX, final float aY) {
        runOnUiThread(() -> {
            Widget widget = mWidgets.get(aHandle);

            if (!isWidgetInputEnabled(widget)) {
                widget = null; // Fallback to mRootWidget in order to allow world clicks to dismiss UI.
            }
            mLastMotionEventWidgetHandle = widget != null ? widget.getHandle() : 0;

            float scale = widget != null ? widget.getPlacement().textureScale
                    : SettingsStore.getInstance(this).getDisplayDpi() / 100.0f;
            // We shouldn't divide the scale factor when we pass the motion event to the web
            // engine
            if (widget instanceof WindowWidget) {
                WindowWidget windowWidget = (WindowWidget) widget;
                if (!windowWidget.isNativeContentVisible()) {
                    scale = 1.0f;
                }
            } else if (widget instanceof OverlayContentWidget) {
                scale = 1.0f;
            }
            final float x = aX / scale;
            final float y = aY / scale;

            if (widget == null) {
                MotionEventGenerator.dispatch(this, mRootWidget, aDevice, aFocused, aPressed, x, y);

            } else if (widget.getBorderWidth() > 0) {
                final int border = widget.getBorderWidth();
                MotionEventGenerator.dispatch(this, widget, aDevice, aFocused, aPressed, x - border, y - border);

            } else {
                MotionEventGenerator.dispatch(this, widget, aDevice, aFocused, aPressed, x, y);
            }
        });
    }

    @Keep
    @SuppressWarnings("unused")
    void handleScrollEvent(final int aHandle, final int aDevice, final float aX, final float aY) {
        runOnUiThread(() -> {
            Widget widget = mWidgets.get(aHandle);
            if (!isWidgetInputEnabled(widget)) {
                return;
            }
            if (widget == null) {
                if (getNavigationBar().isInVRVideo()) {
                    widget = getNavigationBar().getMediaControlsWidget();
                } else {
                    Log.e(LOGTAG, "Failed to find widget for scroll event: " + aHandle);
                    return;
                }
            }
            float scrollDirection = mSettings.getScrollDirection() == SettingsStore.SCROLL_DIRECTION_NATURAL ? 1.0f
                    : -1.0f;
            MotionEventGenerator.dispatchScroll(widget, aDevice, true, aX * scrollDirection, aY * scrollDirection);
        });
    }

    @Keep
    @SuppressWarnings("unused")
    void handleGesture(final int aType) {
        runOnUiThread(() -> {
            boolean consumed = false;
            if ((aType == GestureSwipeLeft) && (mLastGesture == GestureSwipeLeft)) {
                Log.d(LOGTAG, "Go back!");
                SessionStore.get().getActiveSession().goBack();

                consumed = true;
            } else if ((aType == GestureSwipeRight) && (mLastGesture == GestureSwipeRight)) {
                Log.d(LOGTAG, "Go forward!");
                SessionStore.get().getActiveSession().goForward();
                consumed = true;
            }
            if (mLastRunnable != null) {
                mLastRunnable.mCanceled = true;
                mLastRunnable = null;
            }
            if (consumed) {
                mLastGesture = NoGesture;

            } else {
                mLastGesture = aType;
                mLastRunnable = new SwipeRunnable();
                mHandler.postDelayed(mLastRunnable, SwipeDelay);
            }
        });
    }

    @SuppressWarnings({ "UnusedDeclaration" })
    @Keep
    void handleBack() {
        runOnUiThread(() -> {
            dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
            dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
        });
    }

    @SuppressWarnings({ "UnusedDeclaration" })
    @Keep
    void handleAppButton() {
        runOnUiThread(() -> {
            Session session = SessionStore.get().getActiveSession();
            if (session == null || session.getCurrentUri() == null || session.getCurrentUri().isBlank()) {
                handleBack();
                return;
            }

            session.loadUri(ROVIN_APP_BUTTON_JS, WSession.LOAD_FLAGS_REPLACE_HISTORY);
        });
    }

    @SuppressWarnings({ "UnusedDeclaration" })
    @Keep
    void handleAppExit() {
        runOnUiThread(() -> {
            showAppExitDialog();
        });
    }

    @Keep
    @SuppressWarnings({ "UnusedDeclaration" })
    void handleAudioPose(float qx, float qy, float qz, float qw, float px, float py, float pz) {
        mAudioEngine.setPose(qx, qy, qz, qw, px, py, pz);

        // https://developers.google.com/vr/reference/android/com/google/vr/sdk/audio/GvrAudioEngine.html#resume()
        // The initialize method must be called from the main thread at a regular rate.
        runOnUiThread(mAudioUpdateRunnable);
    }

    @Keep
    @SuppressWarnings("unused")
    void handleResize(final int aHandle, final float aWorldWidth, final float aWorldHeight) {
        runOnUiThread(() -> mWindows.getFocusedWindow().handleResizeEvent(aWorldWidth, aWorldHeight));
    }

    @Keep
    @SuppressWarnings("unused")
    void handleMoveEnd(final int aHandle, final float aDeltaX, final float aDeltaY, final float aDeltaZ,
            final float aRotation) {
        runOnUiThread(() -> {
            Widget widget = mWidgets.get(aHandle);
            if (widget != null) {
                widget.handleMoveEvent(aDeltaX, aDeltaY, aDeltaZ, aRotation);
            }
        });
    }

    @Keep
    @SuppressWarnings("unused")
    void registerExternalContext(long aContext) {
        EngineProvider.INSTANCE.getOrCreateRuntime(this).setExternalVRContext(aContext);
    }

    final Object mCompositorLock = new Object();

    class PauseCompositorRunnable implements Runnable {
        public boolean done;

        @Override
        public void run() {
            synchronized (mCompositorLock) {
                Log.d(LOGTAG, "About to pause Compositor");
                mWindows.pauseCompositor();
                Log.d(LOGTAG, "Compositor Paused");
                done = true;
                mCompositorLock.notify();
            }
        }
    }

    @Keep
    @SuppressWarnings("unused")
    void onEnterWebXR() {
        if (Thread.currentThread() == mUiThread) {
            return;
        }
        mIsPresentingImmersive.postValue(true);
        mLaunchImmersive = true; // ROVIN: Persist immersive state for warm-starts
        runOnUiThread(() -> {
            mWindows.enterImmersiveMode();
            for (WebXRListener listener : mWebXRListeners) {
                listener.onEnterWebXR();
            }
        });
        TelemetryService.startImmersive();

        PauseCompositorRunnable runnable = new PauseCompositorRunnable();

        synchronized (mCompositorLock) {
            runOnUiThread(runnable);
            while (!runnable.done) {
                try {
                    mCompositorLock.wait();
                } catch (InterruptedException e) {
                    Log.e(LOGTAG, "Waiting for compositor pause interrupted");
                }
            }
        }
    }

    @Keep
    @SuppressWarnings("unused")
    void onExitWebXR(long aCallback) {
        if (Thread.currentThread() == mUiThread) {
            return;
        }
        mIsPresentingImmersive.postValue(false);
        TelemetryService.stopImmersive();

        if (mLaunchImmersive && !mIsBackgrounding
                && getLifecycle().getCurrentState() != androidx.lifecycle.Lifecycle.State.RESUMED) {
            Log.d(LOGTAG, "Launched in immersive mode: exiting WebXR will finish the app");
            finish();
        }

        runOnUiThread(() -> {
            mWindows.exitImmersiveMode();
            for (WebXRListener listener : mWebXRListeners) {
                listener.onExitWebXR();
            }
        });

        // Show the window in front of you when you exit immersive mode.
        recenterUIYaw(WidgetManagerDelegate.YAW_TARGET_ALL);

        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> {
            if (!mWindows.isPaused()) {
                Log.d(LOGTAG, "Compositor resume begin");
                mWindows.resumeCompositor();
                if (aCallback != 0) {
                    queueRunnable(() -> runCallbackNative(aCallback));
                }
                Log.d(LOGTAG, "Compositor resume end");
            }
        }, 20);
    }

    @Keep
    @SuppressWarnings("unused")
    void onDismissWebXRInterstitial() {
        runOnUiThread(() -> {
            for (WebXRListener listener : mWebXRListeners) {
                listener.onDismissWebXRInterstitial();
            }
        });
    }

    @Keep
    @SuppressWarnings("unused")
    void onWebXRRenderStateChange(boolean aRendering) {
        runOnUiThread(() -> {
            for (WebXRListener listener : mWebXRListeners) {
                listener.onWebXRRenderStateChange(aRendering);
            }
        });
    }

    @Keep
    @SuppressWarnings("unused")
    void renderPointerLayer(final Surface aSurface, int color, final long aNativeCallback) {
        runOnUiThread(() -> {
            try {
                Canvas canvas = aSurface.lockHardwareCanvas();
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                Paint paint = new Paint();
                paint.setAntiAlias(true);
                paint.setDither(true);
                paint.setColor(color);
                paint.setStyle(Paint.Style.FILL);
                final float x = canvas.getWidth() * 0.5f;
                final float y = canvas.getHeight() * 0.5f;
                final float radius = canvas.getWidth() * 0.4f;
                canvas.drawCircle(x, y, radius, paint);
                paint.setColor(Color.BLACK);
                paint.setStrokeWidth(4);
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawCircle(x, y, radius, paint);
                aSurface.unlockCanvasAndPost(canvas);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            if (aNativeCallback != 0) {
                queueRunnable(() -> runCallbackNative(aNativeCallback));
            }
        });
    }

    @Keep
    @SuppressWarnings("unused")
    String getStorageAbsolutePath() {
        final File path = getExternalFilesDir(null);
        if (path == null) {
            return "";
        }
        return path.getAbsolutePath();
    }

    @Keep
    @SuppressWarnings("unused")
    public boolean isOverrideEnvPathEnabled() {
        return SettingsStore.getInstance(this).isEnvironmentOverrideEnabled();
    }

    @Keep
    @SuppressWarnings("unused")
    public void checkTogglePassthrough() {
        if (mSettings.isStartWithPassthroughEnabled() && !mIsPassthroughEnabled) {
            runOnUiThread(this::togglePassthrough);
        }
    }

    @Keep
    @SuppressWarnings("unused")
    public void resetWindowsPosition() {
        // Reset the position of the windows when we are not in headlock.
        if (mSettings.isHeadLockEnabled())
            return;
        runOnUiThread(() -> mWindows.resetWindowsPosition());
    }

    @Keep
    @SuppressWarnings("unused")
    public String getActiveEnvironment() {
        return getServicesProvider().getEnvironmentsManager().getOrDownloadEnvironment();
    }

    @Keep
    @SuppressWarnings("unused")
    public int getPointerColor() {
        return SettingsStore.getInstance(this).getPointerColor();
    }

    private void setUseHardwareAcceleration() {
        UISurfaceTextureRenderer.setUseHardwareAcceleration(
                SettingsStore.getInstance(getBaseContext()).isUIHardwareAccelerationEnabled());
    }

    @Keep
    @SuppressWarnings("unused")
    private void setDeviceType(int aType) {
        DeviceType.setType(aType);
        setUseHardwareAcceleration();
    }

    @Keep
    @SuppressWarnings("unused")
    private void haltActivity(final int aReason) {
        runOnUiThread(() -> {
            if (mConnectionAvailable && mWindows.getFocusedWindow() != null) {
                mWindows.getFocusedWindow().showAlert(
                        getString(R.string.not_entitled_title),
                        getString(R.string.not_entitled_message, getString(R.string.app_name)),
                        (index, isChecked) -> finish());
            }
        });
    }

    @Keep
    @SuppressWarnings("unused")
    private void handlePoorPerformance() {
        runOnUiThread(() -> {
            if (!mSettings.isPerformanceMonitorEnabled()) {
                return;
            }
            // Don't block poorly performing immersive pages.
            if (mIsPresentingImmersive.getValue()) {
                return;
            }
            WindowWidget window = mWindows.getFocusedWindow();
            if (window == null || window.getSession() == null) {
                return;
            }
            final String originalUri = window.getSession().getCurrentUri();
            if (mPoorPerformanceAllowList.contains(originalUri)) {
                return;
            }
            window.getSession().loadHomePage();
            final String[] buttons = { getString(R.string.ok_button), getString(R.string.performance_unblock_page) };
            window.showConfirmPrompt(getString(R.string.performance_title),
                    getString(R.string.performance_message),
                    buttons,
                    (index, isChecked) -> {
                        if (index == PromptDialogWidget.NEGATIVE) {
                            mPoorPerformanceAllowList.add(originalUri);
                            window.getSession().loadUri(originalUri);
                        }
                    });
        });
    }

    @Keep
    @SuppressWarnings("unused")
    private void onAppLink(String aJSON) {
        runOnUiThread(() -> {
            try {
                JSONObject object = new JSONObject(aJSON);
                String uri = object.optString(EXTRA_URL);
                Session session = SessionStore.get().getActiveSession();
                if (!StringUtils.isEmpty(uri) && session != null) {
                    session.loadUri(uri);
                }

            } catch (Exception ex) {
                Log.e(LOGTAG, "Error parsing app link JSON: " + ex.toString());
            }

        });
    }

    @Keep
    @SuppressWarnings("unused")
    private void disableLayers() {
        runOnUiThread(() -> {
            SettingsStore.getInstance(this).setDisableLayers(true);
        });
    }

    @Keep
    @SuppressWarnings("unused")
    private void appendAppNotesToCrashReport(String aNotes) {
        runOnUiThread(() -> EngineProvider.INSTANCE.getOrCreateRuntime(VRBrowserActivity.this)
                .appendAppNotesToCrashReport(aNotes));
    }

    @Keep
    @SuppressWarnings("unused")
    private void updateControllerBatteryLevels(final int leftLevel, final int rightLevel) {
        runOnUiThread(() -> updateBatteryLevels(leftLevel, rightLevel));
    }

    private void updateBatteryLevels(final int leftLevel, final int rightLevel) {
        long currentTime = System.nanoTime();
        if (((currentTime - mLastBatteryUpdate) >= BATTERY_UPDATE_INTERVAL) || mLastBatteryLevel == -1) {
            mLastBatteryUpdate = currentTime;
            BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
            mLastBatteryLevel = bm == null ? 100 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        }

        Intent intent = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            intent = this.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            intent = this.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        }
        int plugged = intent == null ? -1 : intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
        boolean isCharging = plugged == BatteryManager.BATTERY_PLUGGED_AC
                || plugged == BatteryManager.BATTERY_PLUGGED_USB || plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS;
        mTray.setBatteryLevels(mLastBatteryLevel, isCharging, leftLevel, rightLevel);
    }

    private boolean shouldForwardRovinFocusToPage() {
        return mRovinImmersiveActiveConfirmed
                || (mIsPresentingImmersive != null && Boolean.TRUE.equals(mIsPresentingImmersive.getValue()));
    }

    @Keep
    @SuppressWarnings("unused")
    private void onAppFocusChanged(final boolean aIsFocused) {
        runOnUiThread(() -> {
            mRovinNativeVrFocused = aIsFocused;
            if (!aIsFocused) {
                cancelPendingRovinStartupBridge();
                if (mRovinReady && !mRovinTransitionTriggered && !mRovinImmersiveActiveConfirmed) {
                    mRovinPendingNativeFocusStart = true;
                }
            } else {
                primeRovinRuntimeControllerInput("native-focus-gained");
            }
            Session session = SessionStore.get().getActiveSession();
            if (session == null || session.getCurrentUri() == null || session.getCurrentUri().isBlank()) {
                return;
            }

            if (aIsFocused && mRovinPendingNativeFocusStart && mRovinReady
                    && !mRovinTransitionTriggered && !mRovinImmersiveActiveConfirmed) {
                Log.i(LOGTAG, "Rovin Runtime: Native VR focus gained; scheduling deferred JS startup bridge.");
                WindowWidget focusedWindow = mWindows != null ? mWindows.getFocusedWindow() : null;
                scheduleRovinStartOnFocusedWindow(focusedWindow, "native-vr-focus");
            }

            if (!shouldForwardRovinFocusToPage()) {
                Log.d("VRB", "RovinProduct: Native focus changed before immersive gameplay; not forwarding to page. focused=" + aIsFocused);
                return;
            }

            if (aIsFocused) {
                Log.i("VRB", "RovinProduct: Focus GAINED");
                if (session != null && !session.isShutdown()) {
                    session.loadUri(ROVIN_APP_FOCUS_GAINED_JS, WSession.LOAD_FLAGS_REPLACE_HISTORY);
                }
            } else {
                Log.i("VRB", "RovinProduct: Focus LOST");
                if (session != null && !session.isShutdown()) {
                    session.loadUri(ROVIN_APP_FOCUS_LOST_JS, WSession.LOAD_FLAGS_REPLACE_HISTORY);
                }
            }

            if (session.getActiveVideo() == null || !session.getActiveVideo().isActive())
                return;
            if (aIsFocused) {
                if (mPrevActiveMedia != null && mPrevActiveMedia == session.getActiveVideo())
                    mPrevActiveMedia.play();
            } else if (session.getActiveVideo().isPlaying()) {
                mPrevActiveMedia = session.getActiveVideo();
                mPrevActiveMedia.pause();
            }
        });
    }

    @Keep
    @SuppressWarnings("unused")
    private void setEyeTrackingSupported(final boolean isSupported) {
        mIsEyeTrackingSupported = isSupported;
    }

    @Keep
    @SuppressWarnings("unused")
    private void setHandTrackingSupported(final boolean isSupported) {
        mIsHandTrackingSupported = isSupported;
        primeRovinRuntimeControllerInput("hand-tracking-support");
    }

    @Keep
    @SuppressWarnings("unused")
    private void onControllersAvailable() {
        mAreControllersAvailable = true;
        primeRovinRuntimeControllerInput("controllers-available");
    }

    private void primeRovinRuntimeControllerInput(@NonNull String reason) {
        if (!BuildConfig.ROVIN_BUNDLED_CONTENT_ENABLED) {
            return;
        }

        if (mIsHandTrackingEnabled) {
            Log.i(LOGTAG, "Rovin Runtime: Disabling native hand tracking for controller-first input. reason=" + reason);
            setHandTrackingEnabled(false);
        }

        for (long delayMs : ROVIN_INPUT_PRIME_DELAYS_MS) {
            mHandler.postDelayed(() -> {
                WindowWidget focusedWindow = mWindows != null ? mWindows.getFocusedWindow() : null;
                if (focusedWindow == null || !focusedWindow.isVisible()) {
                    return;
                }
                Log.d(LOGTAG, "Rovin Runtime: Priming focused window input. reason=" + reason);
                focusedWindow.requestFocus();
                focusedWindow.requestFocusFromTouch();
            }, delayMs);
        }
    }

    private SurfaceTexture createSurfaceTexture() {
        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0]);

        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            Log.e(LOGTAG, "OpenGL Error creating SurfaceTexture: " + error);
        }

        return new SurfaceTexture(ids[0]);
    }

    void createOffscreenDisplay() {
        final SurfaceTexture texture = createSurfaceTexture();
        runOnUiThread(() -> {
            mOffscreenDisplay = new OffscreenDisplay(VRBrowserActivity.this, texture, 16, 16);
            mOffscreenDisplay.setContentView(mWidgetContainer);
        });
    }

    void createCaptureSurface() {
        final SurfaceTexture texture = createSurfaceTexture();
        runOnUiThread(() -> {
            SettingsStore settings = SettingsStore.getInstance(this);
            texture.setDefaultBufferSize(settings.getWindowWidth(), settings.getWindowHeight());
            BitmapCache.getInstance(this).setCaptureSurface(texture);
        });
    }

    @Override
    public int newWidgetHandle() {
        return mWidgetHandleIndex++;
    }

    public void addWidgets(final Iterable<? extends Widget> aWidgets) {
        for (Widget widget : aWidgets) {
            addWidget(widget);
        }
    }

    private void updateActiveDialog(final Widget aWidget) {
        if (!aWidget.isDialog()) {
            return;
        }

        if (aWidget.isVisible()) {
            mActiveDialog = aWidget;
        } else if (aWidget == mActiveDialog && !aWidget.isVisible()) {
            mActiveDialog = null;
        }
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean isWidgetInputEnabled(Widget aWidget) {
        return mActiveDialog == null || aWidget == null || mActiveDialog == aWidget
                || aWidget instanceof KeyboardWidget;
    }

    // WidgetManagerDelegate
    @Override
    public void addWidget(Widget aWidget) {
        if (aWidget == null) {
            return;
        }
        mWidgets.put(aWidget.getHandle(), aWidget);
        ((View) aWidget).setVisibility(aWidget.getPlacement().visible ? View.VISIBLE : View.GONE);
        final int handle = aWidget.getHandle();
        final WidgetPlacement clone = aWidget.getPlacement().clone();
        queueRunnable(() -> addWidgetNative(handle, clone));
        updateActiveDialog(aWidget);
    }

    private void enqueueUpdateWidgetNativeCall(int handle, WidgetPlacement placement) {
        mPendingNativeWidgetUpdates.put(handle, placement);

        if (mNativeWidgetUpdatesTask == null || mNativeWidgetUpdatesTask.isDone()) {
            mNativeWidgetUpdatesTask = mPendingNativeWidgetUpdatesExecutor.schedule(() -> {
                for (Map.Entry<Integer, WidgetPlacement> entry : mPendingNativeWidgetUpdates.entrySet()) {
                    queueRunnable(() -> updateWidgetNative(entry.getKey(), entry.getValue()));
                }
                mPendingNativeWidgetUpdates.clear();
            }, UPDATE_NATIVE_WIDGETS_DELAY, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void updateWidget(final Widget aWidget) {
        if (aWidget == null) {
            return;
        }
        // Enqueue widget update calls in order to batch updates on the same widget. If
        // a widget
        // updates several times in a short period of time, it's enough to call the
        // native
        // method just once. This effectively reduces the amount of XR layer
        // creation/destruction.
        enqueueUpdateWidgetNativeCall(aWidget.getHandle(), aWidget.getPlacement().clone());

        final int textureWidth = aWidget.getPlacement().textureWidth();
        final int textureHeight = aWidget.getPlacement().textureHeight();
        final int viewWidth = aWidget.getPlacement().viewWidth();
        final int viewHeight = aWidget.getPlacement().viewHeight();

        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) ((View) aWidget).getLayoutParams();
        if (params == null) {
            // Widget not added yet
            return;
        }
        UIWidget view = (UIWidget) aWidget;

        if (params.width != viewWidth || params.height != viewHeight) {
            params.width = viewWidth;
            params.height = viewHeight;
            if (view.isLayer()) {
                // Reuse last frame and do not render while resizing surface with Layers
                // enabled.
                // Fixes resizing glitches.
                view.setResizing(true);
            }
            ((View) aWidget).setLayoutParams(params);
            aWidget.resizeSurface(textureWidth, textureHeight);
        }

        boolean visible = aWidget.getPlacement().visible;

        if (visible != (view.getVisibility() == View.VISIBLE)) {
            view.setVisibility(visible ? View.VISIBLE : View.GONE);
        }

        for (UpdateListener listener : mWidgetUpdateListeners) {
            listener.onWidgetUpdate(aWidget);
        }
        updateActiveDialog(aWidget);
    }

    @Override
    public void removeWidget(final Widget aWidget) {
        if (aWidget == null) {
            return;
        }
        mWidgets.remove(aWidget.getHandle());
        mWidgetContainer.removeView((View) aWidget);
        aWidget.setFirstPaintReady(false);
        queueRunnable(() -> removeWidgetNative(aWidget.getHandle()));
        if (aWidget == mActiveDialog) {
            mActiveDialog = null;
        }
    }

    @Override
    public void updateWidgetsPlacementTranslationZ() {
        for (Widget widget : mWidgets.values()) {
            widget.getPlacement().updateCylinderMapRadius();
            widget.updatePlacementTranslationZ();
            updateWidget(widget);
        }
    }

    @Override
    public void updateVisibleWidgets() {
        queueRunnable(this::updateVisibleWidgetsNative);
    }

    @Override
    public void recreateWidgetSurface(Widget aWidget) {
        queueRunnable(() -> recreateWidgetSurfaceNative(aWidget.getHandle()));
    }

    @Override
    public void startWidgetResize(final WindowWidget aWidget) {
        if (aWidget == null) {
            return;
        }
        mWindows.enterResizeMode();
        Pair<Float, Float> maxSize = aWidget.getMaxWorldSize();
        Pair<Float, Float> minSize = aWidget.getMinWorldSize();
        queueRunnable(() -> startWidgetResizeNative(aWidget.getHandle(), maxSize.first, maxSize.second, minSize.first,
                minSize.second));
    }

    @Override
    public void finishWidgetResize(final WindowWidget aWidget) {
        if (aWidget == null) {
            return;
        }
        mWindows.exitResizeMode();
        queueRunnable(() -> finishWidgetResizeNative(aWidget.getHandle()));
    }

    @Override
    public void startWidgetMove(final Widget aWidget, @WidgetMoveBehaviourFlags int aMoveBehaviour) {
        if (aWidget == null) {
            return;
        }
        queueRunnable(() -> startWidgetMoveNative(aWidget.getHandle(), aMoveBehaviour));
    }

    @Override
    public void finishWidgetMove() {
        queueRunnable(this::finishWidgetMoveNative);
    }

    @Override
    public void startWindowMove() {
        SettingsStore.getInstance(this).setHeadLockEnabled(false);
        setLockMode(WidgetManagerDelegate.CONTROLLER_LOCK);
    }

    @Override
    public void finishWindowMove() {
        setLockMode(WidgetManagerDelegate.NO_LOCK);
    }

    @Override
    public void addUpdateListener(@NonNull UpdateListener aUpdateListener) {
        if (!mWidgetUpdateListeners.contains(aUpdateListener)) {
            mWidgetUpdateListeners.add(aUpdateListener);
        }
    }

    @Override
    public void removeUpdateListener(@NonNull UpdateListener aUpdateListener) {
        mWidgetUpdateListeners.remove(aUpdateListener);
    }

    @Override
    public void addPermissionListener(PermissionListener aListener) {
        if (!mPermissionListeners.contains(aListener)) {
            mPermissionListeners.add(aListener);
        }
    }

    @Override
    public void removePermissionListener(PermissionListener aListener) {
        mPermissionListeners.remove(aListener);
    }

    @Override
    public void addFocusChangeListener(@NonNull FocusChangeListener aListener) {
        if (!mFocusChangeListeners.contains(aListener)) {
            mFocusChangeListeners.add(aListener);
        }
    }

    @Override
    public void removeFocusChangeListener(@NonNull FocusChangeListener aListener) {
        mFocusChangeListeners.remove(aListener);
    }

    @Override
    public void addWorldClickListener(WorldClickListener aListener) {
        if (!mWorldClickListeners.contains(aListener)) {
            mWorldClickListeners.add(aListener);
        }
    }

    @Override
    public void removeWorldClickListener(WorldClickListener aListener) {
        mWorldClickListeners.remove(aListener);
    }

    @Override
    public void addWebXRListener(WebXRListener aListener) {
        mWebXRListeners.add(aListener);
    }

    @Override
    public void removeWebXRListener(WebXRListener aListener) {
        mWebXRListeners.remove(aListener);
    }

    @Override
    public void setWebXRIntersitialState(@WebXRInterstitialState int aState) {
        queueRunnable(() -> setWebXRIntersitialStateNative(aState));
    }

    @Override
    public boolean isWebXRIntersitialHidden() {
        return mHideWebXRIntersitial;
    }

    @Override
    public boolean isWebXRPresenting() {
        return mIsPresentingImmersive.getValue();
    }

    @Override
    public boolean isLaunchImmersive() {
        // This method may be called before we have parsed the current Intent.
        return mLaunchImmersive ||
                (getIntent() != null && getIntent().getBooleanExtra(EXTRA_LAUNCH_IMMERSIVE, false)
                        && getIntent().getStringExtra(EXTRA_LAUNCH_IMMERSIVE_ELEMENT_XPATH) != null);
    }

    @Override
    public void pushBackHandler(@NonNull Runnable aRunnable) {
        mBackHandlers.addLast(aRunnable);
    }

    @Override
    public void popBackHandler(@NonNull Runnable aRunnable) {
        mBackHandlers.removeLastOccurrence(aRunnable);
    }

    @Override
    public void pushWorldBrightness(Object aKey, float aBrightness) {
        if (mCurrentBrightness.second != aBrightness) {
            queueRunnable(() -> setWorldBrightnessNative(aBrightness));
        }
        mBrightnessQueue.add(mCurrentBrightness);
        mCurrentBrightness = Pair.create(aKey, aBrightness);
    }

    @Override
    public void setWorldBrightness(Object aKey, final float aBrightness) {
        if (mCurrentBrightness.first == aKey) {
            if (mCurrentBrightness.second != aBrightness) {
                mCurrentBrightness = Pair.create(aKey, aBrightness);
                queueRunnable(() -> setWorldBrightnessNative(aBrightness));
            }
        } else {
            for (int i = mBrightnessQueue.size() - 1; i >= 0; --i) {
                if (mBrightnessQueue.get(i).first == aKey) {
                    mBrightnessQueue.set(i, Pair.create(aKey, aBrightness));
                    break;
                }
            }
        }
    }

    @Override
    public void popWorldBrightness(Object aKey) {
        if (mBrightnessQueue.size() == 0) {
            return;
        }
        if (mCurrentBrightness.first == aKey) {
            float brightness = mCurrentBrightness.second;
            mCurrentBrightness = mBrightnessQueue.removeLast();
            if (mCurrentBrightness.second != brightness) {
                queueRunnable(() -> setWorldBrightnessNative(mCurrentBrightness.second));
            }

            return;
        }
        for (int i = mBrightnessQueue.size() - 1; i >= 0; --i) {
            if (mBrightnessQueue.get(i).first == aKey) {
                mBrightnessQueue.remove(i);
                break;
            }
        }
    }

    @Override
    public void triggerHapticFeedback(int controllerId) {
        SettingsStore settings = SettingsStore.getInstance(this);
        if (settings.isHapticFeedbackEnabled()) {
            queueRunnable(() -> triggerHapticFeedbackNative(settings.getHapticPulseDuration(),
                    settings.getHapticPulseIntensity(), controllerId));
        }
    }

    public void triggerHapticPulse(float pulseDuration, float pulseIntensity, int controllerId) {
        queueRunnable(() -> triggerHapticFeedbackNative(pulseDuration, pulseIntensity, controllerId));
    }

    @Override
    public void setControllersVisible(final boolean aVisible) {
        queueRunnable(() -> setControllersVisibleNative(aVisible));
    }

    @Override
    public void keyboardDismissed() {
        if (RovinProduct.shouldShowVoiceSearchSettings()) {
            mNavigationBar.showVoiceSearch();
        }
    }

    @Override
    public void updateEnvironment() {
        queueRunnable(this::updateEnvironmentNative);
    }

    @Override
    public void updateKeyboardDictionary() {
        mKeyboard.updateDictionary();
    }

    @Override
    public void updatePointerColor() {
        queueRunnable(this::updatePointerColorNative);
    }

    @Override
    public boolean isPermissionGranted(@NonNull String permission) {
        return mPermissionDelegate.isPermissionGranted(permission);
    }

    @Override
    public void requestPermission(String originator, @NonNull String permission, OriginatorType originatorType,
            WSession.PermissionDelegate.Callback aCallback) {
        Session session = SessionStore.get().getActiveSession();
        if (originatorType == OriginatorType.WEBSITE) {
            mPermissionDelegate.onWebsitePermissionRequest(session.getWSession(), originator, permission, aCallback);
        } else {
            mPermissionDelegate.onAndroidPermissionsRequest(session.getWSession(), new String[] { permission },
                    aCallback);
        }
    }

    @Override
    @Deprecated
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        runOnUiThread(() -> {
            for (PermissionListener listener : mPermissionListeners) {
                listener.onRequestPermissionsResult(requestCode, permissions, grantResults);
            }
        });
    }

    @Override
    public void showVRVideo(final int aWindowHandle,
            final @VideoProjectionMenuWidget.VideoProjectionFlags int aVideoProjection) {
        if (mSettings.isHeadLockEnabled()) {
            mSettings.setHeadLockEnabled(false);
            shouldRestoreHeadLockOnVRVideoExit = true;
        }
        queueRunnable(() -> showVRVideoNative(aWindowHandle, aVideoProjection));
    }

    @Override
    public void hideVRVideo() {
        queueRunnable(this::hideVRVideoNative);

        if (shouldRestoreHeadLockOnVRVideoExit) {
            mSettings.setHeadLockEnabled(true);
        }
    }

    @Override
    public void togglePassthrough() {
        mIsPassthroughEnabled = !mIsPassthroughEnabled;
        queueRunnable(() -> togglePassthroughNative());
    }

    @Override
    public boolean isPassthroughEnabled() {
        return mIsPassthroughEnabled;
    }

    @Override
    public boolean isPassthroughSupported() {
        return DeviceType.isOculusBuild() || DeviceType.isLynx() || DeviceType.isSnapdragonSpaces()
                || DeviceType.isPicoXR();
    }

    @Override
    public boolean areControllersAvailable() {
        return mAreControllersAvailable;
    }

    @Override
    public boolean isPageZoomEnabled() {
        return BuildConfig.ENABLE_PAGE_ZOOM;
    }

    @Override
    public void setLockMode(@LockMode int lockMode) {
        queueRunnable(() -> setLockEnabledNative(lockMode));
    }

    @Override
    public void recenterUIYaw(@YawTarget int aTarget) {
        queueRunnable(() -> recenterUIYawNative(aTarget));
    }

    @Override
    public void setCylinderDensity(final float aDensity) {
        if (mWindows != null && aDensity == 0.0f && mWindows.getWindowsCount() > 1) {
            return;
        }
        setCylinderDensityForce(aDensity);
    }

    @Override
    public void setCylinderDensityForce(final float aDensity) {
        mCurrentCylinderDensity = aDensity;
        queueRunnable(() -> setCylinderDensityNative(aDensity));
        if (mWindows != null) {
            mWindows.updateCurvedMode(false);
        }
    }

    @Override
    public void setCenterWindows(boolean isCenterWindows) {
        if (mWindows != null) {
            mWindows.setCenterWindows(isCenterWindows);
            updateVisibleWidgets();
        }
    }

    @Override
    public float getCylinderDensity() {
        return mCurrentCylinderDensity;
    }

    @Override
    public boolean canOpenNewWindow() {
        return mWindows.canOpenNewWindow();
    }

    @Override
    public void openNewWindow(String uri) {
        WindowWidget newWindow = mWindows.addWindow();
        if ((newWindow != null) && (newWindow.getSession() != null)) {
            newWindow.getSession().loadUri(uri);
        }
    }

    @Override
    public void openNewTab(@NonNull String uri) {
        mWindows.addBackgroundTab(mWindows.getFocusedWindow(), uri);
    }

    @Override
    public void openNewTabForeground(@NonNull String uri) {
        mWindows.addTab(mWindows.getFocusedWindow(), uri);
    }

    private boolean openNewTabNoInterrupt(@NonNull WindowWidget window, @NonNull String uri) {
        if (window.getSession() == null || window.getSession().getActiveVideo() != null) {
            return false;
        }

        mWindows.addTab(window, uri);
        mWindows.focusWindow(window);
        return true;
    }

    @Override
    public void openNewPageNoInterrupt(@NonNull String uri) {
        if (openNewTabNoInterrupt(mWindows.getFocusedWindow(), uri)) {
            return;
        }

        // If we have video playing in current window, ensure we don't open a new tab
        // in a window that has active video
        if (mWindows.getWindowsCount() > 1) {
            for (WindowWidget window : mWindows.getCurrentWindows()) {
                if (openNewTabNoInterrupt(window, uri)) {
                    return;
                }
            }
        }
        // All the current opened Windows have video playing, so we have to open uri in
        // a new window.
        // If we have maximum window number, then open the uri as a new tab in current
        // window.
        if (canOpenNewWindow()) {
            openNewWindow(uri);
        } else {
            openNewTabForeground(uri);
        }
    }

    @Override
    public WindowWidget getFocusedWindow() {
        return mWindows.getFocusedWindow();
    }

    @Override
    public TrayWidget getTray() {
        return mTray;
    }

    @Override
    public NavigationBarWidget getNavigationBar() {
        return mNavigationBar;
    }

    @Override
    public Windows getWindows() {
        return mWindows;
    }

    @Override
    public void saveState() {
        mWindows.saveState();
    }

    @Override
    public void updateLocale(@NonNull Context context) {
        onConfigurationChanged(context.getResources().getConfiguration());
        getApplication().onConfigurationChanged(context.getResources().getConfiguration());
    }

    @Override
    public void setPointerMode(@PointerMode int mode) {
        queueRunnable(() -> setPointerModeNative(mode));
    }

    @Override
    public void setHandTrackingEnabled(boolean value) {
        mIsHandTrackingEnabled = value;
        queueRunnable(() -> setHandTrackingEnabledNative(value));
    }

    @Override
    public boolean isHandTrackingEnabled() {
        return mIsHandTrackingEnabled;
    }

    @Override
    public boolean isHandTrackingSupported() {
        return mIsHandTrackingSupported;
    }

    @Override
    @NonNull
    public AppServicesProvider getServicesProvider() {
        return (AppServicesProvider) getApplication();
    }

    @Override
    public KeyboardWidget getKeyboard() {
        return mKeyboard;
    }

    @Override
    public void onPlatformScrollEvent(float distanceX, float distanceY) {
        float SCROLL_SCALE = 32;
        handleScrollEvent(mLastMotionEventWidgetHandle, 0, distanceX / SCROLL_SCALE, distanceY / SCROLL_SCALE);
    }

    @Override
    public void checkEyeTrackingPermissions(@NonNull EyeTrackingCallback callback) {
        if (isPermissionGranted(getEyeTrackingPermissionString())) {
            callback.onEyeTrackingPermissionRequest(true);
            return;
        }

        PromptDialogWidget dialog = new PromptDialogWidget(this);
        dialog.setTitle(R.string.eye_tracking_permission_title);
        dialog.setDescription(R.string.eye_tracking_permission_message);
        dialog.setButtons(new int[] { R.string.ok_button });
        dialog.setCheckboxVisible(false);
        dialog.setIcon(R.drawable.mozac_ic_warning_fill_24);
        dialog.setButtonsDelegate((index, isChecked) -> {
            dialog.hide(UIWidget.REMOVE_WIDGET);
            dialog.releaseWidget();
            requestPermission(null, getEyeTrackingPermissionString(), OriginatorType.APPLICATION,
                    new WSession.PermissionDelegate.Callback() {
                        @Override
                        public void grant() {
                            callback.onEyeTrackingPermissionRequest(true);
                        }

                        @Override
                        public void reject() {
                            callback.onEyeTrackingPermissionRequest(false);
                        }
                    });
        });
        dialog.show(UIWidget.REQUEST_FOCUS);
    }

    @Override
    public boolean isEyeTrackingSupported() {
        return mIsEyeTrackingSupported;
    }

    @Keep
    @SuppressWarnings("unused")
    private void changeWindowDistance(float aDelta) {
        float increment = 0.05f;
        float clamped = Math.max(0.0f,
                Math.min(mSettings.getWindowDistance() + (aDelta > 0 ? increment : -increment), 1.0f));
        mSettings.setWindowDistance(clamped);
    }

    private boolean supportsCompositionLayers() {
        assert (mMaxCompositionLayers.isPresent());
        return mMaxCompositionLayers.getAsInt() > 1;
    }

    @Keep
    @SuppressWarnings("unused")
    private void onMaxCompositionLayersAvailable(int aNumLayers) {
        assert (!mMaxCompositionLayers.isPresent());
        mMaxCompositionLayers = OptionalInt.of(aNumLayers);
        boolean supportsLayers = supportsCompositionLayers();
        Log.i(LOGTAG, "Max composition layers: " + aNumLayers + " so " + (supportsLayers ? "enabling" : "disabling")
                + " layers support");
        runOnUiThread(() -> {
            for (CheckCompositionLayersCallback callback : mCompositionLayersPendingCallbacks) {
                callback.onCompositionLayersSupport(supportsLayers);
            }
            mCompositionLayersPendingCallbacks.clear();
        });
    }

    @Override
    public void checkCompositionLayersSupported(@NonNull CheckCompositionLayersCallback callback) {
        if (!mMaxCompositionLayers.isPresent()) {
            mCompositionLayersPendingCallbacks.add(callback);
            return;
        }
        callback.onCompositionLayersSupport(supportsCompositionLayers());
    }

    private native void addWidgetNative(int aHandle, WidgetPlacement aPlacement);

    private native void updateWidgetNative(int aHandle, WidgetPlacement aPlacement);

    private native void updateVisibleWidgetsNative();

    private native void removeWidgetNative(int aHandle);

    private native void recreateWidgetSurfaceNative(int aHandle);

    private native void startWidgetResizeNative(int aHandle, float maxWidth, float maxHeight, float minWidth,
            float minHeight);

    private native void finishWidgetResizeNative(int aHandle);

    private native void startWidgetMoveNative(int aHandle, int aMoveBehaviour);

    private native void finishWidgetMoveNative();

    private native void setWorldBrightnessNative(float aBrightness);

    private native void triggerHapticFeedbackNative(float aPulseDuration, float aPulseIntensity, int aControllerId);

    private native void setTemporaryFilePath(String aPath);

    private native void exitImmersiveNative();

    private native void workaroundGeckoSigAction();

    private native void updateEnvironmentNative();

    private native void updatePointerColorNative();

    private native void showVRVideoNative(int aWindowHandler, int aVideoProjection);

    private native void hideVRVideoNative();

    private native void togglePassthroughNative();

    private native void setLockEnabledNative(@LockMode int aLockMode);

    private native void recenterUIYawNative(@YawTarget int aTarget);

    private native void setControllersVisibleNative(boolean aVisible);

    private native void runCallbackNative(long aCallback);

    private native void deleteCallbackNative(long aCallback);

    private native void setCylinderDensityNative(float aDensity);

    private native void setCPULevelNative(@CPULevelFlags int aCPULevel);

    private native void setWebXRIntersitialStateNative(@WebXRInterstitialState int aState);

    private native void setIsServo(boolean aIsServo);

    private native void setPointerModeNative(@PointerMode int aMode);

    private native void setHandTrackingEnabledNative(boolean value);

}

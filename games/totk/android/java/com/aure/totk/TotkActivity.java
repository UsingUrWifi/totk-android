package com.aure.totk;

import android.app.NativeActivity;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.AttachedSurfaceControl;
import android.view.View;
import android.view.Gravity;
import android.view.WindowManager;
import android.graphics.PixelFormat;
import com.aure.totk.companion.AssetStore;
import com.aure.totk.companion.CompanionNative;
import com.aure.totk.companion.DisplayClass;
import com.aure.totk.companion.PresentationController;
import com.aure.totk.setup.SetupActivity;   // compile-time constants only: the game process never loads setup classes
import com.aure.totk.setup.SaveMirror;
import java.nio.charset.StandardCharsets;

/** The game remains a NativeActivity; only eligible external displays get a Java UI.
 * SetupActivity (process ":setup") starts it once install.json is playable; the game process never loads the setup library. */
public final class TotkActivity extends NativeActivity {
    private PresentationController companion;
    private TouchControlsView touchControls;
    private boolean touchPanelAttached;
    /** Copies the saves to the player's save folder after each in-game save (null when no folder is set). */
    private SaveMirror.Watcher saves;
    private AttachedSurfaceControl presentRoot;
    private AttachedSurfaceControl.OnBufferTransformHintChangedListener presentHintListener;
    private long presentEpoch;
    private boolean displayMoveRequested;
    private static boolean updateChecked;
    private boolean updateOpen;
    private String updateVersion;
    private void showUpdate() {
        if (!resumed || updateVersion == null || updateOpen || isFinishing()) return;
        String version = updateVersion;
        updateVersion = null;
        updateOpen = true;
        // Explicitly pause on both single- and dual-screen devices, independent of window focus.
        nativeGameFocus(false, true);
        com.aure.totk.setup.UpdatePrompt.show(this, version, now -> {
            updateOpen = false;
            if (now) {
                try {
                    startActivity(new Intent().setClassName(this, "com.aure.totk.setup.UpdateEntryActivity")
                            .setAction(Intent.ACTION_VIEW));
                } catch (RuntimeException failed) { Log.w("TotkGame", "Could not open the update menu", failed); }
            }
            boolean single = PresentationController.secondScreen(this, getDisplay()) == DisplayClass.NONE;
            nativeGameFocus(hasWindowFocus(), single);
        });
    }
    private static native void nativePresentTransformHint(long epoch, int hint);
    private static native void nativeTextInputDone(long serial, byte[] utf8);
    private static native void nativeGameFocus(boolean focused, boolean singleDisplay);
    private static native void nativeGameInputFocus(boolean top);
    static native void nativeTouchInput(long buttons, int lx, int ly, int rx, int ry);
    /** Relaxed counters from the Vulkan presenter, packed real<<32 | displayed. */
    static native long nativePerfTotals();
    static native String nativeStutterExcerpt();
    static native int nativeOverlayMode();
    static native int nativeOverlayCycle();

    /** runtime/frontend_android.c, on this (the UI) thread: the game asks for text (nn::swkbd). TextEntry answers exactly
     * once: the UTF-8 text, or null when the player cancelled. */
    public void showTextInput(long serial, String prompt, String initial, int maxUnits, int minUnits, boolean secure,
                              boolean digitsOnly, int disabled) {
        TextEntry.show(this, prompt, initial, new TextRules(maxUnits, minUnits, digitsOnly, disabled), secure,
                text -> nativeTextInputDone(serial, text == null ? null : text.getBytes(StandardCharsets.UTF_8)));
    }

    /** runtime/game_error.h, on the failing native thread just before the process aborts (Vulkan device lost): open the
     * menu (process ":setup", which survives this one) with the reason instead of leaving the player on the home screen. */
    public void showGameError(String reason) {
        try {
            startActivity(new Intent().setClassName(this, "com.aure.totk.setup.SetupActivity")
                    .putExtra(SetupActivity.EXTRA_GAME_ERROR, reason));
        } catch (RuntimeException failed) {
            Log.w("TotkGame", "could not open the menu for a game error", failed);
        }
    }

    /** The game's rumble and six-axis sensor (runtime/feedback_android.c); null when the game was given neither. */
    private volatile GameFeedback feedback;
    private boolean resumed;
    static native void nativeMotion(float ax, float ay, float az, float gx, float gy, float gz);
    static native void nativeMotionLive(boolean on, String source);
    private static native int nativeFeedbackEnabled();
    /** start_game (UI thread, before the game starts): GameFeedback.RUMBLE | MOTION this device can drive now. */
    public int feedbackHardware() { return GameFeedback.hardware(this); }
    /** The native rumble thread: amplitude 0..255 per side. */
    public void rumble(int left, int right) {
        GameFeedback f = feedback;
        if (f != null) f.rumble(left, right);
    }

    /** Native UI thread only, after its explicit parent transaction succeeded.
     * API <=34 can crash in getBufferTransformHint before a root surface exists.
     * The bridge's successful buildReparentTransaction is our readiness gate. */
    public void updatePresentPrerotate(long epoch) {
        if (epoch != 0 && presentRoot != null && presentEpoch == epoch) return;
        if (presentRoot != null && presentHintListener != null) {
            presentRoot.removeOnBufferTransformHintChangedListener(presentHintListener);
        }
        presentRoot = null; presentHintListener = null; presentEpoch = 0;
        if (epoch == 0) return;
        if (Build.VERSION.SDK_INT < 32) {
            Log.w("TotkPresent", "Pre-rotation unavailable: buffer transform hint requires API32");
            nativePresentTransformHint(epoch, -1); return;
        }
        View decor = getWindow().getDecorView();
        AttachedSurfaceControl root = decor.isAttachedToWindow() ? decor.getRootSurfaceControl() : null;
        if (root == null) {
            Log.w("TotkPresent", "Pre-rotation deferred: no attached root");
            nativePresentTransformHint(epoch, -1); return;
        }
        presentRoot = root; presentEpoch = epoch;
        presentHintListener = hint -> {
            if (presentRoot == root && presentEpoch == epoch) nativePresentTransformHint(epoch, hint);
        };
        try {
            root.addOnBufferTransformHintChangedListener(presentHintListener);
            nativePresentTransformHint(epoch, root.getBufferTransformHint());
        } catch (RuntimeException unavailable) {
            Log.w("TotkPresent", "Pre-rotation hint unavailable; retaining identity", unavailable);
            root.removeOnBufferTransformHintChangedListener(presentHintListener);
            presentRoot = null; presentHintListener = null; presentEpoch = 0;
            nativePresentTransformHint(epoch, -1);
        }
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        applyRefreshPreference(); // NativeActivity already read settings.env; window preference is additional.
        // NativeActivity synchronously loads totk.env in ANativeActivity_onCreate.
        // Query only after super, before constructing a controller or display listener.
        // The companion art is generated on the device by the optional setup stage; without it the companion stays off.
        if (CompanionNative.isEnabled() && AssetStore.available(this)) companion = new PresentationController(this, new CompanionNative());
        saves = SaveMirror.Watcher.start(this);
        int given = nativeFeedbackEnabled();   // what start_game gave the game (TOTK_RUMBLE / TOTK_GYRO and the hardware)
        if (given != 0 && !isFinishing()) feedback = new GameFeedback(this, given);
        if (!updateChecked) {
            updateChecked = true;
            Updates.check(this, version -> { updateVersion = version; showUpdate(); });
        }
    }
    /**
     * The AHB Vulkan presenter is a SurfaceControl sibling attached to the window root,
     * not a child of the normal Java view hierarchy. A decor child can therefore be
     * completely obscured even though its view and JNI methods are correct. Create an
     * app-owned transparent panel window, which WindowManager layers above this Activity.
     * It is not TYPE_APPLICATION_OVERLAY and needs no draw-over-other-apps permission.
     * FLAG_NOT_FOCUSABLE leaves gamepad/keyboard focus in NativeActivity.
     */
    private void showTouchPanel() {
        if (touchPanelAttached || !resumed || !hasWindowFocus() || updateOpen || isFinishing()) return;
        View decor = getWindow().getDecorView();
        android.os.IBinder token = decor.getWindowToken();
        if (token == null) {
            Log.w("TotkTouch", "Panel attach deferred: activity window token missing");
            return;
        }
        try {
            if (touchControls == null) touchControls = new TouchControlsView(this);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.token = token;
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.setTitle("TotK Touch Controller");
            getWindowManager().addView(touchControls, lp);
            touchPanelAttached = true;
            Log.i("TotkTouch", "Controller panel attached above game surface (" +
                    getResources().getDisplayMetrics().widthPixels + "x" +
                    getResources().getDisplayMetrics().heightPixels + ")");
        } catch (RuntimeException problem) {
            Log.e("TotkTouch", "Controller panel attach failed", problem);
            // A failed panel must never interrupt the game or leave partial windows.
            if (touchControls != null && touchControls.getParent() != null) {
                try { getWindowManager().removeViewImmediate(touchControls); }
                catch (RuntimeException cleanup) { Log.w("TotkTouch", "Panel cleanup failed", cleanup); }
            }
            touchPanelAttached = false;
        }
    }

    private void scheduleTouchPanel() {
        getWindow().getDecorView().post(() -> showTouchPanel());
    }

    private void hideTouchPanel() {
        if (touchControls != null) touchControls.resetInput();
        if (touchPanelAttached && touchControls != null) {
            try {
                getWindowManager().removeViewImmediate(touchControls);
                Log.i("TotkTouch", "Controller panel detached");
            } catch (RuntimeException problem) {
                Log.w("TotkTouch", "Controller panel detach failed", problem);
            }
            touchPanelAttached = false;
        }
    }

    /** Window-level fallback for game SurfaceControl / native-window rate requests.
     * Applied once on Activity startup, never repeatedly on each touch/frame.
     * The Android compositor can still choose another refresh rate.
     */
    private void applyRefreshPreference() {
        String value = getSharedPreferences("settings", MODE_PRIVATE).getString("refresh_hint", "60");
        float preferred = "60".equals(value) ? 60f : 0f;
        WindowManager.LayoutParams params = getWindow().getAttributes();
        if (params.preferredRefreshRate != preferred) {
            params.preferredRefreshRate = preferred;
            getWindow().setAttributes(params);
            Log.i("TotkRefresh", "Activity preference " + preferred + " Hz (request only)");
        }
    }

    private boolean settingsRequested;
    /** Reuse/reparent the singleTask game, never finish it: NativeActivity.onDestroy exits the game process. */
    private void ensureMainDisplay() {
        if (GameLaunch.onMainScreen(this)) {
            displayMoveRequested = false;
            if (settingsRequested) {
                settingsRequested = false;
                com.aure.totk.setup.SetupActivity.openOnGame(this);
            }
            return;
        }
        if (displayMoveRequested) return;
        displayMoveRequested = true;
        try { GameLaunch.start(this); }
        catch (RuntimeException failed) {
            displayMoveRequested = false;
            Log.w("TotkGame", "Could not return the game task to the main display", failed);
        }
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        settingsRequested |= intent.getBooleanExtra(GameLaunch.OPEN_SETTINGS, false);
        ensureMainDisplay();
    }
    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        // Moving a task with the menu on top must not bring its background game back over the menu.
        if (resumed) {
            ensureMainDisplay();
            hideTouchPanel();
            scheduleTouchPanel();
        }
        if (companion != null && getDisplay() != null) companion.onDisplayChanged(getDisplay().getDisplayId());
    }
    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        scheduleTouchPanel();
        ensureMainDisplay();
        GameFeedback f = feedback;
        if (f != null) f.resume();
        showUpdate();
    }
    @Override public void onTopResumedActivityChanged(boolean top) {
        super.onTopResumedActivityChanged(top);
        nativeGameInputFocus(top); // a release routed to another display must not leave a controller button held
        if (!top) hideTouchPanel();
        else scheduleTouchPanel();
        if (companion != null) companion.ownerTopResumed(top);
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) scheduleTouchPanel();
        else hideTouchPanel();
        // Quickstep can leave the live recents tile RESUMED. On one display,
        // focus loss must pause too, and focus return need not call onResume.
        // Never apply that policy to multi-resume on a second display.
        boolean single = PresentationController.secondScreen(this, getDisplay()) == DisplayClass.NONE;
        nativeGameFocus(focused && !updateOpen, single || updateOpen);
        GameFeedback f = feedback;
        if (single && f != null) {
            if (focused && resumed) f.resume();
            else f.pause();
        }
    }
    @Override protected void onPause() {
        hideTouchPanel();
        resumed = false;
        GameFeedback f = feedback;
        if (f != null) f.pause();   // no motion in the background, and no motor left running
        super.onPause();
    }
    @Override protected void onStop() {
        // Leaving the foreground may be the last chance before the process is killed: push a save made just now.
        if (saves != null) saves.flush();
        super.onStop();
    }
    @Override protected void onDestroy() {
        hideTouchPanel();
        touchControls = null;
        updatePresentPrerotate(0);
        if (companion != null) { companion.close(); companion = null; }
        if (saves != null) { saves.close(1500); saves = null; }
        if (feedback != null) { feedback.close(); feedback = null; }
        // Native onDestroy exits the process, so all Java teardown must precede this call.
        super.onDestroy();
    }
}

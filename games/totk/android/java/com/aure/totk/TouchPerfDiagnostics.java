package com.aure.totk;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;

import java.util.Locale;
import java.util.function.BooleanSupplier;

/** Read-only sampling of native Vulkan handoffs and Android performance indicators.
 * Sampling is explicit opt-in and runs every two seconds on the UI looper. It never sets
 * CPU governors, display mode, thermal controls, Android power hints, or render pacing.
 * Native handoff totals are true presenter handoffs, not an FPS estimate from View redraws.
 */
final class TouchPerfDiagnostics {
    private static final String TAG = "TotkPerfIdle";
    private static final String CSV_HEADER = "epoch_ms,seconds_since_touch,touches,pointer_held,game_handoffs_fps,real_handoffs_fps,display_mode_hz,process_cpu_pct,thermal_status,power_saver,battery_pct";
    private final Activity activity;
    private final Runnable invalidate;
    private final BooleanSupplier pointerHeld;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder csv = new StringBuilder(16384);
    private final Runnable ticker = () -> sample();
    private boolean enabled;
    private boolean attached;
    private boolean running;
    private long lastWallMs, lastCpuMs, lastTouchMs;
    private long lastPresentCount, lastRealCount;
    private int touches;
    private String status = "DBG sampling...";
    private String detail = "Idle/touch profiler · no CPU or refresh overrides";

    TouchPerfDiagnostics(Activity activity, Runnable invalidate, BooleanSupplier pointerHeld) {
        this.activity = activity;
        this.invalidate = invalidate;
        this.pointerHeld = pointerHeld;
    }
    boolean enabled() { return enabled; }
    String status() { return status; }
    String detail() { return detail; }

    void setEnabled(boolean enabled) {
        if (this.enabled == enabled) return;
        this.enabled = enabled;
        if (enabled && attached) start();
        else stop();
        invalidate.run();
    }
    void onAttached() { attached = true; if (enabled) start(); }
    void onDetached() { attached = false; stop(); }
    void markTouch() {
        if (running) {
            touches++;
            lastTouchMs = SystemClock.elapsedRealtime();
        }
    }
    private void start() {
        if (running) return;
        running = true;
        lastWallMs = SystemClock.elapsedRealtime();
        lastTouchMs = lastWallMs;
        lastCpuMs = android.os.Process.getElapsedCpuTime();
        long totals = TotkActivity.nativePerfTotals();
        lastPresentCount = totals & 0xffffffffL;
        lastRealCount = (totals >>> 32) & 0xffffffffL;
        touches = 0;
        status = "DBG sampling...";
        handler.postDelayed(ticker, 2000L);
    }
    private void stop() {
        running = false;
        handler.removeCallbacks(ticker);
    }

    private void sample() {
        if (!running) return;
        long now = SystemClock.elapsedRealtime();
        long wallDelta = Math.max(1, now - lastWallMs);
        long cpu = android.os.Process.getElapsedCpuTime();
        long cpuDelta = Math.max(0, cpu - lastCpuMs);
        long totals = TotkActivity.nativePerfTotals();
        long presented = totals & 0xffffffffL;
        long real = (totals >>> 32) & 0xffffffffL;
        // Counter wrap-safe unsigned 32-bit deltas; values reflect native completed handoffs.
        long frames = (presented - lastPresentCount) & 0xffffffffL;
        long realFrames = (real - lastRealCount) & 0xffffffffL;
        double fps = frames * 1000.0 / wallDelta;
        double realFps = realFrames * 1000.0 / wallDelta;
        double processCpu = cpuDelta * 100.0 / wallDelta; // 100% = one full CPU core
        double idleSeconds = Math.max(0, now - lastTouchMs) / 1000.0;
        Display display = activity.getDisplay();
        float displayModeHz = display == null ? 0f : display.getRefreshRate();
        PowerManager power = (PowerManager) activity.getSystemService(Context.POWER_SERVICE);
        int thermal = -1;
        boolean saver = false;
        if (power != null) {
            saver = power.isPowerSaveMode();
            if (Build.VERSION.SDK_INT >= 29) thermal = power.getCurrentThermalStatus();
        }
        int batteryPercent = -1;
        Intent battery = activity.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0) batteryPercent = Math.round(level * 100f / scale);
        }
        boolean held = pointerHeld.getAsBoolean();
        status = String.format(Locale.US, "DBG Game %.1f / %.1f FPS | Mode %.0fHz | CPU %.0f%%",
                fps, realFps, displayModeHz, processCpu);
        detail = String.format(Locale.US, "%s %.1fs | therm %s | saver %s | battery %s%%",
                held ? "HOLD" : touches == 0 && idleSeconds > 2 ? "IDLE" : "TOUCH", idleSeconds,
                thermal < 0 ? "n/a" : Integer.toString(thermal), saver ? "on" : "off",
                batteryPercent < 0 ? "n/a" : Integer.toString(batteryPercent));
        String line = String.format(Locale.US,
                "%d,%.2f,%d,%s,%.2f,%.2f,%.1f,%.1f,%d,%s,%d",
                System.currentTimeMillis(), idleSeconds, touches, held ? "1" : "0", fps, realFps, displayModeHz,
                processCpu, thermal, saver ? "1" : "0", batteryPercent);
        if (csv.length() > 64000) csv.delete(0, csv.length() - 32000);
        csv.append(line).append('\n');
        Log.i(TAG, line); // also available to adb logcat if the app closes unexpectedly
        lastWallMs = now;
        lastCpuMs = cpu;
        lastPresentCount = presented;
        lastRealCount = real;
        touches = 0;
        invalidate.run();
        handler.postDelayed(ticker, 2000L);
    }
    void clear() {
        csv.setLength(0);
        status = "DBG cleared";
        invalidate.run();
    }
    void share() {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "TotK idle-versus-touch performance diagnostics");
        intent.putExtra(Intent.EXTRA_TEXT,
                "TotK Native touch performance diagnostics\n" +
                "Game FPS = completed native Vulkan handoffs; real FPS excludes generated frames.\n" +
                "Mode Hz = Android-reported display mode, not a physical-vsync measurement.\n" +
                "CPU % = whole process CPU time, where 100% equals one fully used core.\n" +
                "Thermal status 0 = none; -1 = not reported. No personal game data is collected.\n\n" +
                CSV_HEADER + "\n" + csv.toString());
        try { activity.startActivity(Intent.createChooser(intent, "Share performance log")); }
        catch (RuntimeException failed) { Log.w(TAG, "No share target available", failed); }
    }
}

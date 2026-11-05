package com.aure.totk;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.SparseArray;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

/**
 * Phone/tablet touch controller for the Android NativeActivity. No overlay permission,
 * Accessibility Service, or fake Android key events are required: the combined Switch
 * button mask and both analog stick axes go directly to the game's native input reader.
 *
 * All pointer ownership is by Android pointer ID, never pointer index, so lifting one
 * finger does not release other held buttons/sticks. Java sends every state transition;
 * the native reader retains press edges that occur between game frames.
 */
public final class TouchControlsView extends View {
    private static final String PREFS = "touch_controls";
    private static final String KEY_VISIBLE = "visible";
    private static final String KEY_SCALE = "scale";
    private static final String KEY_ALPHA = "opacity";

    // Match runtime/frontend_android.c SW_* and runtime/input.c full-key bit indices.
    private static final int A = 0, B = 1, X = 2, Y = 3;
    private static final int L3 = 4, R3 = 5, L = 6, R = 7;
    private static final int ZL = 8, ZR = 9, PLUS = 10, MINUS = 11;
    private static final int LEFT = 12, UP = 13, RIGHT = 14, DOWN = 15;
    private static final int MODE_BUTTON = 0, MODE_LEFT_STICK = 1, MODE_RIGHT_STICK = 2, MODE_MENU = 3;

    private static final class Button {
        final String text;
        final int bit;
        float x, y, radius;
        Button(String text, int bit) { this.text = text; this.bit = bit; }
    }
    private static final class Finger {
        final int mode;
        final float originX, originY;
        float x, y;
        Finger(int mode, float x, float y) {
            this.mode = mode; originX = x; originY = y; this.x = x; this.y = y;
        }
    }

    private final TotkActivity activity;
    private final SharedPreferences prefs;
    private final SparseArray<Finger> pointers = new SparseArray<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Button[] buttons = {
            new Button("A", A), new Button("B", B), new Button("X", X), new Button("Y", Y),
            new Button("L3", L3), new Button("R3", R3), new Button("L", L), new Button("R", R),
            new Button("ZL", ZL), new Button("ZR", ZR), new Button("+", PLUS), new Button("−", MINUS),
            new Button("◀", LEFT), new Button("▲", UP), new Button("▶", RIGHT), new Button("▼", DOWN)
    };
    private final RectF menu = new RectF();
    private boolean visible;
    private float scale;
    private float opacity;
    private float leftX, leftY, rightX, rightY, stickRadius, buttonRadius;
    private long currentButtons;
    private boolean stateSent;
    private boolean drewOnce;
    private boolean touchLogged;
    private int lx, ly, rx, ry;

    public TouchControlsView(TotkActivity activity) {
        super(activity);
        this.activity = activity;
        prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        visible = prefs.getBoolean(KEY_VISIBLE, true);
        scale = clamp(prefs.getFloat(KEY_SCALE, 1f), .75f, 1.35f);
        opacity = clamp(prefs.getFloat(KEY_ALPHA, .46f), .20f, .85f);
        setClickable(true);
        setFocusable(false);
        setWillNotDraw(false);
        // Also establishes controller connection on a touchscreen-only device.
        send(0, 0, 0, 0, 0);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
    private float dp(float dp) { return dp * getResources().getDisplayMetrics().density; }

    private void button(int index, float x, float y, float radius) {
        Button b = buttons[index]; b.x = x; b.y = y; b.radius = radius;
    }

    /** Layout is proportional to the available landscape window, including foldables. */
    private void layoutControls(int width, int height) {
        if (width <= 0 || height <= 0) return;
        float w = width, h = height;
        float side = Math.min(dp(30), .045f * w);
        buttonRadius = Math.min(dp(36), h * .066f) * scale;
        stickRadius = Math.min(dp(80), h * .145f) * scale;
        float faceX = w - side - buttonRadius * 2.35f;
        float dpadX = side + buttonRadius * 2.35f;
        float cy = h * .475f;
        float diamond = buttonRadius * 1.72f;

        button(A, faceX + diamond, cy, buttonRadius);
        button(B, faceX, cy + diamond, buttonRadius);
        button(X, faceX, cy - diamond, buttonRadius);
        button(Y, faceX - diamond, cy, buttonRadius);
        button(LEFT, dpadX - diamond, cy, buttonRadius * .85f);
        button(UP, dpadX, cy - diamond, buttonRadius * .85f);
        button(RIGHT, dpadX + diamond, cy, buttonRadius * .85f);
        button(DOWN, dpadX, cy + diamond, buttonRadius * .85f);

        // Shoulder rows and thumb clicks. The top pills are separate from the pad.
        float shoulderY = Math.max(dp(54), h * .15f);
        button(ZL, w * .095f, shoulderY, buttonRadius * .85f);
        button(L, w * .225f, shoulderY, buttonRadius * .85f);
        button(R, w * .775f, shoulderY, buttonRadius * .85f);
        button(ZR, w * .905f, shoulderY, buttonRadius * .85f);
        button(MINUS, w * .43f, shoulderY, buttonRadius * .70f);
        button(PLUS, w * .57f, shoulderY, buttonRadius * .70f);
        button(L3, w * .075f, h * .83f, buttonRadius * .70f);
        button(R3, w * .925f, h * .83f, buttonRadius * .70f);

        leftX = w * .25f; leftY = h * .81f;
        rightX = w * .75f; rightY = h * .81f;
        float menuWidth = Math.max(dp(66), w * .072f);
        float menuHeight = dp(36);
        menu.set(w / 2f - menuWidth / 2f, dp(7), w / 2f + menuWidth / 2f, dp(7) + menuHeight);
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        resetInput();
        layoutControls(w, h);
    }

    private Button hit(float x, float y) {
        // Reverse to give corner/face buttons precedence where hit circles overlap.
        for (int i = buttons.length - 1; i >= 0; i--) {
            Button b = buttons[i];
            float dx = x - b.x, dy = y - b.y;
            float r = b.radius * 1.18f;
            if (dx * dx + dy * dy <= r * r) return b;
        }
        return null;
    }

    private static int axis(float delta, float radius, boolean reverse) {
        float value = clamp(delta / Math.max(radius, 1f), -1f, 1f);
        return Math.round((reverse ? -value : value) * 32767f);
    }

    private void send(long buttons, int lx, int ly, int rx, int ry) {
        if (stateSent && currentButtons == buttons && this.lx == lx && this.ly == ly && this.rx == rx && this.ry == ry) return;
        stateSent = true;
        currentButtons = buttons; this.lx = lx; this.ly = ly; this.rx = rx; this.ry = ry;
        TotkActivity.nativeTouchInput(buttons, lx, ly, rx, ry);
    }

    public void resetInput() {
        pointers.clear();
        send(0, 0, 0, 0, 0);
        invalidate();
    }

    private void updateNative() {
        long bits = 0;
        int nextLX = 0, nextLY = 0, nextRX = 0, nextRY = 0;
        for (int i = 0; i < pointers.size(); i++) {
            Finger p = pointers.valueAt(i);
            if (p.mode == MODE_BUTTON) {
                Button b = hit(p.x, p.y);
                if (b != null) bits |= 1L << b.bit;
            } else if (p.mode == MODE_LEFT_STICK || p.mode == MODE_RIGHT_STICK) {
                float dx = p.x - p.originX, dy = p.y - p.originY;
                float length = (float)Math.hypot(dx, dy);
                float rate = Math.min(1f, length / Math.max(stickRadius, 1f));
                if (length > 0) { dx = dx / length * rate * stickRadius; dy = dy / length * rate * stickRadius; }
                if (p.mode == MODE_LEFT_STICK) {
                    nextLX = axis(dx, stickRadius, false); nextLY = axis(dy, stickRadius, true);
                } else {
                    nextRX = axis(dx, stickRadius, false); nextRY = axis(dy, stickRadius, true);
                }
            }
        }
        send(visible ? bits : 0, visible ? nextLX : 0, visible ? nextLY : 0,
                visible ? nextRX : 0, visible ? nextRY : 0);
        invalidate();
    }

    private boolean stickOwned(int mode) {
        for (int i = 0; i < pointers.size(); i++) if (pointers.valueAt(i).mode == mode) return true;
        return false;
    }

    private void press(int id, float x, float y) {
        if (menu.contains(x, y)) {
            pointers.put(id, new Finger(MODE_MENU, x, y));
            return;
        }
        if (!visible) return;
        if (hit(x, y) != null) {
            pointers.put(id, new Finger(MODE_BUTTON, x, y));
            return;
        }
        // Floating sticks: touch either lower quadrant to establish its center.
        int mode = x < getWidth() / 2f ? MODE_LEFT_STICK : MODE_RIGHT_STICK;
        if (y > getHeight() * .60f && !stickOwned(mode)) {
            pointers.put(id, new Finger(mode, x, y));
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!touchLogged) {
            touchLogged = true;
            Log.i("TotkTouch", "First touch delivered to controller window");
        }
        int action = event.getActionMasked(), index = event.getActionIndex();
        if (action == MotionEvent.ACTION_CANCEL) {
            resetInput();
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            press(event.getPointerId(index), event.getX(index), event.getY(index));
        }
        // Update every active pointer with its current position; indices may reorder.
        for (int i = 0; i < event.getPointerCount(); i++) {
            Finger finger = pointers.get(event.getPointerId(i));
            if (finger != null) { finger.x = event.getX(i); finger.y = event.getY(i); }
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            int id = event.getPointerId(index);
            Finger ended = pointers.get(id);
            boolean showMenu = ended != null && ended.mode == MODE_MENU && menu.contains(event.getX(index), event.getY(index));
            pointers.remove(id);
            updateNative();
            if (showMenu) showSettings();
            return true;
        }
        updateNative();
        return true;
    }

    private void showSettings() {
        resetInput();
        String[] items = {
                visible ? "Hide controls" : "Show controls",
                "Control size: " + Math.round(scale * 100f) + "%",
                "Opacity: " + Math.round(opacity * 100f) + "%",
                "Reset layout"
        };
        new AlertDialog.Builder(activity).setTitle("Touch controller")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        visible = !visible;
                        prefs.edit().putBoolean(KEY_VISIBLE, visible).apply();
                    } else if (which == 1) {
                        float[] sizes = { .75f, 1f, 1.15f, 1.35f };
                        new AlertDialog.Builder(activity).setTitle("Button size")
                                .setSingleChoiceItems(new String[]{"75%", "100%", "115%", "135%"}, nearest(sizes, scale), (d, choice) -> {
                                    scale = sizes[choice]; prefs.edit().putFloat(KEY_SCALE, scale).apply();
                                    layoutControls(getWidth(), getHeight()); invalidate(); d.dismiss();
                                }).setNegativeButton("Cancel", null).show();
                    } else if (which == 2) {
                        float[] alphas = { .25f, .40f, .55f, .70f, .85f };
                        new AlertDialog.Builder(activity).setTitle("Control opacity")
                                .setSingleChoiceItems(new String[]{"25%", "40%", "55%", "70%", "85%"}, nearest(alphas, opacity), (d, choice) -> {
                                    opacity = alphas[choice]; prefs.edit().putFloat(KEY_ALPHA, opacity).apply();
                                    invalidate(); d.dismiss();
                                }).setNegativeButton("Cancel", null).show();
                    } else {
                        scale = 1f; opacity = .46f; visible = true;
                        prefs.edit().clear().apply();
                        layoutControls(getWidth(), getHeight());
                    }
                    invalidate();
                }).setNegativeButton("Cancel", null).show();
    }
    private static int nearest(float[] values, float target) {
        int best = 0;
        for (int i = 1; i < values.length; i++)
            if (Math.abs(values[i] - target) < Math.abs(values[best] - target)) best = i;
        return best;
    }

    private void drawButton(Canvas canvas, Button b) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.BLACK); paint.setAlpha(Math.round(opacity * 210f));
        canvas.drawCircle(b.x, b.y, b.radius, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1.6f));
        paint.setColor(Color.WHITE); paint.setAlpha(Math.round(opacity * 255f));
        canvas.drawCircle(b.x, b.y, b.radius, paint);
        paint.setStyle(Paint.Style.FILL); paint.setAlpha(Math.round(opacity * 255f));
        paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        paint.setTextSize(b.text.length() > 1 ? b.radius * .53f : b.radius * .85f);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(b.text, b.x, b.y - (paint.ascent() + paint.descent()) / 2f, paint);
    }

    private void drawStick(Canvas canvas, boolean left) {
        int mode = left ? MODE_LEFT_STICK : MODE_RIGHT_STICK;
        float x = left ? leftX : rightX, y = left ? leftY : rightY;
        float knobX = x, knobY = y;
        for (int i = 0; i < pointers.size(); i++) {
            Finger f = pointers.valueAt(i);
            if (f.mode != mode) continue;
            x = f.originX; y = f.originY;
            float dx = f.x - x, dy = f.y - y;
            float distance = (float)Math.hypot(dx, dy);
            if (distance > stickRadius) { dx *= stickRadius / distance; dy *= stickRadius / distance; }
            knobX = x + dx; knobY = y + dy; break;
        }
        paint.setStyle(Paint.Style.FILL); paint.setColor(Color.BLACK); paint.setAlpha(Math.round(opacity * 130f));
        canvas.drawCircle(x, y, stickRadius, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2));
        paint.setColor(Color.WHITE); paint.setAlpha(Math.round(opacity * 255f));
        canvas.drawCircle(x, y, stickRadius, paint);
        paint.setStyle(Paint.Style.FILL); paint.setColor(Color.WHITE); paint.setAlpha(Math.round(opacity * 150f));
        canvas.drawCircle(knobX, knobY, stickRadius * .36f, paint);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!drewOnce) {
            drewOnce = true;
            Log.i("TotkTouch", "Touch view first rendered (" + getWidth() + "x" + getHeight() + ")");
        }
        if (visible) {
            for (Button b : buttons) drawButton(canvas, b);
            drawStick(canvas, true);
            drawStick(canvas, false);
        }
        // The menu stays available even when the pad is hidden.
        paint.setStyle(Paint.Style.FILL); paint.setColor(Color.BLACK); paint.setAlpha(145);
        canvas.drawRoundRect(menu, dp(11), dp(11), paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1));
        paint.setColor(Color.WHITE); paint.setAlpha(170);
        canvas.drawRoundRect(menu, dp(11), dp(11), paint);
        paint.setStyle(Paint.Style.FILL); paint.setColor(Color.WHITE); paint.setAlpha(230);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(dp(12));
        canvas.drawText("PAD", menu.centerX(), menu.centerY() - (paint.ascent() + paint.descent()) / 2f, paint);
    }
}

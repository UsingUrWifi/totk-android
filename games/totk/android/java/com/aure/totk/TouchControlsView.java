package com.aure.totk;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.SparseArray;
import android.util.Log;
import android.view.HapticFeedbackConstants;
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
    private static final String KEY_STYLE = "style";
    private static final String KEY_HAPTICS = "haptics";
    private static final String KEY_ZL_TOGGLE = "zl_toggle";
    private static final String KEY_COMBO_ENABLED = "combo_enabled";
    private static final String KEY_COMBO_FIRST = "combo_first";
    private static final String KEY_COMBO_SECOND = "combo_second";
    private static final String KEY_DIAGNOSTICS = "idle_diagnostics";
    // Labels are in Switch bit order, exactly matching runtime/frontend_android.c.
    private static final String[] ACTION_LABELS = {
            "A", "B", "X", "Y", "L3", "R3", "L", "R", "ZL", "ZR", "+", "−", "◀", "▲", "▶", "▼"
    };
    private static final String STYLE_PRO = "pro";
    private static final String STYLE_OUTLINE = "outline";
    private static final Typeface CONTROL_FONT = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    // Match runtime/frontend_android.c SW_* and runtime/input.c full-key bit indices.
    private static final int A = 0, B = 1, X = 2, Y = 3;
    private static final int L3 = 4, R3 = 5, L = 6, R = 7;
    private static final int ZL = 8, ZR = 9, PLUS = 10, MINUS = 11;
    private static final int LEFT = 12, UP = 13, RIGHT = 14, DOWN = 15;
    private static final int MODE_BUTTON = 0, MODE_LEFT_STICK = 1, MODE_RIGHT_STICK = 2, MODE_MENU = 3, MODE_MACRO = 4;

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
    private final RectF shape = new RectF(); // reused during rendering
    private final Button[] buttons = {
            new Button("A", A), new Button("B", B), new Button("X", X), new Button("Y", Y),
            new Button("L3", L3), new Button("R3", R3), new Button("L", L), new Button("R", R),
            new Button("ZL", ZL), new Button("ZR", ZR), new Button("+", PLUS), new Button("−", MINUS),
            new Button("◀", LEFT), new Button("▲", UP), new Button("▶", RIGHT), new Button("▼", DOWN)
    };
    private final RectF menu = new RectF();
    private final TouchBindings bindings = new TouchBindings();
    private final TouchPerfDiagnostics diagnostics;
    private boolean comboEnabled;
    private float comboX, comboY, comboRadius;
    private boolean visible;
    private boolean proStyle;
    private boolean haptics;
    private boolean pendingStickPulse;
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
        proStyle = !STYLE_OUTLINE.equals(prefs.getString(KEY_STYLE, STYLE_PRO));
        haptics = prefs.getBoolean(KEY_HAPTICS, true);
        bindings.setToggleZl(prefs.getBoolean(KEY_ZL_TOGGLE, false));
        int first = TouchBindings.safeBit(prefs.getInt(KEY_COMBO_FIRST, ZL), ZL);
        int second = TouchBindings.safeBit(prefs.getInt(KEY_COMBO_SECOND, A), A);
        if (first == second) second = first == A ? ZL : A;
        bindings.setCombo(first, second);
        comboEnabled = prefs.getBoolean(KEY_COMBO_ENABLED, false);
        diagnostics = new TouchPerfDiagnostics(activity, this::invalidate, () -> pointers.size() != 0);
        diagnostics.setEnabled(prefs.getBoolean(KEY_DIAGNOSTICS, false));
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
        // Optional shortcut does not move existing controls, and is away from stick centers.
        comboX = w * .63f; comboY = h * .59f; comboRadius = buttonRadius * .80f;
        float menuWidth = Math.max(dp(66), w * .072f);
        float menuHeight = dp(36);
        menu.set(w / 2f - menuWidth / 2f, dp(7), w / 2f + menuWidth / 2f, dp(7) + menuHeight);
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        resetInput();
        layoutControls(w, h);
    }

    private static boolean shoulder(Button b) {
        return b.bit == L || b.bit == R || b.bit == ZL || b.bit == ZR;
    }

    private boolean comboHit(float x, float y) {
        return comboEnabled && Math.abs(x - comboX) <= comboRadius * 1.42f &&
                Math.abs(y - comboY) <= comboRadius * .94f;
    }

    private Button hit(float x, float y) {
        // Reverse to give corner/face buttons precedence where hit circles overlap.
        for (int i = buttons.length - 1; i >= 0; i--) {
            Button b = buttons[i];
            float dx = x - b.x, dy = y - b.y;
            float r = b.radius * 1.18f;
            // Capsule-shaped shoulder buttons get matching hitboxes.
            if (proStyle && shoulder(b)) {
                if (Math.abs(dx) <= b.radius * 1.65f && Math.abs(dy) <= b.radius * 1.06f) return b;
            } else if (dx * dx + dy * dy <= r * r) return b;
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
        bindings.reset(); // never leave ZL latched across focus/rotation/settings
        pendingStickPulse = false;
        send(0, 0, 0, 0, 0);
        invalidate();
    }

    private void updateNative() {
        long bits = 0;
        boolean macroHeld = false;
        int nextLX = 0, nextLY = 0, nextRX = 0, nextRY = 0;
        for (int i = 0; i < pointers.size(); i++) {
            Finger p = pointers.valueAt(i);
            if (p.mode == MODE_BUTTON) {
                Button b = hit(p.x, p.y);
                if (b != null) bits |= 1L << b.bit;
            } else if (p.mode == MODE_MACRO) {
                macroHeld = true;
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
        long acceptedButtons = visible ? bindings.apply(bits, macroHeld) : 0;
        long newPresses = acceptedButtons & ~currentButtons;
        boolean stickStarted = visible && pendingStickPulse;
        pendingStickPulse = false;
        // Native state always updates before feedback, avoiding input latency.
        send(acceptedButtons, visible ? nextLX : 0, visible ? nextLY : 0,
                visible ? nextRX : 0, visible ? nextRY : 0);
        // A second ZL tap releases a latch; still acknowledge that intentional input.
        if (newPresses != 0 || bindings.toggledOnThisPress()) pulse(HapticFeedbackConstants.VIRTUAL_KEY);
        else if (stickStarted) pulse(HapticFeedbackConstants.CLOCK_TICK);
        invalidate();
    }

    private void pulse(int kind) {
        // Respect Android touch-vibration settings. Never cancel the game's HD rumble.
        if (haptics && isAttachedToWindow()) performHapticFeedback(kind);
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
        if (comboHit(x, y)) {
            pointers.put(id, new Finger(MODE_MACRO, x, y));
            return;
        }
        if (hit(x, y) != null) {
            pointers.put(id, new Finger(MODE_BUTTON, x, y));
            return;
        }
        // Floating sticks: touch either lower quadrant to establish its center.
        int mode = x < getWidth() / 2f ? MODE_LEFT_STICK : MODE_RIGHT_STICK;
        if (y > getHeight() * .60f && !stickOwned(mode)) {
            pointers.put(id, new Finger(mode, x, y));
            pendingStickPulse = true; // one gentle tick on engagement; none during drag
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!touchLogged) {
            touchLogged = true;
            Log.i("TotkTouch", "First touch delivered to controller window");
        }
        int action = event.getActionMasked(), index = event.getActionIndex();
        diagnostics.markTouch(); // sampling is independent of actual Switch button edges
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

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        diagnostics.onAttached();
    }
    @Override protected void onDetachedFromWindow() {
        diagnostics.onDetached();
        resetInput();
        super.onDetachedFromWindow();
    }

    private void showSettings() {
        resetInput();
        String[] overlayNames = {"Off", "FPS", "Full"};
        int overlayMode = TotkActivity.nativeOverlayMode();
        String[] items = {
                visible ? "Hide controls" : "Show controls",
                "Control size: " + Math.round(scale * 100f) + "%",
                "Opacity: " + Math.round(opacity * 100f) + "%",
                "Controller style: " + (proStyle ? "Pro Controller" : "Minimal outline"),
                "Touch vibration: " + (haptics ? "On" : "Off"),
                "ZL behavior: " + (bindings.toggleZl() ? "Toggle" : "Hold"),
                "M1 shortcut: " + (comboEnabled ? comboName() : "Off"),
                "Idle performance monitor: " + (diagnostics.enabled() ? "On" : "Off"),
                "Native FPS overlay: " + overlayNames[Math.max(0, Math.min(2, overlayMode))],
                "Share performance log",
                "Clear performance log",
                "Reset controller settings"
        };
        new AlertDialog.Builder(activity).setTitle("Touch controller")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        visible = !visible;
                        resetInput();
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
                    } else if (which == 3) {
                        new AlertDialog.Builder(activity).setTitle("Controller style")
                                .setSingleChoiceItems(new String[]{"Pro Controller · matte and tactile", "Minimal outline"},
                                        proStyle ? 0 : 1, (d, choice) -> {
                                    proStyle = choice == 0;
                                    prefs.edit().putString(KEY_STYLE, proStyle ? STYLE_PRO : STYLE_OUTLINE).apply();
                                    invalidate(); d.dismiss();
                                }).setNegativeButton("Cancel", null).show();
                    } else if (which == 4) {
                        haptics = !haptics;
                        prefs.edit().putBoolean(KEY_HAPTICS, haptics).apply();
                    } else if (which == 5) {
                        bindings.setToggleZl(!bindings.toggleZl());
                        resetInput();
                        prefs.edit().putBoolean(KEY_ZL_TOGGLE, bindings.toggleZl()).apply();
                    } else if (which == 6) {
                        showComboOptions();
                    } else if (which == 7) {
                        diagnostics.setEnabled(!diagnostics.enabled());
                        prefs.edit().putBoolean(KEY_DIAGNOSTICS, diagnostics.enabled()).apply();
                    } else if (which == 8) {
                        TotkActivity.nativeOverlayCycle(); // off -> fps -> full -> off
                    } else if (which == 9) {
                        diagnostics.share();
                    } else if (which == 10) {
                        diagnostics.clear();
                    } else {
                        resetInput();
                        scale = 1f; opacity = .46f; visible = true;
                        proStyle = true; haptics = true; comboEnabled = false;
                        bindings.setToggleZl(false);
                        bindings.setCombo(ZL, A);
                        diagnostics.setEnabled(false);
                        prefs.edit().clear().apply();
                        layoutControls(getWidth(), getHeight());
                    }
                    invalidate();
                }).setNegativeButton("Cancel", null).show();
    }

    private String comboName() {
        return ACTION_LABELS[bindings.comboFirst()] + "+" + ACTION_LABELS[bindings.comboSecond()];
    }
    private void showComboOptions() {
        String[] items = {
                comboEnabled ? "Hide M1" : "Show M1",
                "First action: " + ACTION_LABELS[bindings.comboFirst()],
                "Second action: " + ACTION_LABELS[bindings.comboSecond()]
        };
        new AlertDialog.Builder(activity).setTitle("M1 · hold two buttons together")
                .setItems(items, (dialog, choice) -> {
                    if (choice == 0) {
                        resetInput();
                        comboEnabled = !comboEnabled;
                        prefs.edit().putBoolean(KEY_COMBO_ENABLED, comboEnabled).apply();
                        invalidate();
                    } else showComboActionPicker(choice == 1);
                }).setNegativeButton("Cancel", null).show();
    }
    private void showComboActionPicker(boolean first) {
        final int selected = first ? bindings.comboFirst() : bindings.comboSecond();
        new AlertDialog.Builder(activity).setTitle(first ? "M1 first action" : "M1 second action")
                .setSingleChoiceItems(ACTION_LABELS, selected, (dialog, choice) -> {
                    final int other = first ? bindings.comboSecond() : bindings.comboFirst();
                    if (choice == other) {
                        dialog.dismiss();
                        new AlertDialog.Builder(activity).setMessage("Choose two different buttons for M1.")
                                .setPositiveButton("OK", null).show();
                        return;
                    }
                    resetInput();
                    bindings.setCombo(first ? choice : other, first ? other : choice);
                    prefs.edit().putInt(KEY_COMBO_FIRST, bindings.comboFirst())
                            .putInt(KEY_COMBO_SECOND, bindings.comboSecond()).apply();
                    invalidate();
                    dialog.dismiss();
                }).setNegativeButton("Cancel", null).show();
    }

    private static int nearest(float[] values, float target) {
        int best = 0;
        for (int i = 1; i < values.length; i++)
            if (Math.abs(values[i] - target) < Math.abs(values[best] - target)) best = i;
        return best;
    }

    private int alpha(float multiplier) {
        return Math.max(0, Math.min(255, Math.round(opacity * 255f * multiplier)));
    }
    private void fill(int color, float multiplier) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color); paint.setAlpha(alpha(multiplier));
    }
    private void stroke(int color, float multiplier, float width) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setColor(color); paint.setAlpha(alpha(multiplier));
    }
    private boolean held(Button b) { return (currentButtons & (1L << b.bit)) != 0; }

    /** Retains the original minimalist skin as a selectable fallback. */
    private void drawOutlineButton(Canvas canvas, Button b) {
        boolean down = held(b);
        fill(down ? 0xffa7bed0 : Color.BLACK, down ? .72f : 210f / 255f);
        canvas.drawCircle(b.x, b.y, b.radius, paint);
        stroke(Color.WHITE, down ? 1.55f : 1f, dp(1.6f));
        canvas.drawCircle(b.x, b.y, b.radius, paint);
        fill(Color.WHITE, 1f);
        paint.setTypeface(CONTROL_FONT);
        paint.setTextSize(b.text.length() > 1 ? b.radius * .53f : b.radius * .85f);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(b.text, b.x, b.y - (paint.ascent() + paint.descent()) / 2f, paint);
    }

    /** Matte graphite shell, beveled edge and immediate pressed feedback. */
    private void drawButton(Canvas canvas, Button b) {
        if (!proStyle) { drawOutlineButton(canvas, b); return; }
        boolean down = held(b);
        float r = b.radius;
        canvas.save();
        canvas.translate(b.x, b.y + (down ? dp(1.1f) : 0f));
        if (down) canvas.scale(.94f, .94f);
        if (shoulder(b)) {
            float hw = r * 1.5f, hh = r * .85f;
            shape.set(-hw, -hh + dp(1.8f), hw, hh + dp(1.8f));
            fill(Color.BLACK, .65f); canvas.drawRoundRect(shape, hh, hh, paint);
            shape.offset(0, -dp(1.8f));
            fill(0xff89939f, .94f); canvas.drawRoundRect(shape, hh, hh, paint);
            shape.inset(dp(1.4f), dp(1.4f));
            fill(down ? 0xffb2c8db : 0xff1b222b, down ? 1.35f : 1.05f);
            canvas.drawRoundRect(shape, hh, hh, paint);
        } else {
            fill(Color.BLACK, .65f);
            canvas.drawCircle(0, dp(1.8f), r + dp(1f), paint);
            fill(0xff788490, .95f); canvas.drawCircle(0, 0, r, paint);
            fill(down ? 0xffb2c8db : 0xff1a2028, down ? 1.38f : 1.05f);
            canvas.drawCircle(0, -dp(.6f), r - dp(1.5f), paint);
            if (!down) {
                shape.set(-r + dp(3), -r + dp(3), r - dp(3), r - dp(3));
                stroke(0xffdae0e7, .43f, dp(.9f));
                canvas.drawArc(shape, 208f, 124f, false, paint);
            }
        }
        fill(down ? 0xff101b27 : 0xfff2f5f9, down ? 1.8f : 1.65f);
        paint.setTypeface(CONTROL_FONT);
        paint.setTextSize(b.text.length() > 1 ? r * .53f : r * .83f);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(b.text, 0, -(paint.ascent() + paint.descent()) / 2f, paint);
        canvas.restore();
    }

    /** Connected directional cross, each direction independently illuminated. */
    private void drawProDpad(Canvas canvas) {
        Button up = buttons[UP], left = buttons[LEFT];
        float cx = up.x, cy = left.y;
        float half = up.radius * .90f;
        float reach = (cy - up.y) + up.radius * .95f;
        canvas.save(); canvas.translate(cx, cy);
        float inset = 0f;
        for (int layer = 0; layer < 3; layer++) {
            fill(layer == 0 ? Color.BLACK : layer == 1 ? 0xff7e8994 : 0xff1b222b,
                    layer == 0 ? .67f : layer == 1 ? .90f : 1.13f);
            float h = half - inset, end = reach - inset;
            shape.set(-h, -end + (layer == 0 ? dp(1.5f) : 0f), h, end + (layer == 0 ? dp(1.5f) : 0f));
            canvas.drawRoundRect(shape, h * .27f, h * .27f, paint);
            shape.set(-end, -h + (layer == 0 ? dp(1.5f) : 0f), end, h + (layer == 0 ? dp(1.5f) : 0f));
            canvas.drawRoundRect(shape, h * .27f, h * .27f, paint);
            inset += dp(.9f);
        }
        for (int bit = LEFT; bit <= DOWN; bit++) {
            Button b = buttons[bit];
            float x = b.x - cx, y = b.y - cy;
            boolean down = held(b);
            if (down) {
                fill(0xffb2c8db, 1.32f);
                shape.set(x - half * .82f, y - half * .82f, x + half * .82f, y + half * .82f);
                canvas.drawRoundRect(shape, half * .24f, half * .24f, paint);
            }
            fill(down ? 0xff101b27 : 0xffe4eaf0, down ? 1.85f : 1.28f);
            paint.setTypeface(CONTROL_FONT);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(b.radius * .72f);
            canvas.drawText(b.text, x, y - (paint.ascent() + paint.descent()) / 2f, paint);
        }
        canvas.restore();
    }

    private void drawStick(Canvas canvas, boolean left) {
        int mode = left ? MODE_LEFT_STICK : MODE_RIGHT_STICK;
        float x = left ? leftX : rightX, y = left ? leftY : rightY;
        float knobX = x, knobY = y;
        boolean engaged = false;
        for (int i = 0; i < pointers.size(); i++) {
            Finger f = pointers.valueAt(i);
            if (f.mode != mode) continue;
            engaged = true;
            x = f.originX; y = f.originY;
            float dx = f.x - x, dy = f.y - y;
            float distance = (float)Math.hypot(dx, dy);
            if (distance > stickRadius) { dx *= stickRadius / distance; dy *= stickRadius / distance; }
            knobX = x + dx; knobY = y + dy;
            break;
        }
        if (!proStyle) {
            fill(Color.BLACK, 130f / 255f);
            canvas.drawCircle(x, y, stickRadius, paint);
            stroke(Color.WHITE, 1f, dp(2f));
            canvas.drawCircle(x, y, stickRadius, paint);
            fill(Color.WHITE, 150f / 255f);
            canvas.drawCircle(knobX, knobY, stickRadius * .36f, paint);
            return;
        }
        fill(Color.BLACK, .70f);
        canvas.drawCircle(x, y + dp(1), stickRadius + dp(2), paint);
        fill(0xff86919e, .89f);
        canvas.drawCircle(x, y, stickRadius, paint);
        fill(0xff101823, 1.10f);
        canvas.drawCircle(x, y, stickRadius - dp(2.4f), paint);
        float cap = stickRadius * .36f;
        fill(Color.BLACK, .72f);
        canvas.drawCircle(knobX, knobY + dp(1.4f), cap + dp(1.3f), paint);
        fill(engaged ? 0xff91a5b8 : 0xff677584, engaged ? 1.40f : 1.14f);
        canvas.drawCircle(knobX, knobY, cap, paint);
        stroke(0xffe1e7ed, engaged ? 1.55f : .77f, dp(1.3f));
        canvas.drawCircle(knobX, knobY, cap - dp(2.1f), paint);
    }

    private void drawCombo(Canvas canvas) {
        if (!visible || !comboEnabled) return;
        boolean down = false;
        for (int i = 0; i < pointers.size(); i++)
            if (pointers.valueAt(i).mode == MODE_MACRO) { down = true; break; }
        float hw = comboRadius * 1.30f, hh = comboRadius * .73f;
        shape.set(comboX - hw, comboY - hh, comboX + hw, comboY + hh);
        fill(down ? 0xffb2c8db : 0xff18232e, down ? 1.5f : 1.17f);
        canvas.drawRoundRect(shape, hh, hh, paint);
        stroke(0xffe3ebf5, down ? 1.6f : 1f, dp(1.4f));
        canvas.drawRoundRect(shape, hh, hh, paint);
        fill(down ? 0xff162431 : Color.WHITE, 1.65f);
        paint.setTypeface(CONTROL_FONT); paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(comboRadius * .75f);
        canvas.drawText("M1", comboX, comboY - (paint.ascent() + paint.descent()) / 2f, paint);
        fill(Color.WHITE, .95f); paint.setTextSize(dp(10));
        canvas.drawText(comboName(), comboX, comboY - hh - dp(5), paint);
    }

    private void drawDebugPanel(Canvas canvas) {
        if (!diagnostics.enabled()) return;
        String[] lines = {diagnostics.status(), diagnostics.detail()};
        float width = Math.min(getWidth() * .58f, dp(430));
        float center = getWidth() * .5f;
        float top = menu.bottom + dp(6);
        shape.set(center - width / 2, top, center + width / 2, top + dp(37));
        paint.setStyle(Paint.Style.FILL); paint.setColor(0xff071422); paint.setAlpha(214);
        canvas.drawRoundRect(shape, dp(7), dp(7), paint);
        paint.setTypeface(CONTROL_FONT); paint.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < lines.length; i++) {
            paint.setColor(i == 0 ? 0xffe5f2ff : 0xffaecbdc); paint.setAlpha(235);
            paint.setTextSize(dp(10));
            float measured = paint.measureText(lines[i]);
            if (measured > width - dp(10)) paint.setTextSize(dp(10) * (width - dp(10)) / measured);
            canvas.drawText(lines[i], center, top + dp(14 + i * 16), paint);
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!drewOnce) {
            drewOnce = true;
            Log.i("TotkTouch", "Touch view first rendered (" + getWidth() + "x" + getHeight() + ")");
        }
        if (visible) {
            for (Button b : buttons) {
                if (!proStyle || b.bit < LEFT || b.bit > DOWN) drawButton(canvas, b);
            }
            if (proStyle) drawProDpad(canvas);
            drawStick(canvas, true);
            drawStick(canvas, false);
            drawCombo(canvas);
            if (bindings.toggleZl() && bindings.latchedZl()) {
                fill(0xffb2c8db, 1.7f);
                paint.setTypeface(CONTROL_FONT); paint.setTextAlign(Paint.Align.CENTER);
                paint.setTextSize(dp(9));
                Button b = buttons[ZL];
                canvas.drawText("LOCK", b.x, b.y - b.radius * 1.48f, paint);
            }
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
        drawDebugPanel(canvas);
    }
}

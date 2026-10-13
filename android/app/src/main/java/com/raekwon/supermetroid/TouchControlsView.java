package com.raekwon.supermetroid;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.hardware.input.InputManager;
import android.util.SparseBooleanArray;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import org.libsdl.app.SDLActivity;

// Touch overlay for the Android port. It does not touch the C side at all:
// every on-screen button is turned into the SAME Android key the default
// sm.ini keyboard map already expects, and injected through SDL's own
// SDLActivity.onNativeKeyDown/Up (the same path a real keyboard takes).
//
// Default [KeyMap] Controls (sm.ini):
//   Up, Down, Left, Right, Select, Start, A,  B,  X,  Y,  L,  R
//   arrows,                RShift, Enter, x,  z,  s,  a,  c,  v
// If you change Controls= in sm.ini, change KEYCODES below to match.
//
// Super Metroid default actions: A = jump, B = run, X = shoot,
// Y = cancel item, Select = switch item, L/R = aim down/up.
public class TouchControlsView extends View implements InputManager.InputDeviceListener {

    // Hide the overlay while a physical gamepad is connected (e.g. the Thor's
    // built-in pad or a Bluetooth controller). Set to false to always show it.
    private static final boolean AUTO_HIDE_WITH_GAMEPAD = true;

    private static final int[] OPACITY = { 70, 110, 150, 200 };      // 0..255
    private static final float[] SIZE = { 0.85f, 1.0f, 1.2f };
    private static final String[] SIZE_NAME = { "P", "M", "G" };

    private static final int BTN_UP = 0, BTN_DOWN = 1, BTN_LEFT = 2, BTN_RIGHT = 3,
            BTN_A = 4, BTN_B = 5, BTN_X = 6, BTN_Y = 7, BTN_L = 8, BTN_R = 9,
            BTN_SELECT = 10, BTN_START = 11, BTN_COUNT = 12;

    private static final int[] KEYCODES = {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_X,   // SNES A
        KeyEvent.KEYCODE_Z,   // SNES B
        KeyEvent.KEYCODE_S,   // SNES X
        KeyEvent.KEYCODE_A,   // SNES Y
        KeyEvent.KEYCODE_C,   // SNES L
        KeyEvent.KEYCODE_V,   // SNES R
        KeyEvent.KEYCODE_SHIFT_RIGHT, // Select
        KeyEvent.KEYCODE_ENTER,       // Start
    };

    private static final class Btn {
        final int id;
        final String label;
        final boolean circle;
        float cx, cy, r;
        final RectF rect = new RectF();
        Btn(int id, String label, boolean circle) {
            this.id = id;
            this.label = label;
            this.circle = circle;
        }
    }

    private final Btn[] btns = {
        new Btn(BTN_A, "A", true),
        new Btn(BTN_B, "B", true),
        new Btn(BTN_X, "X", true),
        new Btn(BTN_Y, "Y", true),
        new Btn(BTN_L, "L", false),
        new Btn(BTN_R, "R", false),
        new Btn(BTN_SELECT, "SELECT", false),
        new Btn(BTN_START, "START", false),
    };

    private static final int MENU_ITEMS = 8;
    private final RectF[] menuRects = new RectF[MENU_ITEMS];
    private float menuCx, menuCy, menuR;
    private boolean menuOpen;
    private boolean turbo;

    private final boolean[] down = new boolean[BTN_COUNT];
    private final boolean[] want = new boolean[BTN_COUNT];
    private final SparseBooleanArray menuPointers = new SparseBooleanArray();

    private float dpadCx, dpadCy, dpadR;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private final SharedPreferences prefs;
    private int opacityIdx;
    private int sizeIdx;
    private final float density;

    public TouchControlsView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        prefs = context.getSharedPreferences("touch_controls", Context.MODE_PRIVATE);
        opacityIdx = clamp(prefs.getInt("opacity", 1), 0, OPACITY.length - 1);
        sizeIdx = clamp(prefs.getInt("size", 1), 0, SIZE.length - 1);

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2f * density);
        fill.setStyle(Paint.Style.FILL);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);

        for (int i = 0; i < MENU_ITEMS; i++) menuRects[i] = new RectF();

        setFocusable(false);
        setFocusableInTouchMode(false);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private float dp(float v) {
        return v * density;
    }

    // ------------------------------------------------------------------
    // Gamepad auto-hide
    // ------------------------------------------------------------------

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        InputManager im = (InputManager) getContext().getSystemService(Context.INPUT_SERVICE);
        if (im != null) im.registerInputDeviceListener(this, null);
        updateVisibility();
    }

    @Override
    protected void onDetachedFromWindow() {
        InputManager im = (InputManager) getContext().getSystemService(Context.INPUT_SERVICE);
        if (im != null) im.unregisterInputDeviceListener(this);
        releaseAll();
        super.onDetachedFromWindow();
    }

    @Override public void onInputDeviceAdded(int deviceId) { updateVisibility(); }
    @Override public void onInputDeviceRemoved(int deviceId) { updateVisibility(); }
    @Override public void onInputDeviceChanged(int deviceId) { updateVisibility(); }

    private boolean hasPhysicalGamepad() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null || d.isVirtual()) continue;
            int src = d.getSources();
            if ((src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                return true;
            }
        }
        return false;
    }

    private void updateVisibility() {
        boolean hide = AUTO_HIDE_WITH_GAMEPAD && hasPhysicalGamepad();
        setVisibility(hide ? View.GONE : View.VISIBLE);
        if (hide) releaseAll();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) releaseAll();
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutControls(w, h);
    }

    private void layoutControls(int w, int h) {
        float s = SIZE[sizeIdx];
        float m = dp(20);
        float reach = dp(84) * s;

        // D-pad (fixed position, bottom-left)
        dpadR = reach;
        dpadCx = m + reach;
        dpadCy = h - m - reach;

        // Face buttons (SNES diamond, bottom-right)
        float fx = w - m - reach;
        float fy = h - m - reach;
        float r = dp(32) * s;
        float d = dp(50) * s;
        setCircle(BTN_A, fx + d, fy, r);
        setCircle(BTN_B, fx, fy + d, r);
        setCircle(BTN_X, fx, fy - d, r);
        setCircle(BTN_Y, fx - d, fy, r);

        // Shoulders (top corners)
        float sw = dp(96) * s, sh = dp(44) * s, top = dp(12);
        setRect(BTN_L, m, top, m + sw, top + sh);
        setRect(BTN_R, w - m - sw, top, w - m, top + sh);

        // Select / Start (bottom center)
        float pw = dp(64) * s, ph = dp(30) * s, bottom = h - dp(14);
        setRect(BTN_SELECT, w / 2f - dp(70) * s, bottom - ph, w / 2f - dp(70) * s + pw, bottom);
        setRect(BTN_START, w / 2f + dp(6) * s, bottom - ph, w / 2f + dp(6) * s + pw, bottom);

        // Menu button (top center) and its panel
        menuR = dp(18) * s;
        menuCx = w / 2f;
        menuCy = dp(10) + menuR;
        float iw = dp(74) * s, ih = dp(34) * s, gap = dp(6);
        float panelW = 4 * iw + 3 * gap;
        float left0 = w / 2f - panelW / 2f;
        float top0 = menuCy + menuR + gap;
        for (int i = 0; i < MENU_ITEMS; i++) {
            int col = i % 4, row = i / 4;
            float l = left0 + col * (iw + gap);
            float t = top0 + row * (ih + gap);
            menuRects[i].set(l, t, l + iw, t + ih);
        }
    }

    private Btn find(int id) {
        for (Btn b : btns) if (b.id == id) return b;
        return null;
    }

    private void setCircle(int id, float cx, float cy, float r) {
        Btn b = find(id);
        b.cx = cx;
        b.cy = cy;
        b.r = r;
    }

    private void setRect(int id, float l, float t, float r, float b) {
        find(id).rect.set(l, t, r, b);
    }

    // ------------------------------------------------------------------
    // Touch handling (multitouch, recomputed from all fingers every event
    // so sliding from one button to another works)
    // ------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();

        if (action == MotionEvent.ACTION_CANCEL) {
            menuPointers.clear();
            releaseAll();
            return true;
        }

        int actionIdx = e.getActionIndex();
        int actionId = e.getPointerId(actionIdx);
        boolean isDown = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN;
        boolean isUp = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP;

        if (isDown && handleMenuDown(e.getX(actionIdx), e.getY(actionIdx))) {
            menuPointers.put(actionId, true);
        }

        for (int i = 0; i < BTN_COUNT; i++) want[i] = false;
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (isUp && i == actionIdx) continue;
            if (menuPointers.get(e.getPointerId(i))) continue;
            mapPointer(e.getX(i), e.getY(i));
        }
        apply();

        if (isUp) menuPointers.delete(actionId);
        return true;
    }

    private void mapPointer(float x, float y) {
        // D-pad: 8 directions, small dead zone in the middle
        float dx = x - dpadCx, dy = y - dpadCy;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist >= dpadR * 0.18f && dist <= dpadR * 1.35f) {
            float thr = dist * 0.38f; // sin(22.5 deg)
            if (dx > thr) want[BTN_RIGHT] = true;
            if (dx < -thr) want[BTN_LEFT] = true;
            if (dy > thr) want[BTN_DOWN] = true;
            if (dy < -thr) want[BTN_UP] = true;
        }

        // Other buttons, with a little extra hit area; touching between two
        // face buttons presses both (run + jump, etc.)
        float slop = dp(6);
        for (Btn b : btns) {
            if (hits(b, x, y, slop)) want[b.id] = true;
        }
    }

    private boolean hits(Btn b, float x, float y, float slop) {
        if (b.circle) {
            float dx = x - b.cx, dy = y - b.cy;
            float rr = b.r * 1.2f;
            return dx * dx + dy * dy <= rr * rr;
        }
        return x >= b.rect.left - slop && x <= b.rect.right + slop
                && y >= b.rect.top - slop && y <= b.rect.bottom + slop;
    }

    private void apply() {
        boolean newPress = false;
        for (int i = 0; i < BTN_COUNT; i++) {
            if (want[i] == down[i]) continue;
            down[i] = want[i];
            if (want[i]) {
                SDLActivity.onNativeKeyDown(KEYCODES[i]);
                newPress = true;
            } else {
                SDLActivity.onNativeKeyUp(KEYCODES[i]);
            }
        }
        if (newPress) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        invalidate();
    }

    private void releaseAll() {
        for (int i = 0; i < BTN_COUNT; i++) {
            want[i] = false;
            if (down[i]) {
                down[i] = false;
                SDLActivity.onNativeKeyUp(KEYCODES[i]);
            }
        }
        if (turbo) {
            turbo = false;
            SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_TAB);
        }
        menuOpen = false;
        invalidate();
    }

    // ------------------------------------------------------------------
    // Menu: save / load / turbo / pause / ammo / opacity / size
    // ------------------------------------------------------------------

    private boolean handleMenuDown(float x, float y) {
        float dx = x - menuCx, dy = y - menuCy;
        float rr = menuR * 1.3f;
        if (dx * dx + dy * dy <= rr * rr) {
            menuOpen = !menuOpen;
            invalidate();
            return true;
        }
        if (menuOpen) {
            for (int i = 0; i < MENU_ITEMS; i++) {
                if (menuRects[i].contains(x, y)) {
                    menuAction(i);
                    invalidate();
                    return true;
                }
            }
        }
        return false;
    }

    private void tapKey(int key, int modifier) {
        if (modifier != 0) SDLActivity.onNativeKeyDown(modifier);
        SDLActivity.onNativeKeyDown(key);
        SDLActivity.onNativeKeyUp(key);
        if (modifier != 0) SDLActivity.onNativeKeyUp(modifier);
    }

    private void menuAction(int i) {
        switch (i) {
            case 0: // Save state, slot 1 (Shift+F1)
                tapKey(KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_SHIFT_LEFT);
                menuOpen = false;
                break;
            case 1: // Load state, slot 1 (F1)
                tapKey(KeyEvent.KEYCODE_F1, 0);
                menuOpen = false;
                break;
            case 2: // Turbo (the engine holds turbo while Tab is held)
                turbo = !turbo;
                if (turbo) SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_TAB);
                else SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_TAB);
                break;
            case 3: // Pause (dimmed)
                tapKey(KeyEvent.KEYCODE_P, 0);
                menuOpen = false;
                break;
            case 4:
                GameState.cycleSelectedAmmo(-1);
                break;
            case 5:
                GameState.cycleSelectedAmmo(1);
                break;
            case 6:
                opacityIdx = (opacityIdx + 1) % OPACITY.length;
                prefs.edit().putInt("opacity", opacityIdx).apply();
                break;
            case 7:
                sizeIdx = (sizeIdx + 1) % SIZE.length;
                prefs.edit().putInt("size", sizeIdx).apply();
                layoutControls(getWidth(), getHeight());
                break;
            default:
                break;
        }
    }

    private String menuLabel(int i) {
        switch (i) {
            case 0: return "Salvar";
            case 1: return "Carregar";
            case 2: return turbo ? "Turbo ON" : "Turbo";
            case 3: return "Pausa";
            case 4: return "Muni \u25C0";
            case 5: return "Muni \u25B6";
            case 6: return "Opac. " + (OPACITY[opacityIdx] * 100 / 255) + "%";
            default: return "Tam. " + SIZE_NAME[sizeIdx];
        }
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    private void style(boolean pressed) {
        int a = OPACITY[opacityIdx];
        if (pressed) {
            fill.setColor(Color.argb(Math.min(255, a + 40), 110, 190, 255));
        } else {
            fill.setColor(Color.argb(a / 3, 0, 0, 0));
        }
        stroke.setColor(Color.argb(a, 255, 255, 255));
        text.setColor(Color.argb(Math.min(255, a + 60), 255, 255, 255));
    }

    private void drawCentered(Canvas c, String s, float cx, float cy, float size) {
        text.setTextSize(size);
        float y = cy - (text.descent() + text.ascent()) / 2f;
        c.drawText(s, cx, y, text);
    }

    private void drawArrow(Canvas c, int id, float dirx, float diry) {
        float t = dpadR * 0.26f;
        float cx = dpadCx + dirx * dpadR * 0.62f;
        float cy = dpadCy + diry * dpadR * 0.62f;
        float px = -diry, py = dirx; // perpendicular
        path.reset();
        path.moveTo(cx + dirx * t, cy + diry * t);
        path.lineTo(cx - dirx * t * 0.7f + px * t, cy - diry * t * 0.7f + py * t);
        path.lineTo(cx - dirx * t * 0.7f - px * t, cy - diry * t * 0.7f - py * t);
        path.close();
        style(down[id]);
        c.drawPath(path, fill);
        c.drawPath(path, stroke);
    }

    @Override
    protected void onDraw(Canvas c) {
        float s = SIZE[sizeIdx];

        // D-pad
        style(false);
        c.drawCircle(dpadCx, dpadCy, dpadR, fill);
        c.drawCircle(dpadCx, dpadCy, dpadR, stroke);
        drawArrow(c, BTN_UP, 0, -1);
        drawArrow(c, BTN_DOWN, 0, 1);
        drawArrow(c, BTN_LEFT, -1, 0);
        drawArrow(c, BTN_RIGHT, 1, 0);

        // Buttons
        for (Btn b : btns) {
            style(down[b.id]);
            if (b.circle) {
                c.drawCircle(b.cx, b.cy, b.r, fill);
                c.drawCircle(b.cx, b.cy, b.r, stroke);
                drawCentered(c, b.label, b.cx, b.cy, b.r * 0.9f);
            } else {
                float rad = b.rect.height() / 2f;
                c.drawRoundRect(b.rect, rad, rad, fill);
                c.drawRoundRect(b.rect, rad, rad, stroke);
                boolean small = b.id == BTN_SELECT || b.id == BTN_START;
                drawCentered(c, b.label, b.rect.centerX(), b.rect.centerY(),
                        small ? dp(11) * s : dp(18) * s);
            }
        }

        // Menu button (three bars)
        style(menuOpen);
        c.drawCircle(menuCx, menuCy, menuR, fill);
        c.drawCircle(menuCx, menuCy, menuR, stroke);
        float bw = menuR * 0.9f, gapY = menuR * 0.38f;
        for (int k = -1; k <= 1; k++) {
            c.drawLine(menuCx - bw / 2f, menuCy + k * gapY, menuCx + bw / 2f, menuCy + k * gapY, stroke);
        }

        if (menuOpen) {
            for (int i = 0; i < MENU_ITEMS; i++) {
                boolean on = (i == 2 && turbo);
                style(on);
                // panel items need to stay readable even at low opacity
                fill.setColor(on ? Color.argb(210, 110, 190, 255) : Color.argb(170, 0, 0, 0));
                RectF r = menuRects[i];
                float rad = dp(8);
                c.drawRoundRect(r, rad, rad, fill);
                c.drawRoundRect(r, rad, rad, stroke);
                text.setColor(Color.WHITE);
                drawCentered(c, menuLabel(i), r.centerX(), r.centerY(), dp(12) * s);
            }
        }
    }
}

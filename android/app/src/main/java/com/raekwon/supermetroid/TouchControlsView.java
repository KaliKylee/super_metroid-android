package com.raekwon.supermetroid;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.input.InputManager;
import android.text.util.Linkify;
import android.util.SparseBooleanArray;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

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
//
// The "Controls" entry of the menu opens an editor (drag to move, size and
// opacity sliders, D-Pad / joystick switch, language). Everything is saved in
// SharedPreferences "touch_controls".
public class TouchControlsView extends View implements InputManager.InputDeviceListener {

    // Hide the overlay while a physical gamepad is connected (e.g. the Thor's
    // built-in pad or a Bluetooth controller). Set to false to always show it.
    private static final boolean AUTO_HIDE_WITH_GAMEPAD = true;

    // ---- key buttons (what the game receives) ----
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

    // ---- on-screen elements (what can be moved / resized) ----
    private static final int E_DIR = 0, E_A = 1, E_B = 2, E_X = 3, E_Y = 4,
            E_L = 5, E_R = 6, E_SELECT = 7, E_START = 8, E_MENU = 9, E_COUNT = 10;

    private static final int[] E_BTN = {
        -1, BTN_A, BTN_B, BTN_X, BTN_Y, BTN_L, BTN_R, BTN_SELECT, BTN_START, -1
    };
    private static final String[] E_LABEL = {
        "", "A", "B", "X", "Y", "L", "R", "SELECT", "START", ""
    };
    private static final boolean[] E_CIRCLE = {
        true, true, true, true, true, false, false, false, false, true
    };
    // Base size in dp: radius for circles, half width / half height for pills.
    private static final float[] E_HALF_W = { 84, 32, 32, 32, 32, 48, 48, 32, 32, 18 };
    private static final float[] E_HALF_H = { 84, 32, 32, 32, 32, 22, 22, 15, 15, 18 };

    // ---- menu grid ----
    private static final int MENU_ITEMS = 8;
    private static final int MENU_COLS = 4;
    private static final int MENU_ROWS = 2;
    private final RectF[] menuRects = new RectF[MENU_ITEMS];
    private boolean menuOpen;
    private boolean turbo;

    // ---- input state ----
    private final boolean[] down = new boolean[BTN_COUNT];
    private final boolean[] want = new boolean[BTN_COUNT];
    private final SparseBooleanArray menuPointers = new SparseBooleanArray();
    private boolean dirTouched;
    private float knobDx, knobDy;

    // ---- layout / settings ----
    private final float[] cx = new float[E_COUNT];
    private final float[] cy = new float[E_COUNT];
    private final float[] userScale = new float[E_COUNT];
    private float globalScale = 1f;
    private boolean joystick;
    private int opacityPct = 55;

    // ---- editor state ----
    private boolean editMode;
    private int selected = -1;
    private int dragElem = -1;
    private int dragPointer = -1;
    private float dragOffX, dragOffY;

    private ScrollView panel;
    private TextView tvTitle, tvHint, tvDirLabel, tvOpacity, tvSize, tvLang;
    private Button btnDpad, btnJoy, btnReset, btnDone;
    private Button btnLangAuto, btnLangPt, btnLangEn, btnLangEs;
    private SeekBar sbOpacity, sbSize;

    // ---- drawing ----
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF tmp = new RectF();

    private final SharedPreferences prefs;
    private final float density;

    public TouchControlsView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        prefs = context.getSharedPreferences("touch_controls", Context.MODE_PRIVATE);

        opacityPct = clamp(prefs.getInt("opacity_pct", 55), 0, 100);
        globalScale = clamp(prefs.getInt("global_scale", 100), 50, 200) / 100f;
        joystick = prefs.getBoolean("joystick", false);
        for (int e = 0; e < E_COUNT; e++) {
            userScale[e] = clamp(prefs.getInt("us_" + e, 100), 50, 200) / 100f;
        }

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2f * density);
        fill.setStyle(Paint.Style.FILL);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        selPaint.setStyle(Paint.Style.STROKE);
        selPaint.setStrokeWidth(2.5f * density);
        selPaint.setColor(Color.rgb(255, 213, 79));
        selPaint.setPathEffect(new DashPathEffect(new float[] { 8f * density, 6f * density }, 0f));

        for (int i = 0; i < MENU_ITEMS; i++) menuRects[i] = new RectF();

        setFocusable(false);
        setFocusableInTouchMode(false);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static float clampF(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private float dp(float v) {
        return v * density;
    }

    private String tr(String key) {
        return I18n.t(getContext(), key);
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
        hidePanel();
        editMode = false;
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
        if (hide && editMode) exitEditMode();
        setVisibility(hide ? View.GONE : View.VISIBLE);
        if (hide) releaseAll();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) releaseAll();
    }

    // ------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------

    private float eff(int e) {
        return globalScale * userScale[e];
    }

    // Radius for circles, half width for pills.
    private float halfW(int e) {
        return dp(E_HALF_W[e]) * eff(e);
    }

    private float halfH(int e) {
        return dp(E_HALF_H[e]) * eff(e);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutControls(w, h);
    }

    private void setDefault(float[] dx, float[] dy, int e, float x, float y) {
        dx[e] = x;
        dy[e] = y;
    }

    private void layoutControls(int w, int h) {
        if (w <= 0 || h <= 0) return;
        float[] dx = new float[E_COUNT];
        float[] dy = new float[E_COUNT];

        float m = dp(20);
        float reach = dp(84);
        setDefault(dx, dy, E_DIR, m + reach, h - m - reach);

        float fx = w - m - reach, fy = h - m - reach, d = dp(50);
        setDefault(dx, dy, E_A, fx + d, fy);
        setDefault(dx, dy, E_B, fx, fy + d);
        setDefault(dx, dy, E_X, fx, fy - d);
        setDefault(dx, dy, E_Y, fx - d, fy);

        float top = dp(12);
        setDefault(dx, dy, E_L, m + dp(48), top + dp(22));
        setDefault(dx, dy, E_R, w - m - dp(48), top + dp(22));

        float bottom = h - dp(14);
        setDefault(dx, dy, E_SELECT, w / 2f - dp(38), bottom - dp(15));
        setDefault(dx, dy, E_START, w / 2f + dp(38), bottom - dp(15));

        setDefault(dx, dy, E_MENU, w / 2f, dp(10) + dp(18));

        for (int e = 0; e < E_COUNT; e++) {
            float sx = prefs.getFloat("ex_" + e, -1f);
            if (sx >= 0f) {
                cx[e] = clampF(sx * w, 0f, w);
                cy[e] = clampF(prefs.getFloat("ey_" + e, 0.5f) * h, 0f, h);
            } else {
                cx[e] = dx[e];
                cy[e] = dy[e];
            }
        }
    }

    private void layoutMenuPanel() {
        float sc = clampF(eff(E_MENU), 0.85f, 1.2f);
        float iw = dp(74) * sc, ih = dp(34) * sc, gap = dp(6);
        float panelW = MENU_COLS * iw + (MENU_COLS - 1) * gap;
        float panelH = MENU_ROWS * ih + (MENU_ROWS - 1) * gap;
        float r = halfW(E_MENU);

        float maxL = Math.max(dp(4), getWidth() - panelW - dp(4));
        float left0 = Math.min(Math.max(cx[E_MENU] - panelW / 2f, dp(4)), maxL);
        float top0 = cy[E_MENU] + r + gap;
        if (top0 + panelH > getHeight() - dp(4)) top0 = cy[E_MENU] - r - gap - panelH;
        if (top0 < dp(4)) top0 = dp(4);

        for (int i = 0; i < MENU_ITEMS; i++) {
            int col = i % MENU_COLS, row = i / MENU_COLS;
            float l = left0 + col * (iw + gap);
            float t = top0 + row * (ih + gap);
            menuRects[i].set(l, t, l + iw, t + ih);
        }
    }

    private boolean hitPlay(int e, float x, float y) {
        float dx = x - cx[e], dy = y - cy[e];
        if (E_CIRCLE[e]) {
            float r = halfW(e) * 1.2f;
            return dx * dx + dy * dy <= r * r;
        }
        float slop = dp(6);
        return Math.abs(dx) <= halfW(e) + slop && Math.abs(dy) <= halfH(e) + slop;
    }

    private boolean hitEdit(int e, float x, float y) {
        float dx = x - cx[e], dy = y - cy[e];
        float slop = dp(6);
        if (E_CIRCLE[e]) {
            float r = halfW(e) + slop;
            return dx * dx + dy * dy <= r * r;
        }
        return Math.abs(dx) <= halfW(e) + slop && Math.abs(dy) <= halfH(e) + slop;
    }

    // ------------------------------------------------------------------
    // Touch handling
    // ------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (editMode) return handleEditTouch(e);

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
        dirTouched = false;
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
        // Directional control: D-Pad or joystick, both 8-way, fixed position.
        float dx = x - cx[E_DIR], dy = y - cy[E_DIR];
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        float R = halfW(E_DIR);
        float outer = joystick ? 1.6f : 1.35f;
        float dead = joystick ? 0.22f : 0.18f;
        if (dist <= R * outer) {
            dirTouched = true;
            knobDx = dx;
            knobDy = dy;
            if (dist >= R * dead) {
                float thr = dist * 0.38f; // sin(22.5 deg)
                if (dx > thr) want[BTN_RIGHT] = true;
                if (dx < -thr) want[BTN_LEFT] = true;
                if (dy > thr) want[BTN_DOWN] = true;
                if (dy < -thr) want[BTN_UP] = true;
            }
        }

        // Other buttons, with a little extra hit area; touching between two
        // face buttons presses both (run + jump, etc.)
        for (int e = E_A; e <= E_START; e++) {
            if (hitPlay(e, x, y)) want[E_BTN[e]] = true;
        }
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
        dirTouched = false;
        menuOpen = false;
        invalidate();
    }

    // ------------------------------------------------------------------
    // Menu: save / load / turbo / pause / ammo / controls / credits
    // ------------------------------------------------------------------

    private boolean handleMenuDown(float x, float y) {
        float dx = x - cx[E_MENU], dy = y - cy[E_MENU];
        float rr = halfW(E_MENU) * 1.3f;
        if (dx * dx + dy * dy <= rr * rr) {
            menuOpen = !menuOpen;
            invalidate();
            return true;
        }
        if (menuOpen) {
            layoutMenuPanel();
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
                menuOpen = false;
                enterEditMode();
                break;
            case 7:
                menuOpen = false;
                showCredits();
                break;
            default:
                break;
        }
    }

    private String menuLabel(int i) {
        switch (i) {
            case 0: return tr("save");
            case 1: return tr("load");
            case 2: return tr(turbo ? "turbo_on" : "turbo");
            case 3: return tr("pause");
            case 4: return tr("ammo_prev");
            case 5: return tr("ammo_next");
            case 6: return tr("controls");
            default: return tr("credits");
        }
    }

    private void showCredits() {
        TextView tv = new TextView(getContext());
        tv.setText("Port By Kali Kyle\nhttps://discord.gg/fUnXU4RZgn");
        tv.setTextSize(16f);
        int pad = (int) dp(24);
        tv.setPadding(pad, pad / 2, pad, pad / 2);
        Linkify.addLinks(tv, Linkify.WEB_URLS);
        new AlertDialog.Builder(getContext())
                .setTitle("Super Metroid")
                .setView(tv)
                .setPositiveButton("OK", null)
                .show();
    }

    // ------------------------------------------------------------------
    // Controls editor
    // ------------------------------------------------------------------

    private void enterEditMode() {
        releaseAll(); // also closes the menu and releases turbo
        editMode = true;
        selected = -1;
        showPanel();
        invalidate();
    }

    private void exitEditMode() {
        editMode = false;
        selected = -1;
        dragElem = -1;
        dragPointer = -1;
        hidePanel();
        invalidate();
    }

    private boolean handleEditTouch(MotionEvent e) {
        int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                float x = e.getX(), y = e.getY();
                int pick = -1;
                float best = Float.MAX_VALUE;
                for (int i = 0; i < E_COUNT; i++) {
                    if (!hitEdit(i, x, y)) continue;
                    float dx = x - cx[i], dy = y - cy[i];
                    float dd = dx * dx + dy * dy;
                    if (dd < best) {
                        best = dd;
                        pick = i;
                    }
                }
                selectElement(pick);
                if (pick >= 0) {
                    dragElem = pick;
                    dragPointer = e.getPointerId(0);
                    dragOffX = x - cx[pick];
                    dragOffY = y - cy[pick];
                }
                invalidate();
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragElem >= 0) {
                    int idx = e.findPointerIndex(dragPointer);
                    if (idx >= 0) {
                        cx[dragElem] = clampF(e.getX(idx) - dragOffX, 0f, getWidth());
                        cy[dragElem] = clampF(e.getY(idx) - dragOffY, 0f, getHeight());
                        invalidate();
                    }
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (dragElem >= 0) savePos(dragElem);
                dragElem = -1;
                dragPointer = -1;
                break;
            }
            default:
                break;
        }
        return true;
    }

    private void selectElement(int e) {
        selected = e;
        if (panel != null) updateSizeUi();
    }

    private void savePos(int e) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        prefs.edit()
                .putFloat("ex_" + e, cx[e] / w)
                .putFloat("ey_" + e, cy[e] / h)
                .apply();
    }

    private void resetLayout() {
        SharedPreferences.Editor ed = prefs.edit();
        for (int e = 0; e < E_COUNT; e++) {
            ed.remove("ex_" + e).remove("ey_" + e).remove("us_" + e);
            userScale[e] = 1f;
        }
        ed.remove("global_scale");
        ed.apply();
        globalScale = 1f;
        layoutControls(getWidth(), getHeight());
        updateSizeUi();
        invalidate();
    }

    private String elementName(int e) {
        switch (e) {
            case E_DIR: return tr(joystick ? "joystick" : "dpad");
            case E_SELECT: return tr("select");
            case E_START: return tr("start");
            case E_MENU: return tr("menu");
            default: return E_LABEL[e];
        }
    }

    private GradientDrawable roundedBg(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private TextView mkText(float sp, boolean bold, int color) {
        TextView t = new TextView(getContext());
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private Button mkBtn(View.OnClickListener l) {
        Button b = new Button(getContext());
        b.setAllCaps(false);
        b.setTextSize(12f);
        b.setTextColor(Color.WHITE);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        int ph = (int) dp(8), pv = (int) dp(6);
        b.setPadding(ph, pv, ph, pv);
        b.setOnClickListener(l);
        styleChip(b, false);
        return b;
    }

    private void styleChip(Button b, boolean on) {
        b.setBackground(roundedBg(on ? Color.argb(255, 47, 111, 219) : Color.argb(255, 60, 66, 90), 8));
    }

    private LinearLayout.LayoutParams rowLp(float topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) dp(topDp);
        return lp;
    }

    private LinearLayout.LayoutParams cellLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        int m = (int) dp(2);
        lp.setMargins(m, 0, m, 0);
        return lp;
    }

    private Button langBtn(String label, final String code) {
        Button b = mkBtn(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                I18n.setLang(getContext(), code);
                applyPanelTexts();
                invalidate();
            }
        });
        b.setText(label);
        return b;
    }

    private void showPanel() {
        if (panel != null) return;
        ViewGroup parent = (ViewGroup) getParent();
        if (parent == null) return;
        Context ctx = getContext();

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = (int) dp(12);
        box.setPadding(p, p, p, p);

        tvTitle = mkText(17f, true, Color.WHITE);
        box.addView(tvTitle, rowLp(0));

        tvHint = mkText(12f, false, Color.argb(200, 255, 255, 255));
        box.addView(tvHint, rowLp(4));

        tvDirLabel = mkText(13f, true, Color.WHITE);
        box.addView(tvDirLabel, rowLp(12));

        LinearLayout dirRow = new LinearLayout(ctx);
        dirRow.setOrientation(LinearLayout.HORIZONTAL);
        btnDpad = mkBtn(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setJoystick(false);
            }
        });
        btnJoy = mkBtn(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setJoystick(true);
            }
        });
        dirRow.addView(btnDpad, cellLp());
        dirRow.addView(btnJoy, cellLp());
        box.addView(dirRow, rowLp(6));

        tvOpacity = mkText(13f, true, Color.WHITE);
        box.addView(tvOpacity, rowLp(12));
        sbOpacity = new SeekBar(ctx);
        sbOpacity.setMax(100);
        sbOpacity.setProgress(opacityPct);
        sbOpacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                opacityPct = progress;
                prefs.edit().putInt("opacity_pct", opacityPct).apply();
                tvOpacity.setText(tr("opacity") + ": " + opacityPct + "%");
                invalidate();
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        box.addView(sbOpacity, rowLp(2));

        tvSize = mkText(13f, true, Color.WHITE);
        box.addView(tvSize, rowLp(12));
        sbSize = new SeekBar(ctx);
        sbSize.setMax(150); // 50%..200%
        sbSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                int pct = 50 + progress;
                if (selected >= 0) {
                    userScale[selected] = pct / 100f;
                    prefs.edit().putInt("us_" + selected, pct).apply();
                } else {
                    globalScale = pct / 100f;
                    prefs.edit().putInt("global_scale", pct).apply();
                }
                updateSizeLabel();
                invalidate();
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        box.addView(sbSize, rowLp(2));

        LinearLayout actionRow = new LinearLayout(ctx);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        btnReset = mkBtn(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resetLayout();
            }
        });
        btnDone = mkBtn(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exitEditMode();
            }
        });
        actionRow.addView(btnReset, cellLp());
        actionRow.addView(btnDone, cellLp());
        box.addView(actionRow, rowLp(14));

        tvLang = mkText(13f, true, Color.WHITE);
        box.addView(tvLang, rowLp(14));
        LinearLayout langRow = new LinearLayout(ctx);
        langRow.setOrientation(LinearLayout.HORIZONTAL);
        btnLangAuto = langBtn("Auto", I18n.AUTO);
        btnLangPt = langBtn("Portugu\u00eas", "pt");
        btnLangEn = langBtn("English", "en");
        btnLangEs = langBtn("Espa\u00f1ol", "es");
        langRow.addView(btnLangAuto, cellLp());
        langRow.addView(btnLangPt, cellLp());
        langRow.addView(btnLangEn, cellLp());
        langRow.addView(btnLangEs, cellLp());
        box.addView(langRow, rowLp(6));

        panel = new ScrollView(ctx);
        panel.addView(box, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        GradientDrawable bg = roundedBg(Color.argb(238, 18, 20, 30), 14);
        bg.setStroke((int) dp(1), Color.argb(120, 255, 255, 255));
        panel.setBackground(bg);

        int wPanel = Math.min((int) dp(340), getWidth() - (int) dp(24));
        int hPanel = Math.max((int) dp(160), Math.min((int) dp(330), getHeight() - (int) dp(110)));
        ViewGroup.LayoutParams lp;
        if (parent instanceof RelativeLayout) {
            RelativeLayout.LayoutParams rl = new RelativeLayout.LayoutParams(wPanel, hPanel);
            rl.addRule(RelativeLayout.CENTER_IN_PARENT);
            lp = rl;
        } else {
            lp = new ViewGroup.LayoutParams(wPanel, hPanel);
        }
        parent.addView(panel, lp);

        applyPanelTexts();
    }

    private void hidePanel() {
        if (panel == null) return;
        ViewGroup p = (ViewGroup) panel.getParent();
        if (p != null) p.removeView(panel);
        panel = null;
    }

    private void setJoystick(boolean on) {
        joystick = on;
        prefs.edit().putBoolean("joystick", on).apply();
        applyPanelTexts();
        invalidate();
    }

    private void applyPanelTexts() {
        if (panel == null) return;
        tvTitle.setText(tr("controls"));
        tvHint.setText(tr("edit_hint"));
        tvDirLabel.setText(tr("directional"));
        btnDpad.setText(tr("dpad"));
        btnJoy.setText(tr("joystick"));
        styleChip(btnDpad, !joystick);
        styleChip(btnJoy, joystick);
        tvOpacity.setText(tr("opacity") + ": " + opacityPct + "%");
        sbOpacity.setProgress(opacityPct);
        updateSizeUi();
        btnReset.setText(tr("reset"));
        btnDone.setText(tr("done"));
        tvLang.setText(tr("language"));
        String cur = I18n.pref(getContext());
        styleChip(btnLangAuto, I18n.AUTO.equals(cur));
        styleChip(btnLangPt, "pt".equals(cur));
        styleChip(btnLangEn, "en".equals(cur));
        styleChip(btnLangEs, "es".equals(cur));
    }

    private int sizePercent() {
        return Math.round((selected >= 0 ? userScale[selected] : globalScale) * 100f);
    }

    private void updateSizeLabel() {
        if (panel == null) return;
        String label = selected >= 0
                ? tr("size_sel").replace("%s", elementName(selected))
                : tr("size_all");
        tvSize.setText(label + ": " + sizePercent() + "%");
    }

    private void updateSizeUi() {
        if (panel == null) return;
        sbSize.setProgress(clamp(sizePercent() - 50, 0, 150));
        updateSizeLabel();
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    private void style(boolean pressed, int a) {
        if (pressed) {
            fill.setColor(Color.argb(Math.min(255, a + 40), 110, 190, 255));
        } else {
            fill.setColor(Color.argb(a / 3, 0, 0, 0));
        }
        stroke.setColor(Color.argb(a, 255, 255, 255));
        text.setColor(Color.argb(Math.min(255, a + 60), 255, 255, 255));
    }

    private void drawCentered(Canvas c, String s, float x, float y, float size) {
        text.setTextSize(size);
        float yy = y - (text.descent() + text.ascent()) / 2f;
        c.drawText(s, x, yy, text);
    }

    private void drawArrow(Canvas c, int id, float dirx, float diry, float R, int a) {
        float t = R * 0.26f;
        float ax = cx[E_DIR] + dirx * R * 0.62f;
        float ay = cy[E_DIR] + diry * R * 0.62f;
        float px = -diry, py = dirx; // perpendicular
        path.reset();
        path.moveTo(ax + dirx * t, ay + diry * t);
        path.lineTo(ax - dirx * t * 0.7f + px * t, ay - diry * t * 0.7f + py * t);
        path.lineTo(ax - dirx * t * 0.7f - px * t, ay - diry * t * 0.7f - py * t);
        path.close();
        style(down[id], a);
        c.drawPath(path, fill);
        c.drawPath(path, stroke);
    }

    private void drawDirectional(Canvas c, int a) {
        float R = halfW(E_DIR);
        float x = cx[E_DIR], y = cy[E_DIR];
        style(false, a);
        c.drawCircle(x, y, R, fill);
        c.drawCircle(x, y, R, stroke);
        if (joystick) {
            float kr = R * 0.42f;
            float kx = x, ky = y;
            if (dirTouched) {
                float len = (float) Math.sqrt(knobDx * knobDx + knobDy * knobDy);
                float maxLen = R * 0.58f;
                float k = len > maxLen ? maxLen / len : 1f;
                kx += knobDx * k;
                ky += knobDy * k;
            }
            style(dirTouched, a);
            c.drawCircle(kx, ky, kr, fill);
            c.drawCircle(kx, ky, kr, stroke);
        } else {
            drawArrow(c, BTN_UP, 0, -1, R, a);
            drawArrow(c, BTN_DOWN, 0, 1, R, a);
            drawArrow(c, BTN_LEFT, -1, 0, R, a);
            drawArrow(c, BTN_RIGHT, 1, 0, R, a);
        }
    }

    private void drawButton(Canvas c, int e, int a) {
        style(down[E_BTN[e]], a);
        if (E_CIRCLE[e]) {
            float r = halfW(e);
            c.drawCircle(cx[e], cy[e], r, fill);
            c.drawCircle(cx[e], cy[e], r, stroke);
            drawCentered(c, E_LABEL[e], cx[e], cy[e], r * 0.9f);
        } else {
            tmp.set(cx[e] - halfW(e), cy[e] - halfH(e), cx[e] + halfW(e), cy[e] + halfH(e));
            float rad = halfH(e);
            c.drawRoundRect(tmp, rad, rad, fill);
            c.drawRoundRect(tmp, rad, rad, stroke);
            boolean small = e == E_SELECT || e == E_START;
            drawCentered(c, E_LABEL[e], cx[e], cy[e], dp(small ? 11 : 18) * eff(e));
        }
    }

    private void drawMenuButton(Canvas c, int a) {
        float r = halfW(E_MENU);
        style(menuOpen, a);
        c.drawCircle(cx[E_MENU], cy[E_MENU], r, fill);
        c.drawCircle(cx[E_MENU], cy[E_MENU], r, stroke);
        float bw = r * 0.9f, gapY = r * 0.38f;
        for (int k = -1; k <= 1; k++) {
            c.drawLine(cx[E_MENU] - bw / 2f, cy[E_MENU] + k * gapY,
                    cx[E_MENU] + bw / 2f, cy[E_MENU] + k * gapY, stroke);
        }
    }

    private void drawMenuPanel(Canvas c, int a) {
        layoutMenuPanel();
        float sc = clampF(eff(E_MENU), 0.85f, 1.2f);
        for (int i = 0; i < MENU_ITEMS; i++) {
            boolean on = (i == 2 && turbo);
            style(on, a);
            // panel items need to stay readable even at low opacity
            fill.setColor(on ? Color.argb(210, 110, 190, 255) : Color.argb(170, 0, 0, 0));
            RectF r = menuRects[i];
            float rad = dp(8);
            c.drawRoundRect(r, rad, rad, fill);
            c.drawRoundRect(r, rad, rad, stroke);
            text.setColor(Color.WHITE);
            drawCentered(c, menuLabel(i), r.centerX(), r.centerY(), dp(12) * sc);
        }
    }

    private void drawSelection(Canvas c) {
        int e = selected;
        float pad = dp(7);
        if (E_CIRCLE[e]) {
            c.drawCircle(cx[e], cy[e], halfW(e) + pad, selPaint);
        } else {
            tmp.set(cx[e] - halfW(e) - pad, cy[e] - halfH(e) - pad,
                    cx[e] + halfW(e) + pad, cy[e] + halfH(e) + pad);
            c.drawRoundRect(tmp, dp(10), dp(10), selPaint);
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        int base = Math.round(opacityPct * 2.55f);
        if (editMode) {
            c.drawColor(Color.argb(70, 0, 0, 0));
            base = Math.max(base, 120);
        }

        drawDirectional(c, base);
        for (int e = E_A; e <= E_START; e++) drawButton(c, e, base);
        // The menu button never disappears completely, so it can always be found.
        drawMenuButton(c, Math.max(base, 90));

        if (menuOpen && !editMode) drawMenuPanel(c, base);
        if (editMode && selected >= 0) drawSelection(c);
    }
}

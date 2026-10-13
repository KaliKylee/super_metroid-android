package com.raekwon.supermetroid;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.util.Linkify;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

// Launcher activity. The game (native SnesInit(), src/main.c) expects a raw
// ROM file on disk - no BPS-patched asset blob step like zelda3-android's,
// since this decomp loads a ROM directly on every existing platform. This
// screen just makes sure getExternalFilesDir(null)/sm.smc exists, prompting
// a SAF file picker to copy the user's own ROM there if it doesn't yet, then
// hands off to MainActivity (the SDLActivity subclass that runs the game).
public class SetupActivity extends Activity {
    private static final int REQUEST_PICK_ROM = 1;
    private static final int MIN_ROM_SIZE = 512 * 1024; // smallest real SM dumps are ~2MB; refuse obvious junk

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        installDefaultIniIfMissing();

        if (romFile().length() > 0) {
            launchGame();
            return;
        }

        buildUi();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private void buildUi() {
        // Background: the (pre-blurred, darkened) banner, full screen.
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);

        ImageView bg = new ImageView(this);
        bg.setImageResource(R.drawable.setup_bg);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(bg, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        frame.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setGravity(Gravity.CENTER);
        int pad = (int) dp(16);
        outer.setPadding(pad, pad, pad, pad);
        scroll.addView(outer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Card that holds the controls, so they stand out from the banner.
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(rounded(Color.argb(150, 10, 12, 22), 20));
        int cp = (int) dp(20);
        card.setPadding(cp, cp, cp, cp);
        int maxW = (int) dp(520);
        int availW = getResources().getDisplayMetrics().widthPixels - 2 * pad;
        outer.addView(card, new LinearLayout.LayoutParams(Math.min(maxW, availW),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        status = new TextView(this);
        status.setText(I18n.t(this, "select_rom_msg"));
        status.setTextColor(Color.WHITE);
        status.setTextSize(16f);
        status.setGravity(Gravity.CENTER);
        card.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button pick = new Button(this);
        pick.setText(I18n.t(this, "select_rom_btn"));
        pick.setAllCaps(false);
        pick.setTextColor(Color.WHITE);
        pick.setTextSize(16f);
        pick.setTypeface(null, Typeface.BOLD);
        pick.setBackground(rounded(Color.argb(240, 200, 60, 30), 12));
        pick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                startActivityForResult(intent, REQUEST_PICK_ROM);
            }
        });
        LinearLayout.LayoutParams pickLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pickLp.topMargin = (int) dp(14);
        card.addView(pick, pickLp);

        // Credits
        TextView credit = new TextView(this);
        credit.setText("Port By Kali Kyle");
        credit.setTextColor(Color.WHITE);
        credit.setTextSize(15f);
        credit.setTypeface(null, Typeface.BOLD);
        credit.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams creditLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        creditLp.topMargin = (int) dp(18);
        card.addView(credit, creditLp);

        TextView discord = new TextView(this);
        discord.setText("https://discord.gg/fUnXU4RZgn");
        discord.setTextColor(Color.WHITE);
        discord.setLinkTextColor(Color.rgb(138, 180, 255));
        discord.setTextSize(14f);
        discord.setGravity(Gravity.CENTER);
        Linkify.addLinks(discord, Linkify.WEB_URLS);
        card.addView(discord, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Language selector
        TextView langLabel = new TextView(this);
        langLabel.setText(I18n.t(this, "language"));
        langLabel.setTextColor(Color.argb(210, 255, 255, 255));
        langLabel.setTextSize(13f);
        langLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams langLabelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        langLabelLp.topMargin = (int) dp(18);
        card.addView(langLabel, langLabelLp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        String current = I18n.pref(this);
        row.addView(chip("Auto", I18n.AUTO, current));
        row.addView(chip("Portugu\u00eas", "pt", current));
        row.addView(chip("English", "en", current));
        row.addView(chip("Espa\u00f1ol", "es", current));
        card.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(frame);
    }

    private Button chip(String label, final String code, String current) {
        boolean selected = code.equals(current);
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(12f);
        b.setTextColor(Color.WHITE);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        int ph = (int) dp(12), pv = (int) dp(7);
        b.setPadding(ph, pv, ph, pv);
        b.setBackground(rounded(selected ? Color.argb(255, 47, 111, 219) : Color.argb(140, 60, 66, 90), 10));
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                I18n.setLang(SetupActivity.this, code);
                recreate();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int m = (int) dp(3);
        lp.setMargins(m, m, m, m);
        b.setLayoutParams(lp);
        return b;
    }

    private File romFile() {
        return new File(getExternalFilesDir(null), "sm.smc");
    }

    // main.c's ParseConfigFile() looks for sm.ini relative to cwd, which
    // AndroidImpl_Init() chdir()s to this same external-files directory.
    // Only installs it once - never overwrites a user's own customized ini.
    private void installDefaultIniIfMissing() {
        File dest = new File(getExternalFilesDir(null), "sm.ini");
        if (dest.exists()) return;
        try (InputStream in = getAssets().open("sm.ini");
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } catch (Exception e) {
            // Non-fatal - the engine falls back to hardcoded defaults if sm.ini
            // is missing, it just won't have a gamepad quicksave/load binding.
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_ROM || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        try {
            copyRom(uri);
            launchGame();
        } catch (Exception e) {
            // copyRom() throws translation keys for its own errors; anything
            // else (I/O errors) keeps its original message.
            String msg = e.getMessage() == null ? "" : I18n.t(this, e.getMessage());
            if (status != null) {
                status.setText(I18n.t(this, "rom_read_error").replace("%s", msg));
            }
        }
    }

    private void copyRom(Uri uri) throws Exception {
        File dest = romFile();
        File tmp = new File(dest.getParentFile(), "sm.smc.tmp");
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(tmp)) {
            if (in == null) throw new IllegalStateException("err_open");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        if (tmp.length() < MIN_ROM_SIZE) {
            tmp.delete();
            throw new IllegalStateException("err_small");
        }
        if (!tmp.renameTo(dest)) {
            throw new IllegalStateException("err_finalize");
        }
    }

    private void launchGame() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}

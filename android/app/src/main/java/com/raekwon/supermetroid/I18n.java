package com.raekwon.supermetroid;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

// Tiny in-code translation table: English (default), Portuguese, Spanish.
//
// The language is "auto" (follow the device language; anything other than
// pt/es falls back to English) or one of "en" / "pt" / "es" picked manually.
// The choice is stored in SharedPreferences and shared by the ROM-selection
// screen and the in-game touch controls.
public final class I18n {
    public static final String AUTO = "auto";

    private static final String[] LANGS = { "en", "pt", "es" };
    private static final String PREFS = "settings";
    private static final String KEY = "lang";

    private static final Map<String, String[]> TABLE = new HashMap<String, String[]>();

    private static String pref; // "auto", "en", "pt" or "es"

    private static void add(String key, String en, String pt, String es) {
        TABLE.put(key, new String[] { en, pt, es });
    }

    static {
        // ROM selection screen
        add("select_rom_msg",
                "Select your Super Metroid ROM (sm.smc/.sfc) to continue.",
                "Selecione sua ROM de Super Metroid (sm.smc/.sfc) para continuar.",
                "Selecciona tu ROM de Super Metroid (sm.smc/.sfc) para continuar.");
        add("select_rom_btn", "Select ROM", "Selecionar ROM", "Seleccionar ROM");
        add("language", "Language", "Idioma", "Idioma");
        add("rom_read_error", "Couldn't read that file: %s",
                "N\u00e3o foi poss\u00edvel ler esse arquivo: %s",
                "No se pudo leer ese archivo: %s");
        add("err_open", "Could not open the picked file",
                "N\u00e3o foi poss\u00edvel abrir o arquivo escolhido",
                "No se pudo abrir el archivo elegido");
        add("err_small", "File is too small to be a Super Metroid ROM",
                "O arquivo \u00e9 pequeno demais para ser uma ROM de Super Metroid",
                "El archivo es demasiado peque\u00f1o para ser una ROM de Super Metroid");
        add("err_finalize", "Failed to save the ROM file",
                "Falha ao salvar o arquivo da ROM",
                "Error al guardar el archivo de la ROM");

        // In-game menu
        add("save", "Save", "Salvar", "Guardar");
        add("load", "Load", "Carregar", "Cargar");
        add("turbo", "Turbo", "Turbo", "Turbo");
        add("turbo_on", "Turbo ON", "Turbo ON", "Turbo ON");
        add("pause", "Pause", "Pausa", "Pausa");
        add("ammo_prev", "Ammo \u25C0", "Muni \u25C0", "Muni \u25C0");
        add("ammo_next", "Ammo \u25B6", "Muni \u25B6", "Muni \u25B6");
        add("controls", "Controls", "Controles", "Controles");
        add("credits", "Credits", "Cr\u00e9ditos", "Cr\u00e9ditos");

        // Controls editor
        add("edit_hint",
                "Drag a control to move it. Tap it to select it; tap empty space to adjust all controls.",
                "Arraste um controle para mover. Toque nele para selecionar; toque no vazio para ajustar todos.",
                "Arrastra un control para moverlo. T\u00f3calo para seleccionarlo; toca el vac\u00edo para ajustar todos.");
        add("directional", "Directional control", "Controle direcional", "Control direccional");
        add("dpad", "D-Pad", "D-Pad", "D-Pad");
        add("joystick", "Joystick", "Joystick", "Joystick");
        add("opacity", "Opacity", "Opacidade", "Opacidad");
        add("size_all", "Size (all controls)", "Tamanho (todos os controles)",
                "Tama\u00f1o (todos los controles)");
        add("size_sel", "Size (%s)", "Tamanho (%s)", "Tama\u00f1o (%s)");
        add("reset", "Reset layout", "Restaurar padr\u00e3o", "Restablecer");
        add("done", "Done", "Concluir", "Listo");
        add("menu", "Menu", "Menu", "Men\u00fa");
        add("select", "Select", "Select", "Select");
        add("start", "Start", "Start", "Start");
    }

    private I18n() {
    }

    private static boolean isSupported(String v) {
        for (String l : LANGS) {
            if (l.equals(v)) return true;
        }
        return false;
    }

    private static void load(Context c) {
        if (pref != null) return;
        SharedPreferences p = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String v = p.getString(KEY, AUTO);
        pref = isSupported(v) ? v : AUTO;
    }

    // What the user picked: "auto", "en", "pt" or "es".
    public static String pref(Context c) {
        load(c);
        return pref;
    }

    // The language actually in use: "en", "pt" or "es".
    public static String lang(Context c) {
        load(c);
        if (!AUTO.equals(pref)) return pref;
        String dev = Locale.getDefault().getLanguage();
        return isSupported(dev) ? dev : "en";
    }

    public static void setLang(Context c, String code) {
        String v = isSupported(code) ? code : AUTO;
        c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, v).apply();
        pref = v;
    }

    public static String t(Context c, String key) {
        String[] v = TABLE.get(key);
        if (v == null) return key == null ? "" : key;
        String l = lang(c);
        for (int i = 0; i < LANGS.length; i++) {
            if (LANGS[i].equals(l)) return v[i];
        }
        return v[0];
    }
}

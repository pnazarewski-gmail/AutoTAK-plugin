package com.atakmap.android.autotak;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Dziennik zdarzeń wtyczki do diagnozowania problemów: start, próby połączenia, odmowy, liczby
 * przekazanych obiektów, zmiany ustawień. Wpisy są techniczne i zawsze po angielsku, żeby dało
 * się je zestawić z dziennikiem aplikacji AutoTAK. Nie zawiera treści obiektów, znaków
 * wywoławczych, współrzędnych ani danych serwera. Zostaje w telefonie, dopóki użytkownik go nie
 * udostępni.
 */
public final class PluginLog {

    private static final int MAX_LINES = 300;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat STAMP = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    private static File file;
    private static int written;
    private static String lastText;

    private PluginLog() {
    }

    /** Wczytuje wpisy z poprzednich uruchomień ATAK. */
    public static synchronized void init(Context atakContext) {
        if (file != null)
            return;
        file = new File(atakContext.getFilesDir(), "autotak_plugin_events.log");
        try {
            if (file.exists()) {
                try (java.io.BufferedReader in = new java.io.BufferedReader(new java.io.FileReader(file))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        LINES.addLast(line);
                        if (LINES.size() > MAX_LINES)
                            LINES.removeFirst();
                    }
                }
                written = LINES.size();
            }
        } catch (Exception ignored) {
            // Uszkodzony plik dziennika nie może zatrzymać wtyczki.
        }
    }

    /** Dodaje wpis; taki sam jak poprzedni jest pomijany. */
    public static synchronized void add(String text) {
        if (text.equals(lastText))
            return;
        lastText = text;
        String line = STAMP.format(new Date()) + "  " + text;
        LINES.addLast(line);
        while (LINES.size() > MAX_LINES)
            LINES.removeFirst();
        if (file == null)
            return;
        try {
            boolean rewrite = ++written > MAX_LINES * 2;
            try (FileWriter out = new FileWriter(file, !rewrite)) {
                if (rewrite) {
                    for (String l : LINES)
                        out.write(l + "\n");
                    written = LINES.size();
                } else {
                    out.write(line + "\n");
                }
            }
        } catch (Exception ignored) {
        }
    }

    public static synchronized String text() {
        StringBuilder out = new StringBuilder();
        for (String l : LINES)
            out.append(l).append('\n');
        return out.toString();
    }

    /** Ostatnie wpisy, najnowszy na górze — do podglądu w panelu. */
    public static synchronized String recent(int count) {
        StringBuilder out = new StringBuilder();
        java.util.Iterator<String> it = LINES.descendingIterator();
        for (int i = 0; i < count && it.hasNext(); i++)
            out.append(it.next()).append('\n');
        return out.toString().trim();
    }

    public static synchronized void clear() {
        LINES.clear();
        lastText = null;
        written = 0;
        try {
            if (file != null)
                new FileWriter(file, false).close();
        } catch (Exception ignored) {
        }
    }
}

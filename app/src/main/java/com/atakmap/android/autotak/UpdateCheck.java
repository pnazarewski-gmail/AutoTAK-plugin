package com.atakmap.android.autotak;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.atakmap.android.autotak.plugin.BuildConfig;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Sprawdzanie, czy jest nowsza wersja wtyczki — tylko wtedy, gdy wtyczkę zainstalował sklep Google
 * Play. Przy instalacji z pliku albo przez MDM funkcja jest wyłączona i wtyczka nie łączy się
 * z internetem.
 *
 * Wtyczka działa wewnątrz ATAK, więc nie może użyć wbudowanych aktualizacji sklepu (sklep
 * odpowiada tylko aplikacji, która pyta o samą siebie). Numer najnowszej wersji wysłanej do sklepu
 * jest więc czytany z małego pliku w publicznym repozytorium źródeł wtyczki. Zapytanie niczego nie
 * przesyła. Samą aktualizację wykonuje sklep — wtyczka tylko otwiera w nim swoją stronę.
 */
public final class UpdateCheck {

    private static final String TAG = "AutoTAKLink";
    private static final String STORE = "com.android.vending";
    static final String VERSION_URL =
            "https://raw.githubusercontent.com/pnazarewski-gmail/AutoTAK-plugin/main/play-version.json";
    static final String PREF_AUTO = "autotak.check_updates_auto";

    /** Wynik ostatniego sprawdzenia w tym uruchomieniu: nazwa nowszej wersji albo null. */
    private static volatile String available;

    public interface Callback {
        /** @param newer nazwa nowszej wersji albo null, gdy zainstalowana jest aktualna;
         *  @param failed true, gdy sprawdzenie się nie udało (brak sieci, błąd odpowiedzi). */
        void onResult(String newer, boolean failed);
    }

    private UpdateCheck() {
    }

    /** Czy wtyczkę zainstalował sklep Google Play. */
    public static boolean fromStore(Context pluginContext) {
        try {
            PackageManager pm = pluginContext.getPackageManager();
            String pkg = pluginContext.getPackageName();
            String installer;
            if (Build.VERSION.SDK_INT >= 30)
                installer = pm.getInstallSourceInfo(pkg).getInstallingPackageName();
            else
                installer = legacyInstaller(pm, pkg);
            return STORE.equals(installer);
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static String legacyInstaller(PackageManager pm, String pkg) {
        return pm.getInstallerPackageName(pkg);
    }

    public static String available() {
        return available;
    }

    private static long installedCode(Context pluginContext) throws Exception {
        // Tylko kompilacja testowa: znacznik AutoTAK.oldver udaje starą wersję, żeby zobaczyć komunikat.
        if (BuildConfig.DEBUG && Log.isLoggable("AutoTAK.oldver", Log.DEBUG))
            return 1;
        PackageInfo info = pluginContext.getPackageManager().getPackageInfo(pluginContext.getPackageName(), 0);
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : legacyCode(info);
    }

    @SuppressWarnings("deprecation")
    private static long legacyCode(PackageInfo info) {
        return info.versionCode;
    }

    /**
     * Sprawdza w tle i oddaje wynik w wątku głównym. Przy instalacji spoza sklepu nie robi nic
     * (wynik: brak nowszej wersji, bez połączenia).
     */
    public static void check(final Context pluginContext, final Callback callback) {
        final Handler main = new Handler(Looper.getMainLooper());
        if (!fromStore(pluginContext)) {
            main.post(() -> callback.onResult(null, false));
            return;
        }
        new Thread(() -> {
            String newer = null;
            boolean failed = false;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(VERSION_URL).openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                connection.setUseCaches(false);
                if (connection.getResponseCode() != 200)
                    throw new IllegalStateException("HTTP " + connection.getResponseCode());
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (InputStream in = connection.getInputStream()) {
                    byte[] buffer = new byte[1024];
                    int read;
                    while ((read = in.read(buffer)) > 0 && out.size() < 4096)
                        out.write(buffer, 0, read);
                }
                JSONObject json = new JSONObject(out.toString("UTF-8"));
                long latest = json.getLong("versionCode");
                if (latest > installedCode(pluginContext))
                    newer = json.optString("versionName", String.valueOf(latest));
                PluginLog.add("Update check: " + (newer == null ? "up to date" : "newer version " + newer + " available"));
            } catch (Exception e) {
                failed = true;
                PluginLog.add("Update check failed (" + e.getClass().getSimpleName() + ")");
            } finally {
                if (connection != null)
                    connection.disconnect();
            }
            if (!failed)
                available = newer;
            final String result = newer;
            final boolean error = failed;
            main.post(() -> callback.onResult(result, error));
        }, "autotak-update-check").start();
    }

    /** Otwiera stronę wtyczki w sklepie Google Play (aktualizację wykonuje sklep). */
    public static void openStore(Context atakContext, Context pluginContext) {
        Intent intent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("market://details?id=" + pluginContext.getPackageName()))
                .setPackage(STORE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            atakContext.startActivity(intent);
        } catch (Exception e) {
            PluginLog.add("Could not open Google Play (" + e.getClass().getSimpleName() + ")");
        }
    }
}

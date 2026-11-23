package com.atakmap.android.autotak;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.autotak.plugin.BuildConfig;
import com.atakmap.android.autotak.plugin.R;
import com.atakmap.android.dropdown.DropDown;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.util.FileProviderHelper;
import com.atakmap.app.SettingsActivity;
import com.atakmap.coremap.filesystem.FileSystemUtils;

import java.io.File;

import java.util.ArrayList;
import java.util.List;

/**
 * Panel wtyczki w ATAK: stan łącza z AutoTAK, bieżące ustawienia w skrócie, wejście do ustawień
 * i wymuszenie synchronizacji. Stan odświeża się co sekundę, dopóki panel jest otwarty.
 */
public class StatusDropDownReceiver extends DropDownReceiver implements DropDown.OnStateListener {

    private static final long REFRESH_MS = 1000;

    private final Context pluginContext;
    private final CompanionLink link;
    private final View rootView;
    private final TextView stateView;
    private final TextView detailsView;
    private final TextView settingsView;
    private final TextView eventsView;
    private boolean open;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            if (!open)
                return;
            render();
            getMapView().postDelayed(this, REFRESH_MS);
        }
    };

    public StatusDropDownReceiver(MapView mapView, final Context context, final CompanionLink link) {
        super(mapView);
        this.pluginContext = context;
        this.link = link;
        rootView = PluginLayoutInflater.inflate(context, R.layout.status_panel, null);
        stateView = rootView.findViewById(R.id.panel_state);
        detailsView = rootView.findViewById(R.id.panel_details);
        settingsView = rootView.findViewById(R.id.panel_settings);
        eventsView = rootView.findViewById(R.id.panel_events);
        ((Button) rootView.findViewById(R.id.panel_events_share)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareLog();
            }
        });
        ((Button) rootView.findViewById(R.id.panel_events_clear)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PluginLog.clear();
                render();
            }
        });
        ((Button) rootView.findViewById(R.id.panel_history)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(getMapView().getContext())
                        .setTitle(context.getString(R.string.panel_history))
                        .setMessage(context.getString(R.string.plugin_history))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
        });
        Button settings = rootView.findViewById(R.id.panel_open_settings);
        settings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SettingsActivity.start(AutoTakMapComponent.PREFERENCES_KEY, null);
            }
        });
        Button force = rootView.findViewById(R.id.panel_force_sync);
        force.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                link.forceSync();
                Toast.makeText(getMapView().getContext(),
                        context.getString(R.string.panel_force_sync_done), Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!AutoTakMapComponent.SHOW_STATUS.equals(intent.getAction()))
            return;
        showDropDown(rootView, THREE_EIGHTHS_WIDTH, FULL_HEIGHT, FULL_WIDTH, HALF_HEIGHT, false, this);
        open = true;
        getMapView().removeCallbacks(refresh);
        refresh.run();
    }

    private void render() {
        final int state = link.isLinked() ? link.getState() : (link.getState() == CompanionLink.STATE_CONNECTED
                ? CompanionLink.STATE_LOST : link.getState());
        int title;
        switch (state) {
            case CompanionLink.STATE_CONNECTED:
                title = R.string.panel_state_connected;
                break;
            case CompanionLink.STATE_REFUSED:
                title = R.string.panel_state_refused;
                break;
            case CompanionLink.STATE_LOST:
                title = R.string.panel_state_lost;
                break;
            default:
                title = R.string.panel_state_waiting;
        }
        stateView.setText(pluginContext.getString(title));
        stateView.setTextColor(state == CompanionLink.STATE_CONNECTED ? 0xFF66BB6A : 0xFFFFB74D);

        StringBuilder details = new StringBuilder();
        if (state == CompanionLink.STATE_CONNECTED) {
            String version = link.getHostVersion();
            String host = pluginContext.getString(link.getConnectedHost() == 1
                    ? R.string.panel_host_debug : R.string.panel_host_release, version == null
                            ? pluginContext.getString(R.string.panel_host_old) : version);
            details.append(pluginContext.getString(R.string.panel_details_connected,
                    host, link.getProtocol(), link.getSentCount())).append('\n');
        }
        details.append(pluginContext.getString(R.string.panel_details_plugin, BuildConfig.VERSION_NAME));
        detailsView.setText(details);

        SharedPreferences prefs = link.getPrefs();
        String select = entry(R.array.pref_select_values, R.array.pref_select_entries,
                prefs.getString(CompanionLink.PREF_SELECT, "pan"));
        String objects = entry(R.array.pref_objects_values, R.array.pref_objects_entries,
                prefs.getString(CompanionLink.PREF_OBJECTS, "all"));
        List<String> passed = new ArrayList<>();
        if (prefs.getBoolean(CompanionLink.PREF_SEND_SELF, true))
            passed.add(pluginContext.getString(R.string.panel_passed_self));
        if (prefs.getBoolean(CompanionLink.PREF_SEND_ICONS, true))
            passed.add(pluginContext.getString(R.string.panel_passed_icons));
        if (prefs.getBoolean(CompanionLink.PREF_SEND_MAPS, true))
            passed.add(pluginContext.getString(R.string.panel_passed_maps));
        if (prefs.getBoolean(CompanionLink.PREF_SEND_ACTIVE_MAP, true))
            passed.add(pluginContext.getString(R.string.panel_passed_active_map));
        String passedText = passed.isEmpty() ? pluginContext.getString(R.string.panel_passed_none)
                : android.text.TextUtils.join(", ", passed);
        settingsView.setText(pluginContext.getString(R.string.panel_settings_text, select, objects, passedText));
        String recent = PluginLog.recent(8);
        eventsView.setText(recent.isEmpty() ? pluginContext.getString(R.string.panel_events_empty) : recent);
    }

    /**
     * Zapisuje dziennik zdarzeń do pliku tekstowego w katalogu narzędzi ATAK i przekazuje go do
     * okna „Udostępnij” systemu.
     */
    private void shareLog() {
        Context atak = getMapView().getContext();
        try {
            File dir = FileSystemUtils.getItem("tools/autotak");
            if (!dir.exists() && !dir.mkdirs())
                throw new java.io.IOException("cannot create folder");
            File file = new File(dir, "AutoTAK-plugin-events.txt");
            try (java.io.FileWriter out = new java.io.FileWriter(file, false)) {
                out.write("AutoTAK-plugin " + BuildConfig.VERSION_NAME + ", app "
                        + (link.getHostVersion() == null ? "2.1.0 or earlier / not connected" : link.getHostVersion())
                        + ", Android " + android.os.Build.VERSION.RELEASE + "\n\n");
                out.write(PluginLog.text());
            }
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, pluginContext.getString(R.string.panel_events_subject))
                    .putExtra(Intent.EXTRA_STREAM, FileProviderHelper.fromFile(atak, file))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            atak.startActivity(Intent.createChooser(send, pluginContext.getString(R.string.panel_events_share)));
        } catch (Exception e) {
            PluginLog.add("Sharing the log failed: " + e.getClass().getSimpleName());
            Toast.makeText(atak, pluginContext.getString(R.string.panel_events_share_failed), Toast.LENGTH_LONG).show();
        }
    }

    /** Nazwa wartości ustawienia tak, jak widać ją na stronie ustawień. */
    private String entry(int valuesId, int entriesId, String value) {
        String[] values = pluginContext.getResources().getStringArray(valuesId);
        String[] entries = pluginContext.getResources().getStringArray(entriesId);
        for (int i = 0; i < values.length && i < entries.length; i++) {
            if (values[i].equals(value))
                return entries[i];
        }
        return value;
    }

    @Override
    public void disposeImpl() {
        open = false;
    }

    @Override
    public void onDropDownSelectionRemoved() {
    }

    @Override
    public void onDropDownVisible(boolean visible) {
        open = visible;
        if (visible) {
            getMapView().removeCallbacks(refresh);
            refresh.run();
        }
    }

    @Override
    public void onDropDownSizeChanged(double width, double height) {
    }

    @Override
    public void onDropDownClose() {
        open = false;
    }
}

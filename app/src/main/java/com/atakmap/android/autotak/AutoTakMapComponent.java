package com.atakmap.android.autotak;

import android.content.Context;
import android.content.Intent;

import com.atakmap.android.autotak.plugin.R;
import com.atakmap.android.dropdown.DropDownMapComponent;
import com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter;
import com.atakmap.android.maps.MapView;
import com.atakmap.app.preferences.ToolsPreferenceFragment;

/** Uruchamia łącze z AutoTAK razem z wtyczką; przycisk wtyczki otwiera panel ze stanem. */
public class AutoTakMapComponent extends DropDownMapComponent {

    public static final String SHOW_STATUS = "com.atakmap.android.autotak.SHOW_STATUS";
    public static final String PREFERENCES_KEY = "autotak_preferences";

    private CompanionLink link;

    @Override
    public void onCreate(final Context context, Intent intent, final MapView view) {
        // Motyw ATAK dla panelu i strony ustawień wtyczki — bez niego tytuły są nieczytelne na ciemnym tle.
        context.setTheme(R.style.ATAKPluginTheme);
        super.onCreate(context, intent, view);
        link = new CompanionLink(view);
        link.start();
        // Wtyczka ze sklepu Google Play: przy starcie sprawdza, czy jest nowsza wersja (da się wyłączyć).
        if (UpdateCheck.fromStore(context) && link.getPrefs().getBoolean(UpdateCheck.PREF_AUTO, true)) {
            UpdateCheck.check(context, (newer, failed) -> {
                if (newer != null)
                    android.widget.Toast.makeText(view.getContext(),
                            context.getString(R.string.update_available_toast, newer),
                            android.widget.Toast.LENGTH_LONG).show();
            });
        }
        DocumentedIntentFilter filter = new DocumentedIntentFilter();
        filter.addAction(SHOW_STATUS);
        registerDropDownReceiver(new StatusDropDownReceiver(view, context, link), filter);
        ToolsPreferenceFragment.register(
                new ToolsPreferenceFragment.ToolPreference(
                        context.getString(R.string.app_name),
                        context.getString(R.string.pref_tool_summary),
                        PREFERENCES_KEY,
                        context.getResources().getDrawable(R.drawable.ic_launcher),
                        new AutoTakPreferenceFragment(context)));
    }

    @Override
    protected void onDestroyImpl(Context context, MapView view) {
        ToolsPreferenceFragment.unregister(PREFERENCES_KEY);
        super.onDestroyImpl(context, view);
        if (link != null)
            link.stop();
    }
}

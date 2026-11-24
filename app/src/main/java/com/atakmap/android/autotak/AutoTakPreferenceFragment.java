package com.atakmap.android.autotak;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.preference.Preference;
import android.widget.Toast;

import com.atakmap.android.autotak.plugin.R;
import com.atakmap.android.preference.PluginPreferenceFragment;

/** Ustawienia wtyczki w ATAK (Tool Preferences): każde ma krótki opis pod nazwą. */
public class AutoTakPreferenceFragment extends PluginPreferenceFragment {

    private static Context pluginContext;

    public AutoTakPreferenceFragment() {
        super(pluginContext, R.xml.preferences);
    }

    @SuppressLint("ValidFragment")
    public AutoTakPreferenceFragment(Context context) {
        super(context, R.xml.preferences);
        pluginContext = context;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Sprawdzanie nowej wersji działa tylko dla wtyczki zainstalowanej ze sklepu Google Play.
        final boolean store = UpdateCheck.fromStore(pluginContext);
        Preference check = findPreference("autotak.check_update");
        Preference auto = findPreference(UpdateCheck.PREF_AUTO);
        if (auto != null) {
            auto.setEnabled(store);
            if (!store)
                auto.setSummary(pluginContext.getString(R.string.pref_update_store_only));
        }
        if (check == null)
            return;
        check.setEnabled(store);
        if (!store) {
            check.setSummary(pluginContext.getString(R.string.pref_update_store_only));
            return;
        }
        check.setOnPreferenceClickListener(preference -> {
            final Context atak = getActivity();
            Toast.makeText(atak, pluginContext.getString(R.string.update_checking), Toast.LENGTH_SHORT).show();
            UpdateCheck.check(pluginContext, (newer, failed) -> {
                if (getActivity() == null)
                    return;
                if (failed) {
                    Toast.makeText(atak, pluginContext.getString(R.string.update_failed), Toast.LENGTH_LONG).show();
                } else if (newer == null) {
                    Toast.makeText(atak, pluginContext.getString(R.string.update_current), Toast.LENGTH_LONG).show();
                } else {
                    new AlertDialog.Builder(atak)
                            .setTitle(pluginContext.getString(R.string.update_available_title))
                            .setMessage(pluginContext.getString(R.string.update_available_message, newer))
                            .setPositiveButton(pluginContext.getString(R.string.update_open_store),
                                    (dialog, which) -> UpdateCheck.openStore(atak, pluginContext))
                            .setNegativeButton(pluginContext.getString(R.string.update_later), null)
                            .show();
                }
            });
            return true;
        });
    }

    @Override
    public String getSubTitle() {
        return getSubTitle("Tool Preferences", pluginContext.getString(R.string.app_name));
    }
}

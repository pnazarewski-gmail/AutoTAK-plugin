package com.atakmap.android.autotak;

import android.annotation.SuppressLint;
import android.content.Context;

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
    public String getSubTitle() {
        return getSubTitle("Tool Preferences", pluginContext.getString(R.string.app_name));
    }
}

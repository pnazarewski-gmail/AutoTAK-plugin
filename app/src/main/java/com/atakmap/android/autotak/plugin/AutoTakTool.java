package com.atakmap.android.autotak.plugin;

import android.content.Context;

import com.atak.plugins.impl.AbstractPluginTool;
import com.atakmap.android.autotak.AutoTakMapComponent;

import gov.tak.api.util.Disposable;

/** Przycisk wtyczki w ATAK: pokazuje stan łącza z AutoTAK. */
public class AutoTakTool extends AbstractPluginTool implements Disposable {

    public AutoTakTool(Context context) {
        super(context,
                context.getString(R.string.app_name),
                context.getString(R.string.app_name),
                context.getResources().getDrawable(R.drawable.ic_tool),
                AutoTakMapComponent.SHOW_STATUS);
    }

    @Override
    public void dispose() {
    }
}

package com.atakmap.android.autotak.plugin;

import com.atak.plugins.impl.AbstractPlugin;
import com.atak.plugins.impl.PluginContextProvider;
import com.atakmap.android.autotak.AutoTakMapComponent;

import gov.tak.api.plugin.IServiceController;

/** Punkt wejścia wtyczki, wskazany w assets/plugin.xml. */
public class AutoTakLifecycle extends AbstractPlugin {

    public AutoTakLifecycle(IServiceController serviceController) {
        super(serviceController,
                new AutoTakTool(serviceController
                        .getService(PluginContextProvider.class)
                        .getPluginContext()),
                new AutoTakMapComponent());
    }
}

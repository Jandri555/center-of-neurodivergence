package com.neurocenter.api.plugin;
public interface NeuroPlugin {
 default void onLoad(PluginContext context) throws Exception {}
 default void onEnable() throws Exception {}
 default void onDisable() throws Exception {}
 default void onUnload() throws Exception {}
}

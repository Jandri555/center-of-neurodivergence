package com.neurocenter.api.plugin;
import com.neurocenter.api.NeuroApp;
import com.neurocenter.api.NeuroPlatform;
import java.nio.file.Path;
public interface PluginContext {
 String pluginId(); Path dataDirectory(); NeuroPlatform platform(); ServiceRegistry services(); EventBus events(); ExtensionRegistry extensions(); void registerApplication(NeuroApp app);
}

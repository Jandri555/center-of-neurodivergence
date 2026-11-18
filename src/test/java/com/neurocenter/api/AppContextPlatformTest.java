package com.neurocenter.api;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import com.neurocenter.api.plugin.ServiceRegistry;
import com.neurocenter.api.plugin.EventBus;
import com.neurocenter.api.plugin.ExtensionRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppContextPlatformTest {

    @Test
    void desktopRuntimeDetectsDesktop() {
        assertEquals(NeuroPlatform.DESKTOP, NeuroPlatform.current());
    }

    @Test
    void desktopContextIdentifiesItselfAsDesktop() {
        AppContext context = new AppContext() {
            @Override public Path dataDir() { return Path.of("."); }
            @Override public String getSetting(String key, String defaultValue) { return defaultValue; }
            @Override public void putSetting(String key, String value) {}
            @Override public ServiceRegistry services() { return new ServiceRegistry(); }
            @Override public EventBus events() { return new EventBus(); }
            @Override public ExtensionRegistry extensions() { return new ExtensionRegistry(); }
            @Override public void setStatus(String message) {}
            @Override public void openApp(String appId) {}
            @Override public java.awt.Window window() { return null; }
            @Override public void requestAttention() {}
        };

        assertEquals(NeuroPlatform.DESKTOP, context.platform());
    }
}

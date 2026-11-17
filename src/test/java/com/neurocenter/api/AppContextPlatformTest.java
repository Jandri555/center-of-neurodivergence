package com.neurocenter.api;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

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
            @Override public void setStatus(String message) {}
            @Override public void openApp(String appId) {}
            @Override public java.awt.Window window() { return null; }
            @Override public void requestAttention() {}
        };

        assertEquals(NeuroPlatform.DESKTOP, context.platform());
    }
}

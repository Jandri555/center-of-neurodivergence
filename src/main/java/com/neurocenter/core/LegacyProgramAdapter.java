package com.neurocenter.core;

import com.neurocenter.api.AppContext;
import com.neurocenter.api.NeuroApp;
import com.neurocenter.api.NeuroProgram;
import com.neurocenter.api.ProgramContext;

import javax.swing.Icon;
import javax.swing.JComponent;
import java.awt.Window;
import java.nio.file.Path;

/** Puente de compatibilidad entre la API NeuroProgram y la API NeuroApp. */
@SuppressWarnings("deprecation")
final class LegacyProgramAdapter implements NeuroApp {

    private final NeuroProgram delegate;
    private ProgramContext legacyContext;

    LegacyProgramAdapter(NeuroProgram delegate) {
        this.delegate = delegate;
    }

    @Override public String id() { return delegate.id(); }

    @Override public String name() { return delegate.name(); }

    @Override public String description() { return delegate.description(); }

    @Override public String version() { return delegate.version(); }

    @Override public Icon icon() { return delegate.icon(); }

    @Override
    public void start(AppContext context) {
        legacyContext = new ContextBridge(context);
        delegate.start(legacyContext);
    }

    @Override
    public JComponent createPanel(AppContext context) {
        if (legacyContext == null) legacyContext = new ContextBridge(context);
        return delegate.createPanel(legacyContext);
    }

    @Override
    public void stop() {
        delegate.stop();
    }

    private record ContextBridge(AppContext delegate) implements ProgramContext {
        @Override public Path dataDir() { return delegate.dataDir(); }

        @Override public String getSetting(String key, String defaultValue) {
            return delegate.getSetting(key, defaultValue);
        }

        @Override public void putSetting(String key, String value) {
            delegate.putSetting(key, value);
        }

        @Override public void setStatus(String message) {
            delegate.setStatus(message);
        }

        @Override public void openProgram(String programId) {
            delegate.openApp(programId);
        }

        @Override public Window window() { return delegate.window(); }

        @Override public void requestAttention() { delegate.requestAttention(); }
    }
}

package com.neurocenter.devkit;

import com.neurocenter.api.AppContext;
import com.neurocenter.api.plugin.EventBus;
import com.neurocenter.api.plugin.ExtensionRegistry;
import com.neurocenter.api.plugin.ServiceRegistry;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Headless smoke tests of the desktop side of the same cross-platform .ncapp. */
public final class DevTestKitDesktopSmoke {
    private DevTestKitDesktopSmoke() {}

    public static void main(String[] args) throws Exception {
        FakeContext context = new FakeContext();
        DevTestKitDesktop app = new DevTestKitDesktop();
        if (!"com.neurocenter.devtestkit".equals(app.id()))
            throw new AssertionError("Desktop ID must match mobile manifest");
        if (!"1.5.0".equals(app.version()))
            throw new AssertionError("Incorrect desktop version");

        final JComponent[] view = new JComponent[1];
        SwingUtilities.invokeAndWait(() -> {
            app.start(context);
            view[0] = app.createPanel(context);
        });
        if (!context.requested.isEmpty())
            throw new AssertionError("App asked for permissions on startup");
        JButton permission = find(view[0], JButton.class, "Solicitar network.connect");
        JCheckBox dirty = find(view[0], JCheckBox.class, "UNSAVED_CHANGES");
        if (permission == null || dirty == null)
            throw new AssertionError("Expected permission and unsaved-changes controls");

        SwingUtilities.invokeAndWait(permission::doClick);
        if (!context.requested.equals(List.of("network.connect")))
            throw new AssertionError("Only user-invoked, declared permissions may be requested");

        SwingUtilities.invokeAndWait(dirty::doClick);
        if (!DevTestKitDesktop.UNSAVED_CHANGES)
            throw new AssertionError("UNSAVED_CHANGES must reflect the test toggle");
        SwingUtilities.invokeAndWait(dirty::doClick);
        if (DevTestKitDesktop.UNSAVED_CHANGES)
            throw new AssertionError("The warning flag was not cleared");

        JButton bridge = find(view[0], JButton.class, "Comprobar Agenda Bridge");
        if (bridge == null) throw new AssertionError("Missing bridge availability control");
        SwingUtilities.invokeAndWait(bridge::doClick);
        if (!context.requested.equals(List.of("network.connect")))
            throw new AssertionError("Looking up the plugin must not request permission");
        SwingUtilities.invokeAndWait(app::stop);
        System.out.println("PASS: desktop service, manifest-only permissions, and UNSAVED_CHANGES");
    }

    private static <T extends Component> T find(Component root, Class<T> type, String text) {
        if (type.isInstance(root)) {
            String label = root instanceof JButton b ? b.getText()
                    : root instanceof JCheckBox c ? c.getText() : "";
            if (label.contains(text)) return type.cast(root);
        }
        if (root instanceof Container parent)
            for (Component child : parent.getComponents()) {
                T found = find(child, type, text);
                if (found != null) return found;
            }
        return null;
    }

    private static final class FakeContext implements AppContext {
        private final List<String> requested = new ArrayList<>();
        private final ServiceRegistry registry = new ServiceRegistry();
        @Override public Path dataDir() { return Path.of("."); }
        @Override public String getSetting(String key, String fallback) { return fallback; }
        @Override public void putSetting(String key, String value) {}
        @Override public ServiceRegistry services() { return registry; }
        @Override public EventBus events() { return null; }
        @Override public ExtensionRegistry extensions() { return null; }
        @Override public void setStatus(String message) {}
        @Override public void openApp(String id) {}
        @Override public Window window() { return null; }
        @Override public void requestAttention() {}
        @Override public boolean requestPermission(String permission) {
            requested.add(permission);
            return false;
        }
    }
}

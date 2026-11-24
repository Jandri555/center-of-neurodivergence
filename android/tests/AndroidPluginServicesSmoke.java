import com.neurocenter.core.AndroidPluginServices;

public final class AndroidPluginServicesSmoke {
    public static void main(String[] args) {
        if (AndroidPluginServices.isAgendaBridgeAvailable())
            throw new AssertionError("Agenda provider must not exist by default");
        try {
            AndroidPluginServices.requireAgendaBridge();
            throw new AssertionError("Agenda access should be denied without plugin");
        } catch (SecurityException expected) { }
        AndroidPluginServices.AgendaBridge provider = new AndroidPluginServices.AgendaBridge() {
            public String getAgendaJson() { return "[]"; }
            public String addTask(String title, String date, String time, String notes) { return "ok"; }
            public boolean setEntryCompleted(String id, boolean done) { return true; }
            public boolean deleteAgendaEntry(String id) { return true; }
        };
        AndroidPluginServices.registerAgendaBridge(provider);
        if (!AndroidPluginServices.isAgendaBridgeAvailable()
                || !"[]".equals(AndroidPluginServices.requireAgendaBridge().getAgendaJson()))
            throw new AssertionError("Provider registration failed");
        try {
            AndroidPluginServices.registerAgendaBridge(provider);
            throw new AssertionError("Provider was replaced");
        } catch (IllegalStateException expected) { }
        AndroidPluginServices.unregisterAgendaBridge(provider);
        if (AndroidPluginServices.isAgendaBridgeAvailable())
            throw new AssertionError("Provider did not unregister");
        System.out.println("PASS: plugin-provided Agenda defaults to unavailable");
    }
}

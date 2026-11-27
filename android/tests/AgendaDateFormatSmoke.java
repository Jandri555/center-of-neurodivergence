import com.neurocenter.apps.agenda.AgendaDateFormat;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

public final class AgendaDateFormatSmoke {
    public static void main(String[] args) {
        LocalDate picked = AgendaDateFormat.fromPicker(2027, 1, 16);
        check(picked.equals(LocalDate.of(2027, 2, 16)), "DatePicker month index");
        check(AgendaDateFormat.display(picked).equals("16/02/2027"), "Spanish display");
        check(AgendaDateFormat.store(picked).equals("16/02/2027"), "Spanish storage");
        check(AgendaDateFormat.parse("16/02/2027").equals(picked), "Spanish parsing");
        check(AgendaDateFormat.displayStored("16/02/2027").equals("16/02/2027"), "Saved date display");
        check(AgendaDateFormat.displayStored(null).isEmpty(), "Null date");
        check(AgendaDateFormat.store(AgendaDateFormat.fromPicker(2028, 1, 29)).equals("29/02/2028"), "Leap day");
        for (String invalid : new String[]{"2027-02-16", "31/02/2027", "16/2/2027", "29/02/2027"}) {
            boolean rejected = false;
            try { AgendaDateFormat.parse(invalid); }
            catch (DateTimeParseException expected) { rejected = true; }
            check(rejected, "Reject legacy or invalid format: " + invalid);
        }
        System.out.println("OK: dd/MM/aaaa saved, displayed and parsed consistently");
    }
    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
    }
}

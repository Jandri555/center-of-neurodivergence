import com.neurocenter.apps.agenda.AgendaDateFormat;
import java.time.LocalDate;

public final class AgendaDateFormatSmoke {
    public static void main(String[] args) {
        LocalDate picked = AgendaDateFormat.fromPicker(2027, 1, 16);
        check(picked.equals(LocalDate.of(2027, 2, 16)), "DatePicker month index");
        check(AgendaDateFormat.display(picked).equals("16/02/2027"), "Spanish presentation");
        check(AgendaDateFormat.store(picked).equals("2027-02-16"), "ISO persistence");
        check(AgendaDateFormat.displayStored("2027-02-16").equals("16/02/2027"), "Stored date");
        check(AgendaDateFormat.displayStored(null).isEmpty(), "Empty stored date");
        check(AgendaDateFormat.displayStored("bad").equals("bad"), "Corrupt value must not disappear");
        LocalDate leap = AgendaDateFormat.fromPicker(2028, 1, 29);
        check(AgendaDateFormat.store(leap).equals("2028-02-29"), "Leap day");
        System.out.println("OK: Spanish dates + DatePicker + ISO sync contract");
    }

    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
}

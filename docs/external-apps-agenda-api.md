# External apps: Agenda API

Desktop extensions can create and refresh tasks in the shared NeuroCenter agenda through `AppContext.agenda()`.

## Example

```java
import com.neurocenter.api.agenda.AgendaImportResult;
import com.neurocenter.api.agenda.ExternalTask;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

AgendaImportResult result = context.agenda().upsertExternalTasks(
    "moodle:centros.edu.xunta.gal/iesteis/aulavirtual",
    List.of(new ExternalTask(
        "assignment-123",
        "Entrega de Programación",
        "Descripción en texto plano",
        LocalDate.of(2026, 12, 4),
        LocalTime.of(23, 59),
        URI.create("https://centros.edu.xunta.gal/iesteis/aulavirtual/mod/assign/view.php?id=123")
    ))
);
```

Call the service from the app's worker thread (for example, from `NeuroApp.start` or a `SwingWorker`), not from Swing's event-dispatch thread.

## Identity and update rules

- `sourceId` identifies the source installation/account, not a password or token. Keep it stable between syncs.
- `externalId` identifies one task within that source.
- The pair `(sourceId, externalId)` is unique. Reimporting the same task updates its existing agenda entry.
- The user's local completed state is preserved when source data changes.
- A task missing from an import batch is not deleted. This protects data when Moodle returns an incomplete result.
- Existing manually created agenda items are never matched or overwritten by external imports.
- The source URL is validated as HTTP(S), stored with the entry and exposed as an “Abrir origen” action in the Agenda UI.
- Imported tasks are included in the existing agenda persistence and device-sync journal.

`AgendaImportResult` reports created, updated and unchanged counts. The service is backed by the same in-memory store as the built-in Agenda panel, so successful imports are persisted immediately and refresh an open Agenda view.

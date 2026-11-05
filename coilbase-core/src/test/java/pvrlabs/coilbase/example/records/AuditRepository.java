package pvrlabs.coilbase.example.records;

import pvrlabs.coilbase.Repository;
import pvrlabs.coilbase.RepositoryContext;

public final class AuditRepository extends Repository {
    public AuditRepository(RepositoryContext db) {
        super(db);
    }

    public void insert(long eventId, long recordId, String status) {
        db.update("INSERT INTO audit_events(id, record_id, status) VALUES (:id, :recordId, :status)",
                update -> update.bind("id", eventId).bind("recordId", recordId).bind("status", status).execute());
    }
}

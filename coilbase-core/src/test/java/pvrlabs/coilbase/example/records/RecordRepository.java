package pvrlabs.coilbase.example.records;

import pvrlabs.coilbase.Repository;
import pvrlabs.coilbase.RepositoryContext;

public final class RecordRepository extends Repository {
    public RecordRepository(RepositoryContext db) {
        super(db);
    }

    public void updateStatus(long recordId, String status) {
        int changed = db.update("UPDATE records SET status = :status WHERE id = :id",
                update -> update.bind("status", status).bind("id", recordId).execute());
        if (changed != 1) {
            throw new IllegalStateException("Record not found: " + recordId);
        }
    }
}

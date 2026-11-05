package pvrlabs.coilbase.example.records;

import pvrlabs.coilbase.RepositoryContext;

/** Example service coordinating a status update and an audit insert in one transaction. */
public final class RecordService {
    private final RepositoryContext db;
    private final RecordRepository records;
    private final AuditRepository audit;

    public RecordService(RepositoryContext db, RecordRepository records, AuditRepository audit) {
        this.db = db;
        this.records = records;
        this.audit = audit;
    }

    public void changeStatus(long recordId, String status, long eventId) {
        db.transactional(() -> {
            records.updateStatus(recordId, status);
            audit.insert(eventId, recordId, status);
        });
    }
}

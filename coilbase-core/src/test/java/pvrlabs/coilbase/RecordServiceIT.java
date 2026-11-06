package pvrlabs.coilbase;

import org.h2.jdbcx.JdbcDataSource;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import pvrlabs.coilbase.example.records.AuditRepository;
import pvrlabs.coilbase.example.records.RecordRepository;
import pvrlabs.coilbase.example.records.RecordService;

import static org.junit.jupiter.api.Assertions.*;

class RecordServiceIT {
    RepositoryContext db;
    RecordService service;

    @BeforeEach
    void setup(TestInfo test) {
        // Unique name isolates every test; keep the database across managed handle closures.
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:RecordServiceIT_" + test.getTestMethod().orElseThrow().getName()
                + "_" + System.currentTimeMillis() + ";DB_CLOSE_DELAY=-1");
        db = RepositoryContext.create(source, Dialects.H2);
        // Test-owned DDL and fixture data, not production initialization.
        db.useHandle(handle -> {
            handle.execute("CREATE TABLE records(id BIGINT PRIMARY KEY, status VARCHAR(32) NOT NULL)");
            handle.execute("""
                    CREATE TABLE audit_events(
                        id BIGINT PRIMARY KEY,
                        record_id BIGINT NOT NULL REFERENCES records(id),
                        status VARCHAR(32) NOT NULL
                    )
                    """);
            handle.execute("INSERT INTO records(id, status) VALUES (1, 'PENDING')");
        });
        service = new RecordService(db, new RecordRepository(db), new AuditRepository(db));
    }

    @Test
    void statusUpdateAndAuditInsertCommitTogether() {
        service.changeStatus(1, "ACTIVE", 10);

        assertEquals("ACTIVE", status());
        assertEquals(1, auditCount());
        db.query("SELECT record_id, status FROM audit_events WHERE id = 10", query -> query.map((row, ctx) -> {
            assertEquals(1L, row.getLong("record_id"));
            assertEquals("ACTIVE", row.getString("status"));
            return row.getLong("record_id");
        }).one());
    }

    @Test
    void failedAuditInsertRollsBackStatusUpdate() {
        db.update("INSERT INTO audit_events(id, record_id, status) VALUES (10, 1, 'PENDING')",
                update -> update.execute());

        assertThrows(UnableToExecuteStatementException.class, () -> service.changeStatus(1, "ACTIVE", 10));

        assertEquals("PENDING", status());
        assertEquals(1, auditCount());
        assertEquals("PENDING", db.query("SELECT status FROM audit_events WHERE id = 10",
                query -> query.mapTo(String.class).one()));
    }

    @Test
    void failedUpdatePreventsAuditInsert() {
        assertThrows(IllegalStateException.class, () -> service.changeStatus(999, "ACTIVE", 10));

        assertEquals("PENDING", status());
        assertEquals(0, auditCount());
    }

    @Test
    void serviceJoinsOuterTransactionWithoutIndependentCommit() {
        assertThrows(IllegalArgumentException.class, () -> db.transactional(() -> {
            service.changeStatus(1, "ACTIVE", 10);
            assertEquals("ACTIVE", status());
            assertEquals(1, auditCount());
            throw new IllegalArgumentException("Later operation failed");
        }));

        assertEquals("PENDING", status());
        assertEquals(0, auditCount());
    }

    private String status() {
        return db.query("SELECT status FROM records WHERE id = 1", query -> query.mapTo(String.class).one());
    }

    private int auditCount() {
        return db.query("SELECT COUNT(*) FROM audit_events", query -> query.mapTo(Integer.class).one());
    }
}

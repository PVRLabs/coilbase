package pvrlabs.coilbase;

import java.util.Objects;

/** Optional base class for repositories that share an application context. */
public abstract class Repository {
    protected final RepositoryContext db;

    protected Repository(RepositoryContext db) {
        this.db = Objects.requireNonNull(db, "db");
    }
}

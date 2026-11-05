package pvrlabs.coilbase;

/** The transaction could not commit because a participant explicitly marked it rollback-only. */
public final class RollbackOnlyException extends RuntimeException {
    public RollbackOnlyException() {
        super("Transaction was marked rollback-only");
    }
}

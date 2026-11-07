package pvrlabs.coilbase.example.hello;

import com.zaxxer.hikari.HikariDataSource;

/**
 * An example of organizing application configuration with ordinary Java, not a CoilBase
 * requirement or enforced API. Applications may model configuration however they prefer
 * and add factories such as {@code production()} or {@code test()} when useful.
 * Future CoilBase versions may optionally offer overridable configuration properties via
 * system properties or environment variables for convenience, depending on interest.
 */
public record AppConfig(String jdbcUrl, String username, String password, int poolSize) {
    public static AppConfig development() {
        // TODO: Support property overrides when the example needs external configuration.
        return new AppConfig("jdbc:h2:mem:hello;DB_CLOSE_DELAY=-1", "sa", "", 4);
    }

    public HikariDataSource createDataSource() {
        var pool = new HikariDataSource();
        pool.setJdbcUrl(jdbcUrl);
        pool.setUsername(username);
        pool.setPassword(password);
        pool.setMaximumPoolSize(poolSize);
        pool.setMinimumIdle(1);
        return pool;
    }
}

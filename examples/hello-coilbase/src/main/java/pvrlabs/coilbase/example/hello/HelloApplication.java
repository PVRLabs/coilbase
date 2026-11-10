package pvrlabs.coilbase.example.hello;

import io.avaje.jex.Jex;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import pvrlabs.coilbase.jex.CoilBaseJexApplication;

/** Supplies H2, initializes its schema and explicitly wires the controller and its dependencies. */
public class HelloApplication extends CoilBaseJexApplication {
    @Override protected DataSource createPrimaryDataSource() {
        return createHikariPool("jdbc:h2:mem:hello;DB_CLOSE_DELAY=-1", "sa", "", 1, 4);
    }

    @Override protected void registerRoutes(Jex jex) {
        db().executeScriptResource("/schema.sql");
        var repository = new GreetingRepository(db());
        var service = new GreetingService(db(), repository);
        var controller = new GreetingController(service);
        jex.routing(new GreetingController$Route(controller));
    }

    @Override protected void configureErrorHandling() {
        mapError(GreetingService.InvalidNameException.class, 400);
    }

    public static void main(String[] args) {
        new HelloApplication().enableStaticResources().start();
    }

    private static HikariDataSource createHikariPool(
            String jdbcUrl, String username, String password, int minIdle, int maxPoolSize) {
        var pool = new HikariDataSource();
        pool.setJdbcUrl(jdbcUrl);
        pool.setUsername(username);
        pool.setPassword(password);
        pool.setMinimumIdle(minIdle);
        pool.setMaximumPoolSize(maxPoolSize);
        return pool;
    }
}

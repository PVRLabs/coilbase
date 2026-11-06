package pvrlabs.coilbase.example.hello;

import io.avaje.jex.Jex;
import javax.sql.DataSource;
import com.zaxxer.hikari.HikariDataSource;
import pvrlabs.coilbase.jex.CoilBaseJexApplication;

/** Supplies H2, initializes its schema and explicitly wires the controller and its dependencies. */
public class HelloApplication extends CoilBaseJexApplication {
    @Override protected DataSource createPrimaryDataSource() {
        var pool = new HikariDataSource();
        pool.setJdbcUrl("jdbc:h2:mem:hello;DB_CLOSE_DELAY=-1");
        pool.setUsername("sa");
        pool.setPassword("");
        pool.setMaximumPoolSize(4);
        pool.setMinimumIdle(1);
        return pool;
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
        new HelloApplication().start();
    }
}

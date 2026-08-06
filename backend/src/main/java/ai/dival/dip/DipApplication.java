package ai.dival.dip;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduling is enabled for background work such as contract expiry alerts. Note that scheduled
 * jobs run without a request and therefore without a tenant, so each one must bind a tenant
 * explicitly before touching tenant-owned data.
 */
@SpringBootApplication
@EnableScheduling
public class DipApplication {

    public static void main(String[] args) {
        SpringApplication.run(DipApplication.class, args);
    }
}

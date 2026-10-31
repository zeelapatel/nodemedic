package io.nodemedic;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

@SpringBootApplication
public class NodeMedicApplication {

    public static void main(String[] args) {
        SpringApplication.run(NodeMedicApplication.class, args);
    }

    /** The reconciler's source of time; tests can replace it with a fixed clock. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}

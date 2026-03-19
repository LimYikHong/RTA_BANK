package rta;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RtaBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(RtaBackendApplication.class, args);
    }
}

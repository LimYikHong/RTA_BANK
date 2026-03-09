package rta.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig {

    /**
     * WebMvcConfigurer bean - Configures CORS for MVC endpoints (controller
     * layer). Files are now served from MinIO object storage via
     * FileController.
     */
    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {

            /**
             * CORS mapping for all APIs. - Allows Angular dev origin. - Permits
             * common HTTP methods and credentials (cookies/Authorization
             * header). - Consider restricting allowed headers/origins for
             * production.
             */
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**")
                        .allowedOrigins("http://localhost:4200", "https://localhost:4200", "http://localhost:8086", "https://localhost:8086")
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("*")
                        .allowCredentials(true);
            }

            // Note: Static resource handler for local files removed.
            // Files are now stored in MinIO and served via FileController
            // or directly from MinIO endpoint (http://localhost:9000/rta-bank/...)
        };
    }
}

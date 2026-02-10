package rta.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig {

    /**
     * WebMvcConfigurer bean - Configures CORS for MVC endpoints (controller
     * layer). - Also exposes a static resource handler for serving uploaded
     * files under /uploads/**.
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

            /**
             * Static resource handler: - Maps URL path /uploads/** to the local
             * folder "uploads/" on the server. - Example: GET
             * /uploads/avatar.png -> file:uploads/avatar.png - Make sure the
             * process has read permissions; for prod, consider a CDN or object
             * storage.
             */
            @Override
            public void addResourceHandlers(ResourceHandlerRegistry registry) {
                registry.addResourceHandler("/uploads/**")
                        .addResourceLocations("file:uploads/");
            }
        };
    }
}

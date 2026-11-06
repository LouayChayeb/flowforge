package io.github.louaychayeb.flowforge.orchestrator.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * Caching for the dashboard the orchestrator serves (built from {@code src/main/frontend} into {@code static/}).
 *
 * <p>Vite names every bundle after a hash of its content, so a file under {@code /assets/} never changes: browsers may
 * keep it for a year. {@code index.html} is the one file that points at the current bundles, so it is revalidated on
 * every load — otherwise a browser keeps running the previous dashboard after a deploy.
 */
@Configuration
public class DashboardResources implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(@NonNull ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
        registry.addResourceHandler("/index.html", "/favicon.svg")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache());
    }
}

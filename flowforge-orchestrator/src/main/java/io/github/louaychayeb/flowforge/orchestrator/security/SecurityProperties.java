package io.github.louaychayeb.flowforge.orchestrator.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Who may act on runs ({@code flowforge.security.users}). A password may name its encoding as Spring Security reads
 * it — {@code {bcrypt}$2a$10$…}, as a real deployment should — and one without a prefix is plain text, fit only for a
 * local demo (a warning is logged).
 */
@Validated
@ConfigurationProperties("flowforge.security")
public record SecurityProperties(@NotEmpty List<@Valid User> users) {

    public record User(@NotBlank String name, @NotBlank String password, @NotEmpty List<String> roles) {
    }
}

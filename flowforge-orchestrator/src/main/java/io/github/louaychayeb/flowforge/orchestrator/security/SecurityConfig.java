package io.github.louaychayeb.flowforge.orchestrator.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.louaychayeb.flowforge.orchestrator.error.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import java.io.IOException;
import java.net.URI;

/**
 * Who may change a run.
 *
 * <p>Reading is open — the dashboard, the run history, metrics and health — and so is starting a run. Acting on a run
 * someone else started is not: cancelling it, retrying its compensation or delivering a signal needs the
 * {@code OPERATOR} role, and the audit trail records the authenticated user instead of a name the caller typed.
 *
 * <p>The API is stateless: every request carries HTTP Basic credentials, so there is no session and no cookie, and
 * therefore no CSRF to defend against. Refusals are RFC 9457 problems like every other API error. They carry no
 * {@code WWW-Authenticate} challenge, so a browser asks through the dashboard's own dialog, never its native popup.
 */
@Configuration
public class SecurityConfig {

    public static final String OPERATOR = "OPERATOR";

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/runs/*/cancel", "/api/runs/*/retry",
                                "/api/runs/*/signals/*").hasRole(OPERATOR)
                        .requestMatchers(HttpMethod.GET, "/api/auth/me").authenticated()
                        .anyRequest().permitAll())
                .httpBasic(basic -> basic.authenticationEntryPoint((request, response, e) ->
                        problem(response, objectMapper, ErrorCode.AUTHENTICATION_REQUIRED,
                                "Sign in with an operator account to do this")))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, e) ->
                                problem(response, objectMapper, ErrorCode.AUTHENTICATION_REQUIRED,
                                        "Sign in with an operator account to do this"))
                        .accessDeniedHandler((request, response, e) ->
                                problem(response, objectMapper, ErrorCode.ACCESS_DENIED,
                                        "Your account does not have the " + OPERATOR + " role")))
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService users(SecurityProperties properties) {
        InMemoryUserDetailsManager users = new InMemoryUserDetailsManager();
        for (SecurityProperties.User user : properties.users()) {
            String password = user.password();
            // An encoded password names its encoding: {bcrypt}…; anything else is plain text.
            if (!password.startsWith("{")) {
                password = "{noop}" + password;
            }
            if (password.startsWith("{noop}")) {
                log.warn("User {} has a plain-text password: fine for a local demo, never for a real deployment"
                        + " (set FLOWFORGE_OPERATOR_PASSWORD to a bcrypt hash)", user.name());
            }
            users.createUser(User.withUsername(user.name()).password(password)
                    .roles(user.roles().toArray(String[]::new)).build());
        }
        return users;
    }

    private static void problem(HttpServletResponse response, ObjectMapper objectMapper, ErrorCode code,
                                String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setType(URI.create(code.typeUri()));
        problem.setTitle(code.title());
        problem.setProperty("code", code.name());
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}

package io.github.louaychayeb.flowforge.orchestrator.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Lets a client check its credentials before it acts: the dashboard's sign-in calls this. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record Me(String name, List<String> roles) {
    }

    /** The signed-in user and their roles; {@code 401} without valid credentials. */
    @GetMapping("/me")
    public Me me(Authentication authentication) {
        return new Me(authentication.getName(), authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(role -> role.startsWith("ROLE_") ? role.substring("ROLE_".length()) : role)
                .sorted().toList());
    }
}

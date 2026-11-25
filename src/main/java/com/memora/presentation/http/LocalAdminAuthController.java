package com.memora.presentation.http;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
@Profile("local")
public final class LocalAdminAuthController {
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;

    public LocalAdminAuthController(AuthenticationManager authenticationManager,
                                    SecurityContextRepository securityContextRepository) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken csrfToken) {
        return Map.of("token", csrfToken.getToken());
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest login,
                                   HttpServletRequest request,
                                   HttpServletResponse response) {
        Authentication authenticated;
        try {
            authenticated = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(login.email(), login.password()));
        } catch (AuthenticationException rejected) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(problem(HttpStatus.UNAUTHORIZED, "invalid_credentials", "E-mail ou senha inválidos."));
        }

        request.getSession(true);
        request.changeSessionId();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authenticated);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        return ResponseEntity.ok(new SessionResponse(authenticated.getName()));
    }

    @GetMapping("/session")
    public SessionResponse session(Authentication authentication) {
        return new SessionResponse(authentication.getName());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        Cookie expiredSession = new Cookie("JSESSIONID", "");
        expiredSession.setHttpOnly(true);
        expiredSession.setPath("/");
        expiredSession.setMaxAge(0);
        expiredSession.setSecure(request.isSecure());
        response.addCookie(expiredSession);
        return ResponseEntity.noContent().build();
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("about:blank"));
        problem.setProperty("code", code);
        problem.setProperty("traceId", UUID.randomUUID().toString());
        return problem;
    }

    public record LoginRequest(@NotBlank @Email @Size(max = 254) String email,
                               @NotBlank @Size(max = 256) String password) {
    }

    public record SessionResponse(String email) {
    }
}

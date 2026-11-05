package com.superagent.web.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import java.io.IOException;
import java.util.Map;

/**
 * Session-cookie security for the whole platform. Everything is authenticated
 * except the login page, static assets, health/meta, the auth endpoints, the SSO
 * callbacks, and {@code /mcp} (which is gated by its own token). Connectors and
 * user management are admin-only.
 *
 * <p>Login is form-based but answers with JSON so the SPA can drive it; an
 * unauthenticated API call gets 401 while a page load is redirected to /login.
 * CSRF is disabled (token-less JSON API); the session cookie is {@code SameSite=Lax}
 * (set in application.properties) as the baseline mitigation.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final ObjectMapper mapper = new ObjectMapper();

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/login.html", "/login.css", "/login.js", "/app.css", "/js/**",
                                "/i18n.js", "/superagent-logo.png", "/favicon.ico").permitAll()
                        .requestMatchers("/api/v1/meta", "/actuator/health").permitAll()
                        .requestMatchers("/api/v1/auth/login", "/api/v1/auth/phone/**").permitAll()
                        .requestMatchers("/oauth2/**", "/login/oauth2/**", "/api/v1/auth/wechat/**").permitAll()
                        .requestMatchers("/mcp").permitAll() // gated by X-MCP-Token in McpController
                        // External callers: gated by the automation's own X-Automation-Token in AutomationService.
                        .requestMatchers(HttpMethod.POST, "/api/v1/automations/*/trigger").permitAll()
                        .requestMatchers("/api/v1/users/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/providers/**").hasRole("ADMIN") // runs install commands on the host
                        // Everyone may see which connectors exist and whether they're connected. Credentials are
                        // shared by the whole platform, so adding, editing and (dis)connecting stay with admins.
                        .requestMatchers(HttpMethod.GET, "/api/v1/connectors").authenticated()
                        .requestMatchers("/api/v1/connectors/**", "/api/v1/oauth/**").hasRole("ADMIN")
                        // Everyone reads the defaults (e.g. to show an agent's effective model); only admins change them.
                        .requestMatchers(HttpMethod.GET, "/api/v1/settings").authenticated()
                        .requestMatchers("/api/v1/settings/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginProcessingUrl("/api/v1/auth/login")
                        .successHandler(successHandler())
                        .failureHandler(failureHandler()))
                .logout(logout -> logout
                        .logoutUrl("/api/v1/auth/logout")
                        .logoutSuccessHandler((req, res, auth) -> res.setStatus(HttpStatus.OK.value())))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint()));
        return http.build();
    }

    private AuthenticationSuccessHandler successHandler() {
        return (request, response, authentication) -> writeJson(response, HttpStatus.OK, Map.of(
                "username", authentication.getName(),
                "roles", roles(authentication)));
    }

    private AuthenticationFailureHandler failureHandler() {
        return (request, response, exception) ->
                writeJson(response, HttpStatus.UNAUTHORIZED, Map.of("code", "unauthorized",
                        "message", "用户名或密码错误，或账号未授权"));
    }

    /** API calls get 401; page loads are redirected to the login page. */
    private AuthenticationEntryPoint entryPoint() {
        return (request, response, ex) -> {
            String uri = request.getRequestURI();
            if (uri.startsWith("/api/") || uri.startsWith("/mcp")) {
                writeJson(response, HttpStatus.UNAUTHORIZED, Map.of("code", "unauthorized", "message", "请先登录"));
            } else {
                response.sendRedirect("/login");
            }
        };
    }

    private static java.util.List<String> roles(Authentication auth) {
        return auth.getAuthorities().stream().map(Object::toString)
                .map(a -> a.startsWith("ROLE_") ? a.substring(5) : a).toList();
    }

    private void writeJson(HttpServletResponse response, HttpStatus status, Object body) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(mapper.writeValueAsString(body));
    }
}

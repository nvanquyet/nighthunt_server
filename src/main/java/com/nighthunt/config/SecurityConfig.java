package com.nighthunt.config;

import com.nighthunt.security.filter.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    
    @Value("${app.security.csrf-enabled:false}")
    private boolean csrfEnabled;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // CSRF Configuration - togglable via environment variable
        if (csrfEnabled) {
            http.csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
            );
        } else {
            http.csrf(csrf -> csrf.disable());
        }
        
        // Security Headers Configuration
        http.headers(headers -> headers
                // HSTS - Force HTTPS for 1 year
                .httpStrictTransportSecurity(hsts -> hsts
                    .maxAgeInSeconds(31536000)
                    .includeSubDomains(true))
                // Prevent clickjacking attacks
                .frameOptions(frame -> frame.deny())
                // Prevent MIME type sniffing
                .contentTypeOptions(org.springframework.security.config.Customizer.withDefaults())
                .xssProtection(xss -> xss.disable()) // Modern browsers use CSP instead
                // Content Security Policy
                .contentSecurityPolicy(csp -> csp
                    .policyDirectives("default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'"))
                // Referrer Policy
                .referrerPolicy(referrer -> referrer
                    .policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                // Permissions Policy (formerly Feature Policy)
                .permissionsPolicy(permissions -> permissions
                    .policy("geolocation=(), microphone=(), camera=()"))
        );
        
        http.cors(cors -> cors.configure(http))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Auth endpoints (public) - now with /api prefix via context-path
                .requestMatchers("/auth/**", "/actuator/**", "/dashboard.html", "/dashboard/**", "/ws/**").permitAll()
                // DS containers dùng X-DS-Secret header thay vì JWT
                .requestMatchers("/ds/**").permitAll()
                // DS gọi /match/end/ranked bằng X-DS-Secret, không có JWT
                .requestMatchers("/match/end/ranked").permitAll()
                // Admin dashboard API (validates X-Admin-Secret internally)
                .requestMatchers("/admin/**").permitAll()
                // Relay health probe — no auth needed (smoke test + monitoring)
                .requestMatchers("/relay/health").permitAll()
                // Public game config endpoints — client fetches these at startup (may be before auth)
                .requestMatchers("/maps/**", "/game-modes/**").permitAll()
                // Matchmaking cần JWT (user phải login trước)
                .requestMatchers("/matchmaking/**").authenticated()
                // Profile API
                .requestMatchers("/profile/**").authenticated()
                // User API
                .requestMatchers("/user/**").authenticated()
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}


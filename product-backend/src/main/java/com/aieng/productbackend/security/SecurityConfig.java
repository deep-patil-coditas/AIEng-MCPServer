package com.aieng.productbackend.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/** Security filter chain, password encoder and in-memory users (spec section 2, SECURITY-S01..S10). */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Only {@code DELETE /api/products/**} needs ADMIN (S02, S08); everything else is open (S01). The 401 entry point
     * is set on both {@code httpBasic} (bad credentials, S05) and {@code exceptionHandling} (anonymous, S04).
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   ProblemDetailAuthenticationEntryPoint entryPoint,
                                                   ProblemDetailAccessDeniedHandler accessDeniedHandler)
            throws Exception {
        http
                .csrf(csrf -> csrf.disable())                                                  // S09
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)) // S09
                .httpBasic(basic -> basic.authenticationEntryPoint(entryPoint))                // S03, S05
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(entryPoint)                                  // S04
                        .accessDeniedHandler(accessDeniedHandler))                             // S06
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.DELETE, "/api/products/**").hasRole("ADMIN") // S02, S08
                        .anyRequest().permitAll());                                            // S01
        return http.build();
    }

    /** SECURITY-S03: BCrypt only, never {noop}. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** SECURITY-S03 / FR-IN-05: the two fixed demo users. */
    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(
                User.withUsername("admin").password(encoder.encode("admin123")).roles("ADMIN").build(),
                User.withUsername("user").password(encoder.encode("user123")).roles("USER").build());
    }
}

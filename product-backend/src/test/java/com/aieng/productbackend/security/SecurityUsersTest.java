package com.aieng.productbackend.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/** In-memory users and BCrypt passwords (SECURITY-T08). */
@SpringBootTest
class SecurityUsersTest {

    @Autowired
    private UserDetailsService userDetailsService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // SECURITY-T08 (S03, NFR-04)
    @Test
    void T08_usersHaveBcryptPasswordsAndExactRoles() {
        assertThat(passwordEncoder).isInstanceOf(BCryptPasswordEncoder.class);

        assertUser("admin", "admin123", "ROLE_ADMIN");
        assertUser("user", "user123", "ROLE_USER");
    }

    private void assertUser(String username, String rawPassword, String authority) {
        UserDetails user = userDetailsService.loadUserByUsername(username);
        String hash = user.getPassword();

        assertThat(hash).startsWith("$2").doesNotContain("{noop}");
        assertThat(passwordEncoder.matches(rawPassword, hash)).isTrue();
        assertThat(user.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly(authority);
    }
}

package de.pia.inventur.backend.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@Import(JwtDecoderKonfiguration.class)
public class SecurityKonfiguration {

    @Bean
    SecurityFehlerHandler securityFehlerHandler() { return new SecurityFehlerHandler(); }

    /** Ausschliesslich JWT-Authentifizierung; verhindert weiterhin die Demo-Benutzeranlage. */
    @Bean
    AuthenticationManager authenticationManager(JwtDecoder decoder, OidcBenutzerZuordnung zuordnung) {
        var provider = new JwtAuthenticationProvider(decoder);
        provider.setJwtAuthenticationConverter(new InventurJwtAuthenticationConverter(zuordnung));
        return new ProviderManager(provider);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityFehlerHandler fehler,
            AuthenticationManager authenticationManager) throws Exception {
        var bearer = new DefaultBearerTokenResolver();
        bearer.setAllowUriQueryParameter(false);
        bearer.setAllowFormEncodedBodyParameter(false);
        return http
                .authorizeHttpRequests(regeln -> regeln
                        .requestMatchers(HttpMethod.GET, "/api/v1/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/zaehlerlisten",
                                "/api/v1/zaehlerlisten/*/sitzungen", "/api/v1/sitzungen/*/blaetter",
                                "/api/v1/sitzungen/*/blaetter/*").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/v1/sitzungen/*/positionen/*/ergebnis",
                                "/api/v1/sitzungen/*/blaetter/*/abschluss").authenticated()
                        // Neue Schreibrequests pruefen die Services; Replays bleiben urhebergebunden.
                        .anyRequest().denyAll())
                .sessionManagement(sitzung -> sitzung.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                // Reine API: keine Cookie-Anmeldung oder sonstigen automatisch gesendeten Credentials.
                // Bei einer spaeteren Cookie-/Browser-Anmeldung muss CSRF neu bewertet werden.
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .oauth2ResourceServer(resource -> resource
                        .authenticationManagerResolver(request -> authenticationManager)
                        .bearerTokenResolver(bearer)
                        .authenticationEntryPoint(fehler).accessDeniedHandler(fehler))
                .exceptionHandling(fehlerbehandlung -> fehlerbehandlung
                        .authenticationEntryPoint(fehler).accessDeniedHandler(fehler))
                .build();
    }
}

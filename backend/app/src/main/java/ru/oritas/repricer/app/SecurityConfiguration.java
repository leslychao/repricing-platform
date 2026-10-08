package ru.oritas.repricer.app;

import java.net.URI;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import ru.oritas.repricer.app.files.UploadAdmissionFilter;
import ru.oritas.repricer.platform.FileWorkGate;
import ru.oritas.repricer.platform.OutboxService;

@Configuration
public class SecurityConfiguration {
  @Bean
  JwtDecoder jwtDecoder(
      @Value("${repricer.oidc.issuer}") String issuer,
      @Value("${repricer.oidc.jwks}") String jwks,
      @Value("${repricer.oidc.audience}") String audience) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer),
            token ->
                token.getAudience().contains(audience)
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_audience"))));
    return decoder;
  }

  @Bean
  SecurityFilterChain security(
      HttpSecurity http,
      FileWorkGate fileWorkGate,
      Clock clock,
      OutboxService outbox,
      @Value("${repricer.public-base-url}") URI publicUrl)
      throws Exception {
    CookieCsrfTokenRepository csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
    csrf.setCookieCustomizer(
        cookie -> cookie.path("/").sameSite("Lax").secure(publicUrl.getScheme().equals("https")));
    http.sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(
            config ->
                config
                    .csrfTokenRepository(csrf)
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/actuator/health", "/api/v1/auth/csrf")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(resource -> resource.jwt(Customizer.withDefaults()))
        .addFilterBefore(new OriginFilter(publicUrl.toString()), CsrfFilter.class)
        .addFilterAfter(
            new UploadAdmissionFilter(fileWorkGate, clock, outbox), AuthorizationFilter.class)
        .headers(
            headers ->
                headers.contentSecurityPolicy(
                    csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
        .formLogin(config -> config.disable())
        .httpBasic(config -> config.disable())
        .logout(config -> config.disable());
    return http.build();
  }
}

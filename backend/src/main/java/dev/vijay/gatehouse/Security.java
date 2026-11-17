package dev.vijay.gatehouse;

import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.*;
import com.nimbusds.jose.proc.SecurityContext;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.authorization.*;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.*;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.security.SpringSessionBackedSessionRegistry;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.cors.*;

@Configuration
class Security {
  @Bean
  PasswordEncoder passwords() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }

  @Bean
  OAuth2AuthorizationService authorizations(JdbcTemplate db, ClientStore clients) {
    return new JdbcOAuth2AuthorizationService(db, clients);
  }

  @Bean
  OAuth2AuthorizationConsentService consents(JdbcTemplate db, ClientStore clients) {
    return new JdbcOAuth2AuthorizationConsentService(db, clients);
  }

  @Bean
  @DependsOn("flywayInitializer")
  JWKSource<SecurityContext> keys(KeyVault vault) {
    return new ImmutableJWKSet<>(new JWKSet(vault.load()));
  }

  @Bean
  AuthorizationServerSettings authorizationSettings(@Value("${app.issuer}") String issuer) {
    Rules.redirect(issuer);
    return AuthorizationServerSettings.builder().issuer(issuer).build();
  }

  @Bean
  SessionRegistry sessions(JdbcIndexedSessionRepository repository) {
    return new SpringSessionBackedSessionRegistry<>(repository);
  }

  @Bean
  JwtDecoder decoder(JWKSource<SecurityContext> keys) {
    return OAuth2AuthorizationServerConfiguration.jwtDecoder(keys);
  }

  @Bean
  OAuth2TokenCustomizer<JwtEncodingContext> claims(UserStore users) {
    return context -> {
      boolean access = OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType());
      if (access)
        context
            .getClaims()
            .audience(
                new ArrayList<>(
                    List.of(context.getRegisteredClient().getClientId(), "gatehouse-api")));
      if (AuthorizationGrantType.CLIENT_CREDENTIALS.equals(context.getAuthorizationGrantType()))
        return;
      var user = users.byEmail(context.getPrincipal().getName());
      if (user == null || !user.enabled() || !user.emailVerified())
        throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
      context.getClaims().subject(user.id().toString());
      if (context.getAuthorizedScopes().contains("profile"))
        context.getClaims().claim("name", user.displayName());
      if (context.getAuthorizedScopes().contains("email"))
        context.getClaims().claim("email", user.email());
      if (context.getAuthorizedScopes().contains("email"))
        context.getClaims().claim("email_verified", user.emailVerified());
      if (context.getAuthorizedScopes().contains("roles"))
        context.getClaims().claim("roles", users.roles(user.id()));
    };
  }

  @Bean
  CorsConfigurationSource cors(ClientStore clients) {
    return request -> {
      var config = new CorsConfiguration();
      config.setAllowedOrigins(clients.origins());
      config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
      config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
      config.setAllowCredentials(false);
      return config;
    };
  }

  @Bean
  @Order(1)
  SecurityFilterChain oauth(
      HttpSecurity http,
      JdbcTemplate db,
      TransactionTemplate transaction,
      Audit audit,
      CorsConfigurationSource cors)
      throws Exception {
    http.oauth2AuthorizationServer(
            server -> {
              http.securityMatcher(server.getEndpointsMatcher());
              server.oidc(Customizer.withDefaults());
            })
        .authorizeHttpRequests(a -> a.anyRequest().authenticated())
        .cors(c -> c.configurationSource(cors))
        .exceptionHandling(
            e ->
                e.defaultAuthenticationEntryPointFor(
                    new LoginUrlAuthenticationEntryPoint("/login"),
                    new MediaTypeRequestMatcher(MediaType.TEXT_HTML)))
        .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))
        .addFilterAfter(new RequestLimitFilter(db, audit), SecurityContextHolderFilter.class)
        .addFilterAfter(new ProtocolLockFilter(db, transaction), RequestLimitFilter.class);
    return http.build();
  }

  @Bean
  @Order(2)
  SecurityFilterChain resource(
      HttpSecurity http,
      OAuth2AuthorizationService authorizations,
      JWKSource<SecurityContext> keys,
      @Value("${app.issuer}") String issuer,
      CorsConfigurationSource cors)
      throws Exception {
    var jwt = OAuth2AuthorizationServerConfiguration.jwtDecoder(keys);
    if (jwt instanceof NimbusJwtDecoder decoder)
      decoder.setJwtValidator(
          new DelegatingOAuth2TokenValidator<>(
              JwtValidators.createDefaultWithIssuer(issuer),
              token -> {
                var stored =
                    authorizations.findByToken(token.getTokenValue(), OAuth2TokenType.ACCESS_TOKEN);
                if (token.getAudience().contains("gatehouse-api")
                    && stored != null
                    && stored.getAccessToken() != null
                    && stored.getAccessToken().isActive())
                  return OAuth2TokenValidatorResult.success();
                return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error(
                        "invalid_token", "Token is inactive or has the wrong audience.", null));
              }));
    http.securityMatcher("/api/resource/**")
        .csrf(c -> c.disable())
        .cors(c -> c.configurationSource(cors))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(HttpMethod.OPTIONS, "/**")
                    .permitAll()
                    .requestMatchers("/api/resource/write")
                    .hasAuthority("SCOPE_api.write")
                    .anyRequest()
                    .hasAuthority("SCOPE_api.read"))
        .oauth2ResourceServer(o -> o.jwt(j -> j.decoder(jwt)));
    return http.build();
  }

  @Bean
  @Order(3)
  SecurityFilterChain web(
      HttpSecurity http, UserStore users, PasswordEncoder passwords, Audit audit, JdbcTemplate db)
      throws Exception {
    var provider = new DaoAuthenticationProvider(users);
    provider.setPasswordEncoder(passwords);
    var cache = new HttpSessionRequestCache();
    cache.setRequestMatcher(request -> request.getRequestURI().equals("/oauth2/authorize"));
    http.authenticationProvider(provider)
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/api/health",
                        "/api/csrf",
                        "/api/password/forgot",
                        "/api/password/reset",
                        "/api/registration/signup",
                        "/api/registration/resend",
                        "/api/registration/verify")
                    .permitAll()
                    .requestMatchers("/api/admin/**")
                    .hasRole("ADMIN")
                    .requestMatchers("/api/**")
                    .authenticated()
                    .anyRequest()
                    .permitAll())
        .addFilterAfter(new AccountGuardFilter(users), SecurityContextHolderFilter.class)
        .addFilterBefore(
            new RequestLimitFilter(db, audit),
            org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
                .class)
        .requestCache(c -> c.requestCache(cache))
        .exceptionHandling(
            e ->
                e.defaultAuthenticationEntryPointFor(
                    (request, response, error) -> {
                      response.setStatus(401);
                      response.setContentType("application/json");
                      response.getWriter().write("{\"error\":\"Please sign in.\"}");
                    },
                    request -> request.getRequestURI().startsWith("/api/")))
        .formLogin(
            form ->
                form.loginPage("/login")
                    .successHandler(
                        (request, response, authentication) -> {
                          var user = users.current(authentication);
                          request.getSession().setAttribute("accountVersion", user.version());
                          db.update(
                              "UPDATE iam_users SET login_failures=0,locked_until=NULL WHERE id=?",
                              user.id());
                          audit.record(
                              user.email(), "LOGIN_SUCCESS", user.email(), "Browser sign-in");
                          var saved = cache.getRequest(request, response);
                          cache.removeRequest(request, response);
                          String redirect = saved == null ? "/" : saved.getRedirectUrl();
                          response.setContentType("application/json");
                          response
                              .getWriter()
                              .write(
                                  tools.jackson.databind.json.JsonMapper.builder()
                                      .build()
                                      .writeValueAsString(Map.of("redirect", redirect)));
                        })
                    .failureHandler(
                        (request, response, error) -> {
                          String email = request.getParameter("username");
                          if (email != null && email.length() <= 254) {
                            db.update(
                                "UPDATE iam_users SET"
                                    + " login_failures=login_failures+1,locked_until=CASE WHEN"
                                    + " login_failures+1>=5 THEN CURRENT_TIMESTAMP+INTERVAL '10"
                                    + " minutes' ELSE locked_until END WHERE email=?",
                                email.trim().toLowerCase(Locale.ROOT));
                            audit.record(email, "LOGIN_FAILED", email, "Credentials rejected");
                          }
                          response.setStatus(401);
                          response.setContentType("application/json");
                          response
                              .getWriter()
                              .write(
                                  "{\"error\":\"Sign-in failed. Check your details or try again"
                                      + " later.\"}");
                        }))
        .logout(
            l ->
                l.logoutUrl("/logout")
                    .logoutSuccessHandler(
                        (request, response, authentication) -> {
                          audit.record(
                              authentication == null ? "anonymous" : authentication.getName(),
                              "LOGOUT",
                              "browser",
                              "Session ended");
                          response.setContentType("application/json");
                          response.getWriter().write("{\"ok\":true}");
                        }))
        .headers(
            h ->
                h.contentSecurityPolicy(
                    c ->
                        c.policyDirectives(
                            "default-src 'self'; script-src 'self'; style-src 'self'"
                                + " 'unsafe-inline' https://fonts.googleapis.com; font-src 'self'"
                                + " https://fonts.gstatic.com; img-src 'self' data:; connect-src"
                                + " 'self'; frame-ancestors 'none'; base-uri 'self'; form-action"
                                + " 'self'")));
    return http.build();
  }
}

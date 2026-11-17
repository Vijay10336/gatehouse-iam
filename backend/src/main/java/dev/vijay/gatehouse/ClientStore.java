package dev.vijay.gatehouse;

import java.time.Duration;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.server.authorization.client.*;
import org.springframework.security.oauth2.server.authorization.settings.*;
import org.springframework.stereotype.Repository;

@Repository
class ClientStore implements RegisteredClientRepository {
  record Input(
      String clientId,
      String name,
      String type,
      List<String> redirectUris,
      List<String> logoutUris,
      List<String> scopes) {}

  record View(
      String id,
      String clientId,
      String name,
      String type,
      boolean enabled,
      Set<String> scopes,
      Set<String> redirectUris,
      Set<String> logoutUris) {}

  final JdbcRegisteredClientRepository delegate;
  private final JdbcTemplate db;
  private final PasswordEncoder passwords;

  ClientStore(JdbcTemplate db, PasswordEncoder passwords) {
    this.db = db;
    this.passwords = passwords;
    this.delegate = new JdbcRegisteredClientRepository(db);
  }

  @Override
  public void save(RegisteredClient client) {
    delegate.save(client);
  }

  @Override
  public RegisteredClient findById(String id) {
    return delegate.findById(id);
  }

  @Override
  public RegisteredClient findByClientId(String id) {
    var rows =
        db.queryForList(
            "SELECT s.enabled FROM iam_client_status s JOIN oauth2_registered_client c ON"
                + " c.id=s.client_id WHERE c.client_id=?",
            Boolean.class,
            id);
    return rows.isEmpty() || !rows.getFirst() ? null : delegate.findByClientId(id);
  }

  RegisteredClient create(Input input, String rawSecret) {
    if (input == null || !List.of("public", "web", "machine").contains(input.type()))
      throw ApiException.bad("Choose a valid application type.");
    boolean machine = input.type().equals("machine"), publicClient = input.type().equals("public");
    if (input.scopes() == null
        || input.scopes().isEmpty()
        || input.scopes().size() > Rules.SCOPES.size()
        || !Rules.SCOPES.containsAll(input.scopes()))
      throw ApiException.bad("Choose supported scopes.");
    if (machine && input.scopes().stream().anyMatch(scope -> !scope.equals("api.read")))
      throw ApiException.bad("Machine clients support api.read in this version.");
    if (!machine && !input.scopes().contains("openid"))
      throw ApiException.bad("Include openid for browser sign-in.");
    List<String> redirects = validatedUris(input.redirectUris()),
        logout = validatedUris(input.logoutUris());
    if (!machine && redirects.isEmpty()) throw ApiException.bad("Add at least one redirect URL.");
    if (machine && (!redirects.isEmpty() || !logout.isEmpty()))
      throw ApiException.bad("Machine clients do not use redirect URLs.");
    String id = UUID.randomUUID().toString();
    var builder =
        RegisteredClient.withId(id)
            .clientId(Rules.clientId(input.clientId()))
            .clientName(Rules.text(input.name(), 100))
            .clientAuthenticationMethod(
                publicClient
                    ? ClientAuthenticationMethod.NONE
                    : ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .clientSettings(
                ClientSettings.builder()
                    .requireProofKey(!machine)
                    .requireAuthorizationConsent(!machine)
                    .build())
            .tokenSettings(
                TokenSettings.builder()
                    .accessTokenTimeToLive(Duration.ofMinutes(5))
                    .authorizationCodeTimeToLive(Duration.ofMinutes(1))
                    .refreshTokenTimeToLive(Duration.ofHours(8))
                    .reuseRefreshTokens(false)
                    .build());
    if (!publicClient) builder.clientSecret(passwords.encode(Rules.text(rawSecret, 72)));
    if (machine) builder.authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS);
    else {
      builder.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE);
      if (!publicClient) builder.authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN);
      redirects.forEach(builder::redirectUri);
      logout.forEach(builder::postLogoutRedirectUri);
    }
    input.scopes().forEach(builder::scope);
    RegisteredClient client = builder.build();
    save(client);
    db.update("INSERT INTO iam_client_status(client_id,enabled) VALUES(?,TRUE)", id);
    return client;
  }

  private List<String> validatedUris(List<String> input) {
    if (input == null) return List.of();
    if (input.size() > 8) throw ApiException.bad("Use at most eight redirect URLs.");
    List<String> result = input.stream().map(Rules::redirect).distinct().toList();
    if (String.join(",", result).length() > 1000)
      throw ApiException.bad("Redirect URLs are too long.");
    return result;
  }

  View view(RegisteredClient client, boolean enabled) {
    String type =
        client.getAuthorizationGrantTypes().contains(AuthorizationGrantType.CLIENT_CREDENTIALS)
            ? "machine"
            : client.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE)
                ? "public"
                : "web";
    return new View(
        client.getId(),
        client.getClientId(),
        client.getClientName(),
        type,
        enabled,
        client.getScopes(),
        client.getRedirectUris(),
        client.getPostLogoutRedirectUris());
  }

  List<View> list() {
    return db.query(
        "SELECT client_id,enabled FROM iam_client_status ORDER BY created_at",
        (r, n) -> view(delegate.findById(r.getString(1)), r.getBoolean(2)));
  }

  List<String> origins() {
    var origins = new HashSet<String>();
    for (var client : list())
      if (client.enabled())
        for (String redirect : client.redirectUris()) origins.add(Rules.origin(redirect));
    return List.copyOf(origins);
  }
}

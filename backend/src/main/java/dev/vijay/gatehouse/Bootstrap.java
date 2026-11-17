package dev.vijay.gatehouse;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class Bootstrap implements ApplicationRunner {
  private final JdbcTemplate db;
  private final PasswordEncoder passwords;
  private final ClientStore clients;
  private final String email, password, secret, issuer;

  Bootstrap(
      JdbcTemplate db,
      PasswordEncoder passwords,
      ClientStore clients,
      @Value("${app.bootstrap-email}") String email,
      @Value("${app.bootstrap-password}") String password,
      @Value("${app.demo-client-secret}") String secret,
      @Value("${app.issuer}") String issuer) {
    this.db = db;
    this.passwords = passwords;
    this.clients = clients;
    this.email = email;
    this.password = password;
    this.secret = secret;
    this.issuer = issuer;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    db.queryForMap("SELECT id FROM iam_lock WHERE id=1 FOR UPDATE");
    for (String role : List.of("viewer", "editor", "operator"))
      db.update(
          "INSERT INTO iam_roles(id,name,description) VALUES(?,?,?) ON CONFLICT(name) DO NOTHING",
          UUID.randomUUID(),
          role,
          switch (role) {
            case "viewer" -> "Read application data";
            case "editor" -> "Create and update application data";
            default -> "Operate application services";
          });
    boolean initialized =
        Boolean.TRUE.equals(
            db.queryForObject("SELECT initialized FROM iam_bootstrap WHERE id=1", Boolean.class));
    if (!initialized) {
      String address = Rules.email(email);
      String encoded = passwords.encode(Rules.password(password));
      var existing = db.queryForList("SELECT id FROM iam_users WHERE email=?", UUID.class, address);
      UUID id = existing.isEmpty() ? UUID.randomUUID() : existing.getFirst();
      if (existing.isEmpty()) {
        db.update(
            "INSERT INTO"
                + " iam_users(id,email,display_name,password_hash,administrator,email_verified)"
                + " VALUES(?,?,?,?,TRUE,TRUE)",
            id,
            address,
            "Administrator",
            encoded);
      } else {
        // The setup secret establishes the first administrator, including a pre-claimed address.
        db.update(
            "UPDATE iam_users SET"
                + " password_hash=?,administrator=TRUE,enabled=TRUE,email_verified=TRUE,version=version+1"
                + " WHERE id=?",
            encoded,
            id);
        db.update("DELETE FROM spring_session WHERE principal_name=?", address);
        db.update("DELETE FROM oauth2_authorization WHERE principal_name=?", address);
      }
      db.update(
          "INSERT INTO iam_user_roles(user_id,role_id) SELECT ?,id FROM iam_roles WHERE name IN"
              + " ('viewer','editor') ON CONFLICT DO NOTHING",
          id);
      db.update("UPDATE iam_bootstrap SET initialized=TRUE WHERE id=1");
    }
    if (db.queryForList("SELECT id FROM oauth2_registered_client WHERE client_id='gatehouse-demo'")
        .isEmpty())
      clients.create(
          new ClientStore.Input(
              "gatehouse-demo",
              "Demo web application",
              "public",
              List.of(issuer + "/demo/callback"),
              List.of(issuer + "/demo"),
              List.of("openid", "profile", "email", "roles", "api.read", "api.write")),
          null);
    if (db.queryForList(
            "SELECT id FROM oauth2_registered_client WHERE client_id='gatehouse-service'")
        .isEmpty())
      clients.create(
          new ClientStore.Input(
              "gatehouse-service",
              "Example service",
              "machine",
              List.of(),
              List.of(),
              List.of("api.read")),
          Rules.text(secret, 72));
  }
}

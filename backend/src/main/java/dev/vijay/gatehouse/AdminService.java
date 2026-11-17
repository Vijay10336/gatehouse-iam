package dev.vijay.gatehouse;

import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AdminService {
  record UserInput(
      String email,
      String displayName,
      String password,
      boolean administrator,
      List<UUID> roleIds) {}

  record UserUpdate(
      String displayName,
      boolean enabled,
      boolean administrator,
      List<UUID> roleIds,
      long version) {}

  record RoleInput(String name, String description) {}

  record Role(UUID id, String name, String description, long assignedUsers) {}

  record Session(
      String id,
      String email,
      String displayName,
      Instant lastAccess,
      Instant expiresAt,
      boolean current) {}

  record Event(
      UUID id,
      String actor,
      String event,
      String target,
      String detail,
      String ip,
      Instant createdAt) {}

  private final JdbcTemplate db;
  private final UserStore users;
  private final ClientStore clients;
  private final PasswordEncoder passwords;
  private final Audit audit;

  AdminService(
      JdbcTemplate db,
      UserStore users,
      ClientStore clients,
      PasswordEncoder passwords,
      Audit audit) {
    this.db = db;
    this.users = users;
    this.clients = clients;
    this.passwords = passwords;
    this.audit = audit;
  }

  private UserStore.Account lock(Authentication auth) {
    db.queryForMap("SELECT id FROM iam_lock WHERE id=1 FOR UPDATE");
    return users.admin(auth);
  }

  @Transactional
  UserStore.View createUser(Authentication auth, UserInput input) {
    var actor = lock(auth);
    UUID id = UUID.randomUUID();
    String email = Rules.email(input.email()), name = Rules.text(input.displayName(), 100);
    db.update(
        "INSERT INTO iam_users(id,email,display_name,password_hash,administrator)"
            + " VALUES(?,?,?,?,?)",
        id,
        email,
        name,
        passwords.encode(Rules.password(input.password())),
        input.administrator());
    assign(id, input.roleIds());
    audit.record(actor.email(), "USER_CREATED", email, "User added to the directory");
    return users.view(users.byId(id));
  }

  @Transactional
  UserStore.View updateUser(Authentication auth, UUID id, UserUpdate input) {
    var actor = lock(auth);
    var user = users.byId(id);
    if (user.version() != input.version())
      throw new ApiException(
          HttpStatus.CONFLICT, "This user changed in another session. Refresh before saving.");
    keepAdministrator(user, input.enabled(), input.administrator());
    db.update(
        "UPDATE iam_users SET display_name=?,enabled=?,administrator=?,updated_at=CURRENT_TIMESTAMP"
            + " WHERE id=?",
        Rules.text(input.displayName(), 100),
        input.enabled(),
        input.administrator(),
        id);
    assign(id, input.roleIds());
    users.revoke(user);
    audit.record(
        actor.email(),
        "USER_UPDATED",
        user.email(),
        "Profile, access status, or roles updated; sessions and grants revoked");
    return users.view(users.byId(id));
  }

  private void keepAdministrator(UserStore.Account user, boolean enabled, boolean admin) {
    if (user.enabled()
        && user.administrator()
        && !(enabled && admin)
        && db.queryForObject(
                "SELECT COUNT(*) FROM iam_users WHERE administrator=TRUE AND enabled=TRUE AND"
                    + " email_verified=TRUE",
                Long.class)
            <= 1)
      throw ApiException.bad("Keep at least one enabled administrator in the directory.");
  }

  private void assign(UUID userId, List<UUID> ids) {
    if (ids == null || ids.size() > 50 || ids.stream().distinct().count() != ids.size())
      throw ApiException.bad("Choose valid roles.");
    for (UUID id : ids)
      if (id == null
          || db.queryForObject("SELECT COUNT(*) FROM iam_roles WHERE id=?", Long.class, id) != 1)
        throw ApiException.bad("A selected role no longer exists.");
    db.update("DELETE FROM iam_user_roles WHERE user_id=?", userId);
    for (UUID id : ids)
      db.update("INSERT INTO iam_user_roles(user_id,role_id) VALUES(?,?)", userId, id);
  }

  @Transactional
  void unlock(Authentication auth, UUID id) {
    var actor = lock(auth);
    var user = users.byId(id);
    db.update("UPDATE iam_users SET login_failures=0,locked_until=NULL WHERE id=?", id);
    audit.record(actor.email(), "USER_UNLOCKED", user.email(), "Login lock cleared");
  }

  @Transactional
  void revoke(Authentication auth, UUID id) {
    var actor = lock(auth);
    var user = users.byId(id);
    users.revoke(user);
    audit.record(
        actor.email(), "ACCESS_REVOKED", user.email(), "Browser sessions and OAuth grants revoked");
  }

  @Transactional
  void deleteUser(Authentication auth, UUID id) {
    var actor = lock(auth);
    var user = users.byId(id);
    keepAdministrator(user, false, false);
    users.revoke(user);
    db.update("DELETE FROM iam_users WHERE id=?", id);
    audit.record(actor.email(), "USER_DELETED", user.email(), "User removed from the directory");
  }

  List<Role> roles() {
    return db.query(
        "SELECT r.id,r.name,r.description,COUNT(ur.user_id) AS assigned FROM iam_roles r LEFT JOIN"
            + " iam_user_roles ur ON r.id=ur.role_id GROUP BY r.id ORDER BY r.name",
        (r, n) ->
            new Role(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getLong(4)));
  }

  @Transactional
  Role createRole(Authentication auth, RoleInput input) {
    var actor = lock(auth);
    String name = Rules.roleName(input.name());
    String description = input.description() == null ? "" : input.description().trim();
    if (description.length() > 240)
      throw ApiException.bad("Keep the role description within 240 characters.");
    UUID id = UUID.randomUUID();
    db.update("INSERT INTO iam_roles(id,name,description) VALUES(?,?,?)", id, name, description);
    audit.record(actor.email(), "ROLE_CREATED", name, "Role added");
    return new Role(id, name, description, 0);
  }

  @Transactional
  void deleteRole(Authentication auth, UUID id) {
    var actor = lock(auth);
    var rows = db.queryForList("SELECT name FROM iam_roles WHERE id=?", String.class, id);
    if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Role not found.");
    var affected =
        db.queryForList("SELECT user_id FROM iam_user_roles WHERE role_id=?", UUID.class, id);
    for (UUID user : affected) users.revoke(users.byId(user));
    db.update("DELETE FROM iam_roles WHERE id=?", id);
    audit.record(
        actor.email(),
        "ROLE_DELETED",
        rows.getFirst(),
        "Assignments removed and affected grants revoked");
  }

  @Transactional
  Object createClient(Authentication auth, ClientStore.Input input) {
    var actor = lock(auth);
    String secret = "public".equals(input.type()) ? null : Rules.random();
    var client = clients.create(input, secret);
    audit.record(actor.email(), "CLIENT_CREATED", client.getClientId(), "Application registered");
    var result = new LinkedHashMap<String, Object>();
    result.put("client", clients.view(client, true));
    if (secret != null) result.put("secret", secret);
    return result;
  }

  @Transactional
  void enableClient(Authentication auth, String id, boolean enabled) {
    var actor = lock(auth);
    var client = clients.findById(id);
    if (client == null) throw new ApiException(HttpStatus.NOT_FOUND, "Application not found.");
    db.update("UPDATE iam_client_status SET enabled=? WHERE client_id=?", enabled, id);
    if (!enabled) db.update("DELETE FROM oauth2_authorization WHERE registered_client_id=?", id);
    audit.record(
        actor.email(),
        enabled ? "CLIENT_ENABLED" : "CLIENT_DISABLED",
        client.getClientId(),
        "Application access changed");
  }

  @Transactional
  Object rotateClient(Authentication auth, String id) {
    var actor = lock(auth);
    var client = clients.findById(id);
    if (client == null) throw new ApiException(HttpStatus.NOT_FOUND, "Application not found.");
    if (client.getClientSecret() == null)
      throw ApiException.bad("Public clients do not use a client secret.");
    String secret = Rules.random();
    clients.save(RegisteredClient.from(client).clientSecret(passwords.encode(secret)).build());
    db.update("DELETE FROM oauth2_authorization WHERE registered_client_id=?", id);
    audit.record(
        actor.email(),
        "CLIENT_SECRET_ROTATED",
        client.getClientId(),
        "Old secret and grants invalidated");
    return Map.of("secret", secret);
  }

  List<Session> sessions(String current, String email) {
    return db.query(
        "SELECT"
            + " s.primary_id,s.session_id,s.principal_name,u.display_name,s.last_access_time,s.expiry_time"
            + " FROM spring_session s JOIN iam_users u ON u.email=s.principal_name WHERE"
            + " s.expiry_time>? AND (CAST(? AS text) IS NULL OR s.principal_name=?) ORDER BY"
            + " s.last_access_time DESC LIMIT 200",
        (r, n) ->
            new Session(
                r.getString(1),
                r.getString(3),
                r.getString(4),
                Instant.ofEpochMilli(r.getLong(5)),
                Instant.ofEpochMilli(r.getLong(6)),
                r.getString(2).equals(current)),
        System.currentTimeMillis(),
        email,
        email);
  }

  @Transactional
  void deleteSession(Authentication auth, String id, boolean admin) {
    var user = users.current(auth);
    if (admin) lock(auth);
    var names =
        db.queryForList(
            "SELECT principal_name FROM spring_session WHERE primary_id=?", String.class, id);
    if (names.isEmpty()) return;
    if (!admin && !user.email().equals(names.getFirst()))
      throw new ApiException(HttpStatus.FORBIDDEN, "This session belongs to another account.");
    db.update("DELETE FROM spring_session WHERE primary_id=?", id);
    audit.record(user.email(), "SESSION_REVOKED", names.getFirst(), "Browser session revoked");
  }

  List<Event> events(String kind) {
    return db.query(
        "SELECT * FROM iam_audit WHERE (?='' OR event=?) ORDER BY created_at DESC,id LIMIT 100",
        (r, n) ->
            new Event(
                r.getObject("id", UUID.class),
                r.getString("actor"),
                r.getString("event"),
                r.getString("target"),
                r.getString("detail"),
                r.getString("ip"),
                r.getTimestamp("created_at").toInstant()),
        kind,
        kind);
  }

  Object overview() {
    return Map.of(
        "users",
        count("SELECT COUNT(*) FROM iam_users"),
        "enabledUsers",
        count("SELECT COUNT(*) FROM iam_users WHERE enabled=TRUE"),
        "administrators",
        count("SELECT COUNT(*) FROM iam_users WHERE administrator=TRUE"),
        "applications",
        count("SELECT COUNT(*) FROM iam_client_status WHERE enabled=TRUE"),
        "roles",
        count("SELECT COUNT(*) FROM iam_roles"),
        "sessions",
        db.queryForObject(
            "SELECT COUNT(*) FROM spring_session WHERE principal_name IS NOT NULL AND"
                + " expiry_time>?",
            Long.class,
            System.currentTimeMillis()),
        "signIns",
        count(
            "SELECT COUNT(*) FROM iam_audit WHERE event='LOGIN_SUCCESS' AND"
                + " created_at>CURRENT_TIMESTAMP-INTERVAL '24 hours'"),
        "failedSignIns",
        count(
            "SELECT COUNT(*) FROM iam_audit WHERE event='LOGIN_FAILED' AND"
                + " created_at>CURRENT_TIMESTAMP-INTERVAL '24 hours'"),
        "tokens",
        count(
            "SELECT COUNT(*) FROM iam_audit WHERE event='TOKEN_ISSUED' AND"
                + " created_at>CURRENT_TIMESTAMP-INTERVAL '24 hours'"));
  }

  private long count(String sql) {
    return db.queryForObject(sql, Long.class);
  }
}

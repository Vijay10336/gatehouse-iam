package dev.vijay.gatehouse;

import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;

@Service
class UserStore implements UserDetailsService {
  record Account(
      UUID id,
      String email,
      String displayName,
      String passwordHash,
      boolean enabled,
      boolean administrator,
      boolean emailVerified,
      long version,
      Instant lockedUntil,
      Instant createdAt) {}

  record View(
      UUID id,
      String email,
      String displayName,
      boolean enabled,
      boolean administrator,
      boolean emailVerified,
      long version,
      Instant lockedUntil,
      Instant createdAt,
      List<String> roles) {}

  final JdbcTemplate db;

  UserStore(JdbcTemplate db) {
    this.db = db;
  }

  private final org.springframework.jdbc.core.RowMapper<Account> mapper =
      (r, n) ->
          new Account(
              r.getObject("id", UUID.class),
              r.getString("email"),
              r.getString("display_name"),
              r.getString("password_hash"),
              r.getBoolean("enabled"),
              r.getBoolean("administrator"),
              r.getBoolean("email_verified"),
              r.getLong("version"),
              r.getTimestamp("locked_until") == null
                  ? null
                  : r.getTimestamp("locked_until").toInstant(),
              r.getTimestamp("created_at").toInstant());

  Account byEmail(String email) {
    if (email == null) return null;
    var results =
        db.query(
            "SELECT * FROM iam_users WHERE email=?", mapper, email.trim().toLowerCase(Locale.ROOT));
    return results.isEmpty() ? null : results.getFirst();
  }

  Account byId(UUID id) {
    var results = db.query("SELECT * FROM iam_users WHERE id=?", mapper, id);
    if (results.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "User not found.");
    return results.getFirst();
  }

  Account current(Authentication authentication) {
    Account user = authentication == null ? null : byEmail(authentication.getName());
    if (user == null || !user.enabled() || !user.emailVerified())
      throw new ApiException(HttpStatus.UNAUTHORIZED, "Please sign in.");
    return user;
  }

  Account admin(Authentication authentication) {
    Account user = current(authentication);
    if (!user.administrator())
      throw new ApiException(HttpStatus.FORBIDDEN, "Administrator access is required.");
    return user;
  }

  List<String> roles(UUID id) {
    return db.queryForList(
        "SELECT r.name FROM iam_roles r JOIN iam_user_roles ur ON ur.role_id=r.id WHERE"
            + " ur.user_id=? ORDER BY r.name",
        String.class,
        id);
  }

  View view(Account account) {
    return new View(
        account.id(),
        account.email(),
        account.displayName(),
        account.enabled(),
        account.administrator(),
        account.emailVerified(),
        account.version(),
        account.lockedUntil(),
        account.createdAt(),
        roles(account.id()));
  }

  List<View> users(String search, int page, boolean administrators) {
    String term = search == null ? "" : search.trim();
    return db
        .query(
            "SELECT * FROM iam_users WHERE POSITION(LOWER(?) IN LOWER(email || ' ' ||"
                + " display_name))>0 AND (?=FALSE OR administrator=TRUE) ORDER BY created_at"
                + " DESC,id LIMIT 50 OFFSET ?",
            mapper,
            term,
            administrators,
            Math.max(0, page) * 50)
        .stream()
        .map(this::view)
        .toList();
  }

  @Override
  public UserDetails loadUserByUsername(String email) {
    Account account = byEmail(email);
    if (account == null) throw new UsernameNotFoundException("Email or password is incorrect.");
    return User.withUsername(account.email())
        .password(account.passwordHash())
        .roles(account.administrator() ? "ADMIN" : "USER")
        .disabled(!account.enabled() || !account.emailVerified())
        .accountLocked(
            account.lockedUntil() != null && account.lockedUntil().isAfter(Instant.now()))
        .build();
  }

  void revoke(Account account) {
    db.update(
        "UPDATE iam_users SET version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",
        account.id());
    db.update("DELETE FROM spring_session WHERE principal_name=?", account.email());
    db.update("DELETE FROM oauth2_authorization WHERE principal_name=?", account.email());
  }
}

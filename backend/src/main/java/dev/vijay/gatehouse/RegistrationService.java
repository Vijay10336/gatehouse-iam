package dev.vijay.gatehouse;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class RegistrationService {
  private final JdbcTemplate db;
  private final UserStore users;
  private final PasswordEncoder passwords;
  private final MailSender mail;
  private final Audit audit;
  private final String issuer;
  private final String from;

  RegistrationService(
      JdbcTemplate db,
      UserStore users,
      PasswordEncoder passwords,
      MailSender mail,
      Audit audit,
      @Value("${app.issuer}") String issuer,
      @Value("${app.mail-from}") String from) {
    this.db = db;
    this.users = users;
    this.passwords = passwords;
    this.mail = mail;
    this.audit = audit;
    this.issuer = issuer;
    this.from = from;
  }

  @Transactional
  void signup(String displayName, String address, String password) {
    String email = Rules.email(address);
    String name = Rules.text(displayName, 100);
    String encoded = passwords.encode(Rules.password(password));
    // Concurrent requests for the same email must preserve one account and password.
    db.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", (r, n) -> 0, email);
    if (users.byEmail(email) != null) return;
    UUID id = UUID.randomUUID();
    db.update(
        "INSERT INTO iam_users(id,email,display_name,password_hash,administrator,email_verified)"
            + " VALUES(?,?,?,?,FALSE,FALSE)",
        id,
        email,
        name,
        encoded);
    sendVerification(users.byId(id));
    audit.record(email, "SIGNUP_REQUESTED", email, "Public account pending email verification");
  }

  @Transactional
  void resend(String address) {
    String email = Rules.email(address);
    db.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", (r, n) -> 0, email);
    var user = users.byEmail(email);
    if (user == null || !user.enabled() || user.emailVerified()) return;
    Long recent =
        db.queryForObject(
            "SELECT COUNT(*) FROM iam_verification_tokens WHERE user_id=? AND"
                + " created_at>CURRENT_TIMESTAMP-INTERVAL '1 minute'",
            Long.class,
            user.id());
    if (recent != null && recent > 0) return;
    sendVerification(user);
  }

  private void sendVerification(UserStore.Account user) {
    String token = Rules.random();
    db.update(
        "INSERT INTO iam_verification_tokens(token_hash,user_id,expires_at) VALUES(?,?,?) ON"
            + " CONFLICT(user_id) DO UPDATE SET"
            + " token_hash=EXCLUDED.token_hash,expires_at=EXCLUDED.expires_at,created_at=CURRENT_TIMESTAMP",
        Rules.hash(token),
        user.id(),
        Timestamp.from(Instant.now().plusSeconds(86400)));
    var message = new SimpleMailMessage();
    message.setFrom(from);
    message.setTo(user.email());
    message.setSubject("Verify your Gatehouse email");
    message.setText(
        "Verify your email within 24 hours using the password you chose during sign-up:\n"
            + issuer
            + "/verify?token="
            + token
            + "\n\nIf you did not request this account, you can ignore this email.");
    mail.send(message);
  }

  @Transactional
  void verify(String token, String password) {
    String hash = Rules.hash(Rules.text(token, 100));
    var candidates =
        db.queryForList(
            "SELECT user_id FROM iam_verification_tokens WHERE token_hash=? AND"
                + " expires_at>CURRENT_TIMESTAMP",
            UUID.class,
            hash);
    if (candidates.isEmpty())
      throw ApiException.bad("This verification link has expired or has already been used.");
    UUID id = candidates.getFirst();
    db.queryForMap("SELECT id FROM iam_users WHERE id=? FOR UPDATE", id);
    var user = users.byId(id);
    if (!user.enabled())
      throw ApiException.bad("This account is disabled. Contact your administrator.");
    if (password == null
        || password.length() > 100
        || !passwords.matches(password, user.passwordHash()))
      throw ApiException.bad("Enter the password you chose when creating this account.");
    var consumed =
        db.queryForList(
            "DELETE FROM iam_verification_tokens WHERE token_hash=? AND"
                + " expires_at>CURRENT_TIMESTAMP RETURNING user_id",
            UUID.class,
            hash);
    if (consumed.isEmpty())
      throw ApiException.bad("This verification link has expired or has already been used.");
    db.update(
        "UPDATE iam_users SET email_verified=TRUE,login_failures=0,locked_until=NULL WHERE id=?",
        id);
    users.revoke(user);
    audit.record(
        user.email(), "SIGNUP_VERIFIED", user.email(), "Email verified; account ready for sign-in");
  }
}

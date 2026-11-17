package dev.vijay.gatehouse;

import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PasswordService {
  private final JdbcTemplate db;
  private final UserStore users;
  private final PasswordEncoder passwords;
  private final MailSender mail;
  private final Audit audit;
  private final String issuer, from;

  PasswordService(
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
  void forgot(String email) {
    var user = users.byEmail(Rules.email(email));
    if (user == null || !user.enabled()) return;
    String token = Rules.random();
    db.update(
        "INSERT INTO iam_reset_tokens(token_hash,user_id,expires_at) VALUES(?,?,?)",
        Rules.hash(token),
        user.id(),
        java.sql.Timestamp.from(Instant.now().plusSeconds(1800)));
    var message = new SimpleMailMessage();
    message.setFrom(from);
    message.setTo(user.email());
    message.setSubject("Reset your Gatehouse password");
    message.setText(
        "Reset your password within 30 minutes by opening this link:\n"
            + issuer
            + "/reset?token="
            + token);
    mail.send(message);
    audit.record(user.email(), "PASSWORD_RESET_REQUESTED", user.email(), "Reset link sent");
  }

  @Transactional
  void reset(String token, String password) {
    String encoded = passwords.encode(Rules.password(password));
    var candidates =
        db.queryForList(
            "SELECT user_id FROM iam_reset_tokens WHERE token_hash=? AND"
                + " expires_at>CURRENT_TIMESTAMP",
            UUID.class,
            Rules.hash(Rules.text(token, 100)));
    if (candidates.isEmpty())
      throw ApiException.bad("This reset link has expired or has already been used.");
    db.queryForMap("SELECT id FROM iam_users WHERE id=? FOR UPDATE", candidates.getFirst());
    var ids =
        db.queryForList(
            "DELETE FROM iam_reset_tokens WHERE token_hash=? AND expires_at>CURRENT_TIMESTAMP"
                + " RETURNING user_id",
            UUID.class,
            Rules.hash(Rules.text(token, 100)));
    if (ids.isEmpty())
      throw ApiException.bad("This reset link has expired or has already been used.");
    var user = users.byId(ids.getFirst());
    if (!user.enabled())
      throw ApiException.bad("This account is disabled. Contact your administrator.");
    db.update(
        "UPDATE iam_users SET"
            + " password_hash=?,email_verified=TRUE,login_failures=0,locked_until=NULL WHERE id=?",
        encoded,
        user.id());
    db.update("DELETE FROM iam_reset_tokens WHERE user_id=?", user.id());
    db.update("DELETE FROM iam_verification_tokens WHERE user_id=?", user.id());
    users.revoke(user);
    audit.record(
        user.email(),
        "PASSWORD_RESET",
        user.email(),
        "Password changed; sessions and grants revoked");
  }

  @Transactional
  void change(Authentication auth, String oldPassword, String newPassword) {
    var current = users.current(auth);
    db.queryForMap("SELECT id FROM iam_users WHERE id=? FOR UPDATE", current.id());
    var user = users.byId(current.id());
    if (oldPassword == null
        || oldPassword.length() > 100
        || !passwords.matches(oldPassword, user.passwordHash()))
      throw ApiException.bad("The current password is incorrect.");
    String encoded = passwords.encode(Rules.password(newPassword));
    db.update("UPDATE iam_users SET password_hash=? WHERE id=?", encoded, user.id());
    db.update("DELETE FROM iam_reset_tokens WHERE user_id=?", user.id());
    db.update("DELETE FROM iam_verification_tokens WHERE user_id=?", user.id());
    users.revoke(user);
    audit.record(
        user.email(),
        "PASSWORD_CHANGED",
        user.email(),
        "Password changed; sessions and grants revoked");
  }
}

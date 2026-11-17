package dev.vijay.gatehouse;

import jakarta.servlet.http.HttpSession;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

@RestController
class ApiController {
  private final UserStore users;
  private final AdminService admin;
  private final ClientStore clients;
  private final PasswordService passwords;
  private final JdbcTemplate db;
  private final String issuer;
  private final RegistrationService registration;

  ApiController(
      UserStore users,
      AdminService admin,
      ClientStore clients,
      PasswordService passwords,
      JdbcTemplate db,
      @Value("${app.issuer}") String issuer,
      RegistrationService registration) {
    this.users = users;
    this.admin = admin;
    this.clients = clients;
    this.passwords = passwords;
    this.db = db;
    this.issuer = issuer;
    this.registration = registration;
  }

  record Signup(String displayName, String email, String password) {}

  record VerifyEmail(String token, String password) {}

  record Email(String email) {}

  record Reset(String token, String password) {}

  record Change(String currentPassword, String newPassword) {}

  record Enabled(boolean enabled) {}

  @PostMapping("/api/registration/signup")
  Object signup(@RequestBody Signup body) {
    registration.signup(body.displayName(), body.email(), body.password());
    return Map.of(
        "message",
        "If this email can be registered, a verification link is on its way. Check your inbox"
            + " before signing in.");
  }

  @PostMapping("/api/registration/resend")
  Object resendVerification(@RequestBody Email body) {
    registration.resend(body.email());
    return Map.of(
        "message",
        "If verification is pending, a link is on its way. Allow a minute before requesting"
            + " another.");
  }

  @PostMapping("/api/registration/verify")
  Object verifyRegistration(@RequestBody VerifyEmail body) {
    registration.verify(body.token(), body.password());
    return Map.of("message", "Email verified. Sign in with your password.");
  }

  @GetMapping("/api/health")
  Object health() {
    db.queryForObject("SELECT 1", Integer.class);
    return Map.of("status", "ok");
  }

  @GetMapping("/api/csrf")
  Object csrf(CsrfToken token) {
    return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
  }

  @GetMapping("/api/me")
  Object me(Authentication auth) {
    return users.view(users.current(auth));
  }

  @PostMapping("/api/password/forgot")
  Object forgot(@RequestBody Email body) {
    passwords.forgot(body.email());
    return Map.of("message", "If the account is available, a reset link is on its way.");
  }

  @PostMapping("/api/password/reset")
  Object reset(@RequestBody Reset body) {
    passwords.reset(body.token(), body.password());
    return Map.of("message", "Password updated. Sign in with your new password.");
  }

  @PostMapping("/api/account/password")
  Object change(Authentication auth, @RequestBody Change body) {
    passwords.change(auth, body.currentPassword(), body.newPassword());
    return Map.of("message", "Password changed. Please sign in again.");
  }

  @GetMapping("/api/account/sessions")
  Object accountSessions(Authentication auth, HttpSession session) {
    return admin.sessions(session.getId(), users.current(auth).email());
  }

  @DeleteMapping("/api/account/sessions/{id}")
  Object deleteOwnSession(Authentication auth, @PathVariable String id) {
    admin.deleteSession(auth, id, false);
    return Map.of("ok", true);
  }

  @GetMapping("/api/admin/overview")
  Object overview(Authentication auth) {
    users.admin(auth);
    return admin.overview();
  }

  @GetMapping("/api/admin/users")
  Object userList(
      Authentication auth,
      @RequestParam(defaultValue = "") String search,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "false") boolean administrators) {
    users.admin(auth);
    if (search.length() > 100 || page < 0 || page > 10000)
      throw ApiException.bad("Search is too long or page is invalid.");
    long total =
        db.queryForObject(
            "SELECT COUNT(*) FROM iam_users WHERE POSITION(LOWER(?) IN LOWER(email || ' ' ||"
                + " display_name))>0 AND (?=FALSE OR administrator=TRUE)",
            Long.class,
            search.trim(),
            administrators);
    return Map.of(
        "items",
        users.users(search, page, administrators),
        "total",
        total,
        "page",
        page,
        "pageSize",
        50);
  }

  @PostMapping("/api/admin/users")
  Object createUser(Authentication auth, @RequestBody AdminService.UserInput input) {
    return admin.createUser(auth, input);
  }

  @PutMapping("/api/admin/users/{id}")
  Object updateUser(
      Authentication auth, @PathVariable UUID id, @RequestBody AdminService.UserUpdate input) {
    return admin.updateUser(auth, id, input);
  }

  @DeleteMapping("/api/admin/users/{id}")
  Object deleteUser(Authentication auth, @PathVariable UUID id) {
    admin.deleteUser(auth, id);
    return Map.of("ok", true);
  }

  @PostMapping("/api/admin/users/{id}/unlock")
  Object unlock(Authentication auth, @PathVariable UUID id) {
    admin.unlock(auth, id);
    return Map.of("ok", true);
  }

  @PostMapping("/api/admin/users/{id}/revoke")
  Object revoke(Authentication auth, @PathVariable UUID id) {
    admin.revoke(auth, id);
    return Map.of("ok", true);
  }

  @PostMapping("/api/admin/users/{id}/reset-link")
  Object resetLink(Authentication auth, @PathVariable UUID id) {
    users.admin(auth);
    passwords.forgot(users.byId(id).email());
    return Map.of("message", "Password reset email sent.");
  }

  @GetMapping("/api/admin/roles")
  Object roles(Authentication auth) {
    users.admin(auth);
    return admin.roles();
  }

  @PostMapping("/api/admin/roles")
  Object createRole(Authentication auth, @RequestBody AdminService.RoleInput input) {
    return admin.createRole(auth, input);
  }

  @DeleteMapping("/api/admin/roles/{id}")
  Object deleteRole(Authentication auth, @PathVariable UUID id) {
    admin.deleteRole(auth, id);
    return Map.of("ok", true);
  }

  @GetMapping("/api/admin/clients")
  Object clients(Authentication auth) {
    users.admin(auth);
    return clients.list();
  }

  @PostMapping("/api/admin/clients")
  Object createClient(Authentication auth, @RequestBody ClientStore.Input input) {
    return admin.createClient(auth, input);
  }

  @PutMapping("/api/admin/clients/{id}/enabled")
  Object enableClient(Authentication auth, @PathVariable String id, @RequestBody Enabled input) {
    admin.enableClient(auth, id, input.enabled());
    return Map.of("ok", true);
  }

  @PostMapping("/api/admin/clients/{id}/rotate-secret")
  Object rotateSecret(Authentication auth, @PathVariable String id) {
    return admin.rotateClient(auth, id);
  }

  @GetMapping("/api/admin/sessions")
  Object sessions(Authentication auth, HttpSession session) {
    users.admin(auth);
    return admin.sessions(session.getId(), null);
  }

  @DeleteMapping("/api/admin/sessions/{id}")
  Object deleteSession(Authentication auth, @PathVariable String id) {
    admin.deleteSession(auth, id, true);
    return Map.of("ok", true);
  }

  @GetMapping("/api/admin/events")
  Object events(Authentication auth, @RequestParam(defaultValue = "") String kind) {
    users.admin(auth);
    return admin.events(kind);
  }

  @GetMapping("/api/admin/config")
  Object config(Authentication auth) {
    users.admin(auth);
    return Map.of(
        "issuer",
        issuer,
        "discovery",
        issuer + "/.well-known/openid-configuration",
        "jwks",
        issuer + "/oauth2/jwks",
        "accessTokenMinutes",
        5,
        "directory",
        "Primary directory");
  }

  @GetMapping("/api/resource/profile")
  Object resource(JwtAuthenticationToken auth) {
    return Map.of(
        "subject",
        auth.getToken().getSubject(),
        "scopes",
        auth.getToken().getClaimAsStringList("scope"),
        "roles",
        Optional.ofNullable(auth.getToken().getClaimAsStringList("roles")).orElse(List.of()),
        "message",
        "The API accepted your Gatehouse access token.");
  }

  @GetMapping("/api/resource/write")
  Object restricted(JwtAuthenticationToken auth) {
    List<String> roles = auth.getToken().getClaimAsStringList("roles");
    if (roles == null || !roles.contains("editor"))
      throw new ApiException(HttpStatus.FORBIDDEN, "The editor role is required.");
    return Map.of("message", "Editor access granted.", "subject", auth.getToken().getSubject());
  }
}

@Controller
class Pages {
  @GetMapping({"/login", "/signup", "/verify", "/reset", "/account", "/demo", "/demo/callback"})
  String app() {
    return "forward:/index.html";
  }
}

package dev.vijay.gatehouse;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HexFormat;
import org.springframework.jdbc.core.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

class AccountGuardFilter extends OncePerRequestFilter {
  private final UserStore users;

  AccountGuardFilter(UserStore users) {
    this.users = users;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    var session = request.getSession(false);
    if (auth != null && auth.isAuthenticated() && session != null) {
      var user = users.byEmail(auth.getName());
      Object version = session.getAttribute("accountVersion");
      if (user == null
          || !user.enabled()
          || !user.emailVerified()
          || !(version instanceof Long stored)
          || stored != user.version()) {
        session.invalidate();
        SecurityContextHolder.clearContext();
      }
    }
    chain.doFilter(request, response);
  }
}

class RequestLimitFilter extends OncePerRequestFilter {
  private final JdbcTemplate db;
  private final Audit audit;
  private final int maximum;

  RequestLimitFilter(JdbcTemplate db, Audit audit) {
    this.db = db;
    this.audit = audit;
    this.maximum =
        Integer.parseInt(System.getenv().getOrDefault("AUTH_REQUESTS_PER_MINUTE", "300"));
    if (maximum < 1) throw new IllegalStateException("AUTH_REQUESTS_PER_MINUTE must be positive.");
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return !request.getMethod().equals("POST")
        || !java.util.List.of(
                "/login",
                "/oauth2/token",
                "/api/password/forgot",
                "/api/password/reset",
                "/api/registration/signup",
                "/api/registration/resend",
                "/api/registration/verify")
            .contains(path);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String bucket = Rules.hash(request.getRemoteAddr() + ":" + request.getRequestURI());
    Integer hits =
        db.queryForObject(
            "INSERT INTO iam_rate_limits(bucket,window_start,hits) VALUES(?,CURRENT_TIMESTAMP,1) ON"
                + " CONFLICT(bucket) DO UPDATE SET hits=CASE WHEN"
                + " iam_rate_limits.window_start<CURRENT_TIMESTAMP-INTERVAL '1 minute' THEN 1 ELSE"
                + " iam_rate_limits.hits+1 END, window_start=CASE WHEN"
                + " iam_rate_limits.window_start<CURRENT_TIMESTAMP-INTERVAL '1 minute' THEN"
                + " CURRENT_TIMESTAMP ELSE iam_rate_limits.window_start END RETURNING hits",
            Integer.class,
            bucket);
    if (hits != null && hits > maximum) {
      response.setStatus(429);
      response.setContentType("application/json");
      response.setHeader("Retry-After", "60");
      response.getWriter().write("{\"error\":\"Too many requests. Try again in a minute.\"}");
      return;
    }
    chain.doFilter(request, response);
    if (request.getRequestURI().equals("/oauth2/token") && response.getStatus() == 200)
      audit.record(
          "oauth-client",
          "TOKEN_ISSUED",
          request.getParameter("grant_type"),
          "Token endpoint request succeeded");
  }
}

// A one-use authorization code or rotating refresh token must have only one winner,
// including when simultaneous exchanges reach different API nodes.
class ProtocolLockFilter extends OncePerRequestFilter {
  private final JdbcTemplate db;
  private final TransactionTemplate transaction;

  ProtocolLockFilter(JdbcTemplate db, TransactionTemplate transaction) {
    this.db = db;
    this.transaction = transaction;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().equals("/oauth2/token")
        || !request.getMethod().equals("POST")
        || !("authorization_code".equals(request.getParameter("grant_type"))
            || "refresh_token".equals(request.getParameter("grant_type")));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String raw =
        request.getParameter(
            "authorization_code".equals(request.getParameter("grant_type"))
                ? "code"
                : "refresh_token");
    if (raw == null || raw.length() > 2048) {
      chain.doFilter(request, response);
      return;
    }
    long lock = ByteBuffer.wrap(HexFormat.of().parseHex(Rules.hash(raw))).getLong();
    try {
      transaction.executeWithoutResult(
          status -> {
            db.execute(
                (ConnectionCallback<Void>)
                    connection -> {
                      try (var statement =
                          connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                        statement.setLong(1, lock);
                        statement.execute();
                      }
                      return null;
                    });
            try {
              chain.doFilter(request, response);
            } catch (IOException | ServletException e) {
              throw new ChainFailure(e);
            }
          });
    } catch (ChainFailure error) {
      if (error.getCause() instanceof IOException cause) throw cause;
      throw (ServletException) error.getCause();
    }
  }

  private static class ChainFailure extends RuntimeException {
    ChainFailure(Exception cause) {
      super(cause);
    }
  }
}

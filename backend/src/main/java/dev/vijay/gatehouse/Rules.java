package dev.vijay.gatehouse;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

final class Rules {
  private static final SecureRandom RANDOM = new SecureRandom();
  static final Set<String> SCOPES =
      Set.of("openid", "profile", "email", "roles", "api.read", "api.write");

  private Rules() {}

  static String text(String value, int maximum) {
    if (value == null
        || value.isBlank()
        || value.trim().length() > maximum
        || value.chars().anyMatch(Character::isISOControl))
      throw ApiException.bad("Please check the entered values.");
    return value.trim();
  }

  static String email(String value) {
    String email = text(value, 254).toLowerCase(Locale.ROOT);
    if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
      throw ApiException.bad("Enter a valid email address.");
    return email;
  }

  static String password(String value) {
    if (value == null || value.length() < 12 || value.getBytes(StandardCharsets.UTF_8).length > 72)
      throw ApiException.bad("Use at least 12 characters, with no more than 72 UTF-8 bytes.");
    return value;
  }

  static String roleName(String value) {
    String name = text(value, 40);
    if (!name.matches("[a-z][a-z0-9:_-]{1,39}"))
      throw ApiException.bad(
          "Use 2–40 lowercase letters, digits, hyphens, underscores, or colons for the role.");
    return name;
  }

  static String clientId(String value) {
    String id = text(value, 80);
    if (!id.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{1,79}"))
      throw ApiException.bad(
          "Use 2–80 letters, digits, dots, hyphens, or underscores for the client ID.");
    return id;
  }

  static String redirect(String value) {
    String text = text(value, 400);
    try {
      URI uri = URI.create(text);
      String host = uri.getHost();
      boolean loopback =
          List.of("localhost", "127.0.0.1", "[::1]", "::1")
              .contains(host == null ? "" : host.toLowerCase(Locale.ROOT));
      if (host == null
          || uri.getRawUserInfo() != null
          || uri.getRawFragment() != null
          || text.contains("*")
          || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && loopback)))
        throw new IllegalArgumentException();
      return uri.toString();
    } catch (IllegalArgumentException e) {
      throw ApiException.bad(
          "Use an exact HTTPS redirect URL, or HTTP on localhost for development.");
    }
  }

  static String origin(String value) {
    URI uri = URI.create(value);
    return uri.getScheme() + "://" + uri.getRawAuthority();
  }

  static String random() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }
}

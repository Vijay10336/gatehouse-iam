package dev.vijay.gatehouse;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class RulesTest {
  @Test
  void onlyExactHttpsOrLoopbackRedirectsAreAccepted() {
    assertEquals(
        "https://portal.example.com/callback",
        Rules.redirect("https://portal.example.com/callback"));
    assertEquals(
        "http://localhost:5173/callback", Rules.redirect("http://localhost:5173/callback"));
    assertEquals(
        "http://127.0.0.1:8080/callback", Rules.redirect("http://127.0.0.1:8080/callback"));
    for (String uri :
        new String[] {
          "http://portal.example.com/callback",
          "https://*.example.com/callback",
          "https://user:password@example.com/callback",
          "javascript:alert(1)",
          "https://example.com/callback#fragment",
          "http://localhost.evil.example/callback"
        }) assertThrows(ApiException.class, () -> Rules.redirect(uri));
  }

  @Test
  void passwordLimitUsesUtf8Bytes() {
    assertEquals("long-password-12", Rules.password("long-password-12"));
    assertThrows(ApiException.class, () -> Rules.password("short"));
    assertThrows(ApiException.class, () -> Rules.password("🙂".repeat(19)));
    assertEquals("🙂".repeat(12), Rules.password("🙂".repeat(12)));
  }

  @Test
  void directoryIdentifiersRejectMalformedInput() {
    assertEquals("billing:read", Rules.roleName("billing:read"));
    assertEquals("portal-client", Rules.clientId("portal-client"));
    assertThrows(ApiException.class, () -> Rules.roleName("Administrator Role"));
    assertThrows(ApiException.class, () -> Rules.clientId("portal client"));
    assertThrows(ApiException.class, () -> Rules.text("line\nbreak", 100));
  }

  @Test
  void emailIsNormalized() {
    assertEquals("alex@example.com", Rules.email("  ALEX@EXAMPLE.COM  "));
    assertThrows(ApiException.class, () -> Rules.email("not-an-email"));
    assertThrows(ApiException.class, () -> Rules.email("alex @example.com"));
  }

  @Test
  void signingKeysAreAuthenticatedAndEncrypted() throws Exception {
    String master = Base64.getEncoder().encodeToString(new byte[32]);
    var vault = new KeyVault(null, null, master);
    String encrypted = vault.encrypt("private-jwk-value");
    assertFalse(encrypted.contains("private-jwk-value"));
    assertEquals("private-jwk-value", vault.decrypt(encrypted));
    assertNotEquals(encrypted, vault.encrypt("private-jwk-value"));
    byte[] altered = Base64.getDecoder().decode(encrypted);
    altered[altered.length - 1] ^= 1;
    assertThrows(Exception.class, () -> vault.decrypt(Base64.getEncoder().encodeToString(altered)));
    assertThrows(IllegalStateException.class, () -> new KeyVault(null, null, "not-a-key"));
  }
}

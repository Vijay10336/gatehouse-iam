package dev.vijay.gatehouse;

import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

class BootstrapTest {
  @Test
  void changingBootstrapEmailAfterSetupCannotCreateAnotherAdministrator() {
    var db = mock(JdbcTemplate.class);
    var clients = mock(ClientStore.class);
    var passwords = mock(PasswordEncoder.class);
    when(db.queryForObject("SELECT initialized FROM iam_bootstrap WHERE id=1", Boolean.class))
        .thenReturn(true);
    when(db.queryForList(
            "SELECT id FROM oauth2_registered_client WHERE client_id='gatehouse-demo'"))
        .thenReturn(List.of(Map.of("id", "demo")));
    when(db.queryForList(
            "SELECT id FROM oauth2_registered_client WHERE client_id='gatehouse-service'"))
        .thenReturn(List.of(Map.of("id", "service")));
    new Bootstrap(
            db,
            passwords,
            clients,
            "different-admin@example.com",
            "unused-password-123",
            "unused-secret",
            "http://localhost:18100")
        .run(mock(ApplicationArguments.class));
    verifyNoInteractions(passwords);
    verifyNoInteractions(clients);
  }
}

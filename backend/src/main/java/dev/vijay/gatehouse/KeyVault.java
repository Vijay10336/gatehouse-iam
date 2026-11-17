package dev.vijay.gatehouse;

import com.nimbusds.jose.jwk.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class KeyVault {
  private final byte[] master;
  private final JdbcTemplate db;
  private final TransactionTemplate transaction;

  KeyVault(
      JdbcTemplate db,
      TransactionTemplate transaction,
      @Value("${app.key-encryption-secret}") String master) {
    this.db = db;
    this.transaction = transaction;
    try {
      this.master = Base64.getDecoder().decode(master);
    } catch (Exception e) {
      throw new IllegalStateException(
          "KEY_ENCRYPTION_SECRET must be a base64-encoded 32-byte key.");
    }
    if (this.master.length != 32)
      throw new IllegalStateException("KEY_ENCRYPTION_SECRET must decode to 32 bytes.");
  }

  RSAKey load() {
    return transaction.execute(
        status -> {
          db.queryForMap("SELECT id FROM iam_lock WHERE id=1 FOR UPDATE");
          var rows =
              db.queryForList(
                  "SELECT encrypted_jwk FROM iam_signing_keys WHERE id='primary'", String.class);
          try {
            if (!rows.isEmpty()) return RSAKey.parse(decrypt(rows.getFirst()));
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(3072);
            var pair = generator.generateKeyPair();
            var key =
                new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID(UUID.randomUUID().toString())
                    .build();
            db.update(
                "INSERT INTO iam_signing_keys(id,encrypted_jwk) VALUES('primary',?)",
                encrypt(key.toJSONString()));
            return key;
          } catch (Exception e) {
            throw new IllegalStateException(
                "Cannot load signing key. Preserve the original encryption secret with database"
                    + " backups.",
                e);
          }
        });
  }

  String encrypt(String value) throws Exception {
    byte[] iv = new byte[12];
    new SecureRandom().nextBytes(iv);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.ENCRYPT_MODE, new SecretKeySpec(master, "AES"), new GCMParameterSpec(128, iv));
    byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
    return Base64.getEncoder()
        .encodeToString(ByteBuffer.allocate(12 + encrypted.length).put(iv).put(encrypted).array());
  }

  String decrypt(String value) throws Exception {
    byte[] encrypted = Base64.getDecoder().decode(value);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.DECRYPT_MODE,
        new SecretKeySpec(master, "AES"),
        new GCMParameterSpec(128, Arrays.copyOf(encrypted, 12)));
    return new String(
        cipher.doFinal(Arrays.copyOfRange(encrypted, 12, encrypted.length)),
        StandardCharsets.UTF_8);
  }
}

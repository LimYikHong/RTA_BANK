package rta.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * FileEncryptionService — Encrypts batch files using AES-256-GCM with the AES
 * session key encrypted using the merchant's RSA public key.
 *
 * <p>
 * This is the counterpart of {@link FileDecryptionService} and would normally
 * live on the merchant upload system. It is included here so that:
 * <ul>
 * <li>The upload endpoint can auto-encrypt files for merchants</li>
 * <li>Integration tests can verify the full encrypt→decrypt round-trip</li>
 * </ul>
 *
 * <h3>Encrypted file layout (binary):</h3>
 * <pre>
 *   [4 bytes]  encKeyLen           — length of RSA-encrypted AES key
 *   [N bytes]  encryptedAesKey     — AES-256 key encrypted with merchant's RSA public key
 *   [12 bytes] iv                  — AES-GCM initialisation vector
 *   [remaining] cipherText         — AES-256-GCM encrypted file content (includes 16-byte auth tag)
 * </pre>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FileEncryptionService {

    private final RsaKeyService rsaKeyService;

    private static final String RSA_ALGORITHM = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    private static final String AES_ALGORITHM = "AES/GCM/NoPadding";
    private static final int AES_KEY_BITS = 256;
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;

    /**
     * Encrypt a batch file for the given merchant using AES+RSA hybrid
     * encryption.
     *
     * @param merchantId the merchant whose RSA public key is used
     * @param plainBytes the raw file content
     * @return encrypted payload (see layout above)
     * @throws GeneralSecurityException if any crypto operation fails
     * @throws IOException if writing the byte stream fails
     */
    public byte[] encryptFile(String merchantId, byte[] plainBytes)
            throws GeneralSecurityException, IOException {

        // 1. Get the OUTBOUND RSA public key (bank uses to encrypt return files for merchant)
        String publicKeyPem = rsaKeyService.getActiveOutboundPublicKey(merchantId)
                .orElseThrow(() -> new IllegalStateException(
                "No active OUTBOUND RSA public key found for merchantId=" + merchantId
                + ". Please generate RSA keys for this merchant first."));

        PublicKey publicKey = parsePublicKey(publicKeyPem);

        // 2. Generate a random AES-256 session key
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(AES_KEY_BITS);
        SecretKey aesKey = keyGen.generateKey();

        // 3. Generate a random IV
        byte[] iv = new byte[GCM_IV_BYTES];
        new SecureRandom().nextBytes(iv);

        // 4. Encrypt the file content with AES-GCM
        Cipher aesCipher = Cipher.getInstance(AES_ALGORITHM);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_BITS, iv);
        aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, gcmSpec);
        byte[] cipherText = aesCipher.doFinal(plainBytes);

        // 5. Encrypt the AES key with RSA public key
        Cipher rsaCipher = Cipher.getInstance(RSA_ALGORITHM);
        rsaCipher.init(Cipher.ENCRYPT_MODE, publicKey);
        byte[] encryptedAesKey = rsaCipher.doFinal(aesKey.getEncoded());

        // 6. Assemble the encrypted payload
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer header = ByteBuffer.allocate(4);
        header.putInt(encryptedAesKey.length);
        out.write(header.array());
        out.write(encryptedAesKey);
        out.write(iv);
        out.write(cipherText);

        byte[] payload = out.toByteArray();
        log.info("File encrypted for merchantId={}, plainSize={}, encryptedSize={}",
                merchantId, plainBytes.length, payload.length);

        return payload;
    }

    /**
     * Encrypt a file using a provided RSA public key PEM (no DB lookup). Useful
     * for merchant-side systems that have the public key locally.
     */
    public byte[] encryptFileWithKey(String publicKeyPem, byte[] plainBytes)
            throws GeneralSecurityException, IOException {

        PublicKey publicKey = parsePublicKey(publicKeyPem);

        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(AES_KEY_BITS);
        SecretKey aesKey = keyGen.generateKey();

        byte[] iv = new byte[GCM_IV_BYTES];
        new SecureRandom().nextBytes(iv);

        Cipher aesCipher = Cipher.getInstance(AES_ALGORITHM);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_BITS, iv);
        aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, gcmSpec);
        byte[] cipherText = aesCipher.doFinal(plainBytes);

        Cipher rsaCipher = Cipher.getInstance(RSA_ALGORITHM);
        rsaCipher.init(Cipher.ENCRYPT_MODE, publicKey);
        byte[] encryptedAesKey = rsaCipher.doFinal(aesKey.getEncoded());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer header = ByteBuffer.allocate(4);
        header.putInt(encryptedAesKey.length);
        out.write(header.array());
        out.write(encryptedAesKey);
        out.write(iv);
        out.write(cipherText);

        return out.toByteArray();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------
    /**
     * Parse a PEM-encoded X.509 public key into a {@link PublicKey} object.
     */
    private PublicKey parsePublicKey(String pem) throws GeneralSecurityException {
        String base64 = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] der = Base64.getDecoder().decode(base64);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(der);
        return KeyFactory.getInstance("RSA").generatePublic(spec);
    }
}

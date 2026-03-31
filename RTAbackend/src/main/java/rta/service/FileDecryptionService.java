package rta.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import rta.entity.MerchantKey;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * FileDecryptionService — Decrypts files that were encrypted by the merchant
 * upload system using the AES+RSA hybrid scheme.
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
public class FileDecryptionService {

    private final RsaKeyService rsaKeyService;

    private static final String RSA_ALGORITHM = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    private static final String AES_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;

    /**
     * Decrypt an encrypted batch file.
     *
     * @param merchantId the merchant who encrypted this file
     * @param encryptedBytes the full encrypted payload (see layout above)
     * @return the decrypted (plain) file bytes
     * @throws GeneralSecurityException if any crypto operation fails
     * @throws IOException if reading the byte stream fails
     */
    public byte[] decryptFile(String merchantId, byte[] encryptedBytes)
            throws GeneralSecurityException, IOException {

        // 1. Look up the merchant's active RSA private key
        MerchantKey merchantKey = rsaKeyService.getActiveKey(merchantId)
                .orElseThrow(() -> new IllegalStateException(
                "No active RSA key found for merchantId=" + merchantId));

        PrivateKey privateKey = parsePrivateKey(merchantKey.getPrivateKeyPem());

        // 2. Parse the encrypted payload
        ByteBuffer buf = ByteBuffer.wrap(encryptedBytes);

        if (buf.remaining() < 4) {
            throw new IllegalStateException("Encrypted payload too short: " + encryptedBytes.length + " bytes");
        }

        int encKeyLen = buf.getInt();
        log.info("Decrypting file for merchantId={}: totalSize={}, encKeyLen={}",
                merchantId, encryptedBytes.length, encKeyLen);

        if (encKeyLen <= 0 || encKeyLen > buf.remaining()) {
            throw new IllegalStateException(
                    "Invalid encKeyLen=" + encKeyLen + " (remaining=" + buf.remaining()
                    + "). File may not be in the expected packed binary format.");
        }

        byte[] encryptedAesKey = new byte[encKeyLen];
        buf.get(encryptedAesKey);

        if (buf.remaining() < GCM_IV_BYTES) {
            throw new IllegalStateException(
                    "Not enough bytes for IV after reading AES key. remaining=" + buf.remaining());
        }

        byte[] iv = new byte[GCM_IV_BYTES];
        buf.get(iv);

        byte[] cipherText = new byte[buf.remaining()];
        buf.get(cipherText);

        log.info("Parsed payload: encAesKey={} bytes, iv={} bytes, cipherText={} bytes",
                encryptedAesKey.length, iv.length, cipherText.length);

        // 3. Decrypt the AES key using RSA private key
        SecretKey aesKey;
        try {
            aesKey = decryptAesKey(privateKey, encryptedAesKey);
        } catch (Exception ex) {
            throw new GeneralSecurityException(
                    "RSA decryption of AES key failed (key mismatch?): " + ex.getClass().getSimpleName(), ex);
        }

        // 4. Decrypt the file content using AES-GCM
        byte[] plainBytes;
        try {
            plainBytes = decryptContent(aesKey, iv, cipherText);
        } catch (Exception ex) {
            throw new GeneralSecurityException(
                    "AES-GCM decryption failed (corrupted data?): " + ex.getClass().getSimpleName(), ex);
        }

        log.info("File decrypted successfully for merchantId={}, plainSize={} bytes",
                merchantId, plainBytes.length);

        return plainBytes;
    }

    /**
     * Decrypt an encrypted file from an InputStream.
     */
    public InputStream decryptFile(String merchantId, InputStream encryptedStream)
            throws GeneralSecurityException, IOException {
        byte[] encryptedBytes = encryptedStream.readAllBytes();
        byte[] plainBytes = decryptFile(merchantId, encryptedBytes);
        return new ByteArrayInputStream(plainBytes);
    }

    // -----------------------------------------------------------------------
    // Internal crypto operations
    // -----------------------------------------------------------------------
    /**
     * Decrypt the AES-256 key using the merchant's RSA private key.
     */
    private SecretKey decryptAesKey(PrivateKey privateKey, byte[] encryptedAesKey)
            throws GeneralSecurityException {
        Cipher rsaCipher = Cipher.getInstance(RSA_ALGORITHM);
        rsaCipher.init(Cipher.DECRYPT_MODE, privateKey);
        byte[] aesKeyBytes = rsaCipher.doFinal(encryptedAesKey);
        return new SecretKeySpec(aesKeyBytes, "AES");
    }

    /**
     * Decrypt file content using AES-256-GCM.
     */
    private byte[] decryptContent(SecretKey aesKey, byte[] iv, byte[] cipherText)
            throws GeneralSecurityException {
        Cipher aesCipher = Cipher.getInstance(AES_ALGORITHM);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_BITS, iv);
        aesCipher.init(Cipher.DECRYPT_MODE, aesKey, gcmSpec);
        return aesCipher.doFinal(cipherText);
    }

    /**
     * Parse a PEM-encoded PKCS#8 private key into a {@link PrivateKey} object.
     */
    private PrivateKey parsePrivateKey(String pem) throws GeneralSecurityException {
        String base64 = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] der = Base64.getDecoder().decode(base64);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        return KeyFactory.getInstance("RSA").generatePrivate(spec);
    }
}

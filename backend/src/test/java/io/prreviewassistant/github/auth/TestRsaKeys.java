package io.prreviewassistant.github.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.pkcs.RSAPrivateKey;

final class TestRsaKeys {

    private TestRsaKeys() {
    }

    static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("RSA must be available in the JDK", exception);
        }
    }

    static Path writePkcs8(Path path, KeyPair keyPair) throws IOException {
        return writePem(path, "PRIVATE KEY", keyPair.getPrivate().getEncoded());
    }

    static Path writePkcs1(Path path, KeyPair keyPair) throws IOException {
        PrivateKeyInfo keyInfo = PrivateKeyInfo.getInstance(keyPair.getPrivate().getEncoded());
        RSAPrivateKey rsaPrivateKey = RSAPrivateKey.getInstance(keyInfo.parsePrivateKey());
        return writePem(path, "RSA PRIVATE KEY", rsaPrivateKey.getEncoded());
    }

    private static Path writePem(Path path, String label, byte[] encoded) throws IOException {
        String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded);
        String pem = "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n";
        return Files.writeString(path, pem, StandardCharsets.US_ASCII);
    }
}

package io.prreviewassistant.github.auth;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.util.Arrays;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

public final class PemPrivateKeyLoader {
    static final int MAX_PEM_BYTES = 64 * 1024;

    public RSAPrivateKey load(Path path) {
        if (path == null || !Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw GitHubException.invalidConfiguration();
        }

        byte[] pemBytes = null;
        try {
            try (var input = Files.newInputStream(path)) {
                pemBytes = input.readNBytes(MAX_PEM_BYTES + 1);
            }
            if (pemBytes.length == 0 || pemBytes.length > MAX_PEM_BYTES) {
                throw GitHubException.invalidConfiguration();
            }
            try (Reader reader = new InputStreamReader(new ByteArrayInputStream(pemBytes), StandardCharsets.US_ASCII);
                PEMParser parser = new PEMParser(reader)) {
                Object pemObject = parser.readObject();
                PrivateKey privateKey = convert(pemObject);
                if (parser.readObject() != null || !(privateKey instanceof RSAPrivateKey rsaPrivateKey)) {
                    throw GitHubException.invalidConfiguration();
                }
                return rsaPrivateKey;
            }
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof GitHubException githubException) {
                throw githubException;
            }
            throw GitHubException.invalidConfiguration();
        } finally {
            if (pemBytes != null) {
                Arrays.fill(pemBytes, (byte) 0);
            }
        }
    }

    private PrivateKey convert(Object pemObject) throws IOException {
        JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
        if (pemObject instanceof PEMKeyPair keyPair) {
            return converter.getKeyPair(keyPair).getPrivate();
        }
        if (pemObject instanceof PrivateKeyInfo privateKeyInfo) {
            return converter.getPrivateKey(privateKeyInfo);
        }
        throw GitHubException.invalidConfiguration();
    }
}

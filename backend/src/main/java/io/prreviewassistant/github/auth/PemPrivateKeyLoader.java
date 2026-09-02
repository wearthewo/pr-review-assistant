package io.prreviewassistant.github.auth;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateKey;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

public final class PemPrivateKeyLoader {

    public RSAPrivateKey load(Path path) {
        if (path == null || !Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw GitHubException.invalidConfiguration();
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.US_ASCII);
                PEMParser parser = new PEMParser(reader)) {
            Object pemObject = parser.readObject();
            PrivateKey privateKey = convert(pemObject);
            if (parser.readObject() != null || !(privateKey instanceof RSAPrivateKey rsaPrivateKey)) {
                throw GitHubException.invalidConfiguration();
            }
            return rsaPrivateKey;
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof GitHubException githubException) {
                throw githubException;
            }
            throw GitHubException.invalidConfiguration();
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

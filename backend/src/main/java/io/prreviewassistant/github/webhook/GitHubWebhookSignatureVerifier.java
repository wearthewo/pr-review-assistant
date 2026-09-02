package io.prreviewassistant.github.webhook;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

@Component
public final class GitHubWebhookSignatureVerifier {

    private static final String HEADER_PREFIX = "sha256=";
    private static final int SHA_256_HEX_LENGTH = 64;
    private final byte[] secret;

    public GitHubWebhookSignatureVerifier(GitHubWebhookProperties properties) {
        this.secret = properties.secretBytes();
    }

    public boolean isValid(byte[] rawBody, String signatureHeader) {
        if (signatureHeader == null
                || !signatureHeader.startsWith(HEADER_PREFIX)
                || signatureHeader.length() != HEADER_PREFIX.length() + SHA_256_HEX_LENGTH) {
            return false;
        }

        byte[] supplied;
        try {
            supplied = HexFormat.of().parseHex(signatureHeader, HEADER_PREFIX.length(), signatureHeader.length());
        } catch (IllegalArgumentException exception) {
            return false;
        }

        return MessageDigest.isEqual(hmac(rawBody), supplied);
    }

    private byte[] hmac(byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(rawBody);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }
}

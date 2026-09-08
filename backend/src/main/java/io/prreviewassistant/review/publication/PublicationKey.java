package io.prreviewassistant.review.publication;

import io.prreviewassistant.review.analysis.ReviewFinding;
import io.prreviewassistant.review.job.ReviewTarget;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

public final class PublicationKey {
    private PublicationKey() { }

    public static String create(ReviewTarget target, int version, List<ReviewFinding> findings) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, Long.toString(target.repositoryId()));
            update(digest, Integer.toString(target.pullRequestNumber()));
            update(digest, target.headSha());
            update(digest, Integer.toString(version));
            for (ReviewFinding finding : findings) update(digest, finding.id());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }
}

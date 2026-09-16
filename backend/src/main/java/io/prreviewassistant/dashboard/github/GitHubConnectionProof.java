package io.prreviewassistant.dashboard.github;

import java.util.List;

public record GitHubConnectionProof(long userId, List<AccessibleInstallation> installations) {
    public GitHubConnectionProof {
        if (userId <= 0 || installations == null) {
            throw new IllegalArgumentException("verified GitHub proof is invalid");
        }
        installations = List.copyOf(installations);
    }

    public record AccessibleInstallation(long installationId, long accountId, String accountType, String targetType) {
        public AccessibleInstallation {
            if (installationId <= 0 || accountId <= 0 || accountType == null || targetType == null) {
                throw new IllegalArgumentException("GitHub installation proof is invalid");
            }
        }

        public boolean isOwnedUserInstallation(long verifiedUserId) {
            return accountId == verifiedUserId
                    && "User".equalsIgnoreCase(accountType)
                    && "User".equalsIgnoreCase(targetType);
        }
    }
}

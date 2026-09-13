package io.prreviewassistant.tenant;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcTenantOwnershipStore implements TenantOwnershipStore {
    private final JdbcClient jdbc;

    public JdbcTenantOwnershipStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(noRollbackFor = TenantOwnershipException.class)
    public TenantContext provision(long githubInstallationId, long githubRepositoryId, Instant now) {
        validate(githubInstallationId, githubRepositoryId, now);
        advisoryLock(githubInstallationId);
        advisoryLock(githubRepositoryId ^ Long.MIN_VALUE);
        Optional<InstallationRow> existingInstallation = findInstallation(githubInstallationId);
        Optional<RepositoryRow> existingRepository = findRepository(githubRepositoryId);
        if (existingRepository.isPresent()) {
            if (existingInstallation.isEmpty()
                    || !sameOwner(existingInstallation.orElseThrow(), existingRepository.orElseThrow())) {
                throw new TenantOwnershipException(TenantOwnershipError.TENANT_REPOSITORY_OWNERSHIP_MISMATCH);
            }
            return context(existingInstallation.orElseThrow(), existingRepository.orElseThrow());
        }

        InstallationRow installation = existingInstallation.orElseGet(() -> {
            UUID tenantId = UUID.randomUUID();
            UUID installationId = UUID.randomUUID();
            OffsetDateTime timestamp = utc(now);
            jdbc.sql("INSERT INTO tenants (id, created_at, updated_at) VALUES (:id, :now, :now)")
                    .param("id", tenantId).param("now", timestamp).update();
            jdbc.sql("""
                    INSERT INTO github_installations
                      (id, tenant_id, github_installation_id, created_at, updated_at)
                    VALUES (:id, :tenantId, :githubInstallationId, :now, :now)
                    """).param("id", installationId).param("tenantId", tenantId)
                    .param("githubInstallationId", githubInstallationId).param("now", timestamp).update();
            return new InstallationRow(installationId, tenantId, githubInstallationId);
        });

        UUID repositoryId = UUID.randomUUID();
        OffsetDateTime timestamp = utc(now);
        jdbc.sql("""
                INSERT INTO tenant_repositories
                  (id, tenant_id, installation_id, github_repository_id, created_at, updated_at)
                VALUES (:id, :tenantId, :installationId, :githubRepositoryId, :now, :now)
                """).param("id", repositoryId).param("tenantId", installation.tenantId())
                .param("installationId", installation.id())
                .param("githubRepositoryId", githubRepositoryId).param("now", timestamp).update();
        RepositoryRow repository = new RepositoryRow(
                repositoryId, installation.tenantId(), installation.id(), githubRepositoryId);
        return context(installation, repository);
    }

    @Override
    public Optional<TenantContext> resolve(long githubInstallationId, long githubRepositoryId) {
        if (githubInstallationId <= 0 || githubRepositoryId <= 0) {
            return Optional.empty();
        }
        return jdbc.sql("""
                SELECT installation.id AS installation_id, installation.tenant_id,
                       installation.github_installation_id,
                       repository.id AS repository_id, repository.github_repository_id
                FROM github_installations installation
                JOIN tenant_repositories repository
                  ON repository.installation_id = installation.id
                 AND repository.tenant_id = installation.tenant_id
                WHERE installation.github_installation_id = :githubInstallationId
                  AND repository.github_repository_id = :githubRepositoryId
                """).param("githubInstallationId", githubInstallationId)
                .param("githubRepositoryId", githubRepositoryId)
                .query((resultSet, row) -> new TenantContext(
                        resultSet.getObject("tenant_id", UUID.class),
                        resultSet.getObject("installation_id", UUID.class),
                        resultSet.getObject("repository_id", UUID.class),
                        resultSet.getLong("github_installation_id"),
                        resultSet.getLong("github_repository_id")))
                .optional();
    }

    private Optional<InstallationRow> findInstallation(long githubInstallationId) {
        return jdbc.sql("""
                SELECT id, tenant_id, github_installation_id
                FROM github_installations WHERE github_installation_id = :externalId
                """).param("externalId", githubInstallationId)
                .query((resultSet, row) -> new InstallationRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("tenant_id", UUID.class),
                        resultSet.getLong("github_installation_id")))
                .optional();
    }

    private Optional<RepositoryRow> findRepository(long githubRepositoryId) {
        return jdbc.sql("""
                SELECT id, tenant_id, installation_id, github_repository_id
                FROM tenant_repositories WHERE github_repository_id = :externalId
                """).param("externalId", githubRepositoryId)
                .query((resultSet, row) -> new RepositoryRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("tenant_id", UUID.class),
                        resultSet.getObject("installation_id", UUID.class),
                        resultSet.getLong("github_repository_id")))
                .optional();
    }

    private TenantContext context(InstallationRow installation, RepositoryRow repository) {
        return new TenantContext(installation.tenantId(), installation.id(), repository.id(),
                installation.githubInstallationId(), repository.githubRepositoryId());
    }

    private boolean sameOwner(InstallationRow installation, RepositoryRow repository) {
        return repository.tenantId().equals(installation.tenantId())
                && repository.installationId().equals(installation.id());
    }

    private void advisoryLock(long key) {
        jdbc.sql("SELECT pg_advisory_xact_lock(:key)").param("key", key)
                .query((resultSet, row) -> Boolean.TRUE).single();
    }

    private void validate(long installationId, long repositoryId, Instant now) {
        if (installationId <= 0 || repositoryId <= 0 || now == null) {
            throw new IllegalArgumentException("authoritative ownership identity is invalid");
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private record InstallationRow(UUID id, UUID tenantId, long githubInstallationId) { }
    private record RepositoryRow(UUID id, UUID tenantId, UUID installationId, long githubRepositoryId) { }
}

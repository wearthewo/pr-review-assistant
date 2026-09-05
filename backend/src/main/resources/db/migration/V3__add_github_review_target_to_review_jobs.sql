ALTER TABLE review_jobs
    ADD COLUMN github_installation_id BIGINT,
    ADD COLUMN github_repository_id BIGINT,
    ADD COLUMN github_pull_request_number INTEGER,
    ADD COLUMN github_head_sha VARCHAR(64),
    ADD CONSTRAINT ck_review_jobs_review_target_shape CHECK (
        (github_installation_id IS NULL
            AND github_repository_id IS NULL
            AND github_pull_request_number IS NULL
            AND github_head_sha IS NULL)
        OR
        (github_installation_id IS NOT NULL
            AND github_repository_id IS NOT NULL
            AND github_pull_request_number IS NOT NULL
            AND github_head_sha IS NOT NULL
            AND github_installation_id > 0
            AND github_repository_id > 0
            AND github_pull_request_number > 0
            AND github_head_sha ~ '^[0-9a-f]{40,64}$')
    ),
    ADD CONSTRAINT uq_review_jobs_github_review_target UNIQUE (
        github_installation_id,
        github_repository_id,
        github_pull_request_number,
        github_head_sha
    );

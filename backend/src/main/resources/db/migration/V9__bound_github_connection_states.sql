CREATE INDEX ix_github_connection_states_active_user
    ON github_connection_states (application_user_id, created_at DESC, state_hash DESC)
    WHERE consumed_at IS NULL;

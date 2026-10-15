-- Existing accounts retain access. Only new defender registrations are pending.
ALTER TABLE staff_user ADD COLUMN status varchar(30) NOT NULL DEFAULT 'ACTIVE'
    CHECK (status IN ('ACTIVE', 'PENDING_ACTIVATION'));
CREATE TABLE account_invitation (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES staff_user(id),
    token_hash char(64) NOT NULL UNIQUE,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    used_at timestamptz,
    revoked_at timestamptz,
    CHECK (expires_at > created_at),
    CHECK (used_at IS NULL OR revoked_at IS NULL)
);
CREATE UNIQUE INDEX one_open_invitation ON account_invitation(account_id)
    WHERE used_at IS NULL AND revoked_at IS NULL;

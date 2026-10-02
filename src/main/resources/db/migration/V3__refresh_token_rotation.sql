-- Distinguish a refresh token that was replaced by rotation (replaying it signals theft and kills the
-- whole session family) from one revoked by logout, password change or deactivation (replaying it is
-- just an expired session and must not sign out the user's other devices).
ALTER TABLE refresh_tokens ADD COLUMN rotated BOOLEAN NOT NULL DEFAULT FALSE;

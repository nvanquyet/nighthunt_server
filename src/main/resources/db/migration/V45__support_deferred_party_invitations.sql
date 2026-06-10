-- Solo players may send a party invitation without becoming party host first.
-- The party is created only when the invitation is accepted.
ALTER TABLE party_invitations
    DROP FOREIGN KEY fk_party_invitations_party_id;

ALTER TABLE party_invitations
    MODIFY COLUMN party_id BIGINT NULL;

ALTER TABLE party_invitations
    ADD CONSTRAINT fk_party_invitations_party_id
        FOREIGN KEY (party_id) REFERENCES parties(id) ON DELETE CASCADE;

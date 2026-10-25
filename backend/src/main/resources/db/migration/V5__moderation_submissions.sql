CREATE TABLE hackatlas.moderation_submission (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES hackatlas.user_account(id) ON DELETE CASCADE,
    hackathon_id uuid REFERENCES hackatlas.catalog_hackathon(id),
    kind varchar(10) NOT NULL CHECK (kind IN ('SUGGESTION', 'REPORT')),
    url text,
    comment varchar(2000),
    reason varchar(12) CHECK (reason IN ('DATES', 'LINK', 'PRIZE', 'ELIGIBILITY', 'DUPLICATE', 'OTHER')),
    message varchar(2000),
    status varchar(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'RESOLVED', 'DISMISSED')),
    created_at timestamptz NOT NULL,
    reviewed_at timestamptz,
    CHECK ((kind = 'SUGGESTION' AND url IS NOT NULL AND hackathon_id IS NULL AND reason IS NULL AND message IS NULL)
        OR (kind = 'REPORT' AND hackathon_id IS NOT NULL AND url IS NULL AND comment IS NULL
            AND reason IS NOT NULL AND message IS NOT NULL AND char_length(message) BETWEEN 5 AND 2000)),
    CHECK ((status = 'PENDING' AND reviewed_at IS NULL) OR (status <> 'PENDING' AND reviewed_at IS NOT NULL))
);
CREATE INDEX moderation_pending_queue_idx ON hackatlas.moderation_submission(created_at, id) WHERE status = 'PENDING';
CREATE INDEX moderation_submission_user_idx ON hackatlas.moderation_submission(user_id);

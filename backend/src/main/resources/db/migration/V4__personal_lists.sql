CREATE TABLE hackatlas.user_interest (
    user_id uuid NOT NULL REFERENCES hackatlas.user_account(id) ON DELETE CASCADE,
    tag_id varchar(50) NOT NULL REFERENCES hackatlas.catalog_tag(id),
    PRIMARY KEY (user_id, tag_id)
);

CREATE TABLE hackatlas.saved_hackathon (
    user_id uuid NOT NULL REFERENCES hackatlas.user_account(id) ON DELETE CASCADE,
    hackathon_id uuid NOT NULL REFERENCES hackatlas.catalog_hackathon(id),
    saved_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, hackathon_id)
);
CREATE INDEX saved_hackathon_page_idx
    ON hackatlas.saved_hackathon(user_id, saved_at DESC, hackathon_id ASC);

CREATE TABLE hackatlas.application_mark (
    user_id uuid NOT NULL REFERENCES hackatlas.user_account(id) ON DELETE CASCADE,
    hackathon_id uuid NOT NULL REFERENCES hackatlas.catalog_hackathon(id),
    marked_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, hackathon_id)
);
CREATE INDEX application_mark_page_idx
    ON hackatlas.application_mark(user_id, marked_at DESC, hackathon_id ASC);

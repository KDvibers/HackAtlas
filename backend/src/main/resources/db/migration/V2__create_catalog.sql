-- B1 catalogue only. Production migrations deliberately contain no event/demo data.
CREATE TABLE hackatlas.catalog_tag (
    id varchar(50) PRIMARY KEY,
    label varchar(80) NOT NULL,
    interest_allowed boolean NOT NULL DEFAULT true
);

CREATE TABLE hackatlas.catalog_hackathon (
    id uuid PRIMARY KEY,
    slug varchar(200) NOT NULL UNIQUE,
    title varchar(200) NOT NULL,
    short_description varchar(350) NOT NULL,
    organizer_name varchar(160) NOT NULL,
    organizer_website_url text,
    format varchar(10) NOT NULL CHECK (format IN ('ONLINE', 'OFFLINE', 'HYBRID')),
    country_code varchar(2),
    city varchar(100),
    venue varchar(300),
    starts_precision varchar(6),
    starts_date date,
    starts_time time,
    starts_time_zone text,
    starts_instant timestamptz,
    ends_precision varchar(6),
    ends_date date,
    ends_time time,
    ends_time_zone text,
    ends_instant timestamptz,
    deadline_precision varchar(6),
    deadline_date date,
    deadline_time time,
    deadline_time_zone text,
    deadline_instant timestamptz,
    registration_status varchar(10) NOT NULL
        CHECK (registration_status IN ('OPEN', 'CLOSED', 'UPCOMING', 'UNKNOWN')),
    event_status varchar(10) NOT NULL
        CHECK (event_status IN ('SCHEDULED', 'ONGOING', 'ENDED', 'POSTPONED', 'CANCELLED')),
    publication_status varchar(10) NOT NULL DEFAULT 'DRAFT'
        CHECK (publication_status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    published_at timestamptz,
    prize_status varchar(10) NOT NULL CHECK (prize_status IN ('ANNOUNCED', 'NONE', 'UNKNOWN')),
    prize_total_amount numeric CHECK (prize_total_amount >= 0 AND scale(prize_total_amount) <= 6),
    prize_currency varchar(3),
    prize_kind varchar(12) NOT NULL CHECK (prize_kind IN ('CASH', 'NON_CASH', 'MIXED', 'UNSPECIFIED')),
    prize_summary varchar(500),
    team_min_size integer CHECK (team_min_size BETWEEN 1 AND 100),
    team_max_size integer CHECK (team_max_size BETWEEN 1 AND 100),
    individual_application_allowed boolean,
    solo_participation_allowed boolean,
    team_formation_available boolean,
    image_url text,
    artwork_key varchar(20) NOT NULL
        CHECK (artwork_key IN ('AI_BUST', 'FINTECH_CITY', 'GAMEPAD', 'GREENTECH', 'WIREFRAME')),
    last_verified_at timestamptz,
    updated_at timestamptz NOT NULL,
    is_demo boolean NOT NULL DEFAULT false,
    description text NOT NULL,
    eligibility_summary text,
    allowed_country_codes jsonb CHECK (jsonb_typeof(allowed_country_codes) = 'array'),
    min_age integer CHECK (min_age BETWEEN 0 AND 120),
    max_age integer CHECK (max_age BETWEEN 0 AND 120),
    students_only boolean,
    languages jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(languages) = 'array'),
    stages jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(stages) = 'array'),
    prize_details text,
    submission_requirements text,
    judging_criteria text,
    official_url text NOT NULL,
    registration_url text,
    rules_url text,
    source_url text NOT NULL,
    CONSTRAINT catalog_published_at_present CHECK (publication_status <> 'PUBLISHED' OR published_at IS NOT NULL),
    CONSTRAINT catalog_team_size_order CHECK (team_min_size <= team_max_size),
    CONSTRAINT catalog_age_order CHECK (min_age <= max_age),
    CONSTRAINT catalog_country_code CHECK (country_code ~ '^[A-Z]{2}$'),
    CONSTRAINT catalog_prize_currency CHECK (prize_currency ~ '^[A-Z]{3}$'),
    CONSTRAINT catalog_starts_precision CHECK ((
        (starts_precision IS NULL AND starts_date IS NULL AND starts_time IS NULL
            AND starts_time_zone IS NULL AND starts_instant IS NULL)
        OR (starts_precision = 'DATE' AND starts_date IS NOT NULL AND starts_time IS NULL AND starts_instant IS NULL)
        OR (starts_precision = 'MINUTE' AND starts_date IS NOT NULL AND starts_time IS NOT NULL
            AND starts_time_zone IS NOT NULL AND starts_instant IS NOT NULL)
    ) IS TRUE),
    CONSTRAINT catalog_ends_precision CHECK ((
        (ends_precision IS NULL AND ends_date IS NULL AND ends_time IS NULL
            AND ends_time_zone IS NULL AND ends_instant IS NULL)
        OR (ends_precision = 'DATE' AND ends_date IS NOT NULL AND ends_time IS NULL AND ends_instant IS NULL)
        OR (ends_precision = 'MINUTE' AND ends_date IS NOT NULL AND ends_time IS NOT NULL
            AND ends_time_zone IS NOT NULL AND ends_instant IS NOT NULL)
    ) IS TRUE),
    CONSTRAINT catalog_deadline_precision CHECK ((
        (deadline_precision IS NULL AND deadline_date IS NULL AND deadline_time IS NULL
            AND deadline_time_zone IS NULL AND deadline_instant IS NULL)
        OR (deadline_precision = 'DATE' AND deadline_date IS NOT NULL AND deadline_time IS NULL AND deadline_instant IS NULL)
        OR (deadline_precision = 'MINUTE' AND deadline_date IS NOT NULL AND deadline_time IS NOT NULL
            AND deadline_time_zone IS NOT NULL AND deadline_instant IS NOT NULL)
    ) IS TRUE)
);

CREATE TABLE hackatlas.catalog_hackathon_topic (
    hackathon_id uuid NOT NULL REFERENCES hackatlas.catalog_hackathon(id) ON DELETE CASCADE,
    tag_id varchar(50) NOT NULL REFERENCES hackatlas.catalog_tag(id),
    PRIMARY KEY (hackathon_id, tag_id)
);

CREATE INDEX catalog_topic_lookup ON hackatlas.catalog_hackathon_topic (tag_id, hackathon_id);
CREATE INDEX catalog_deadline_order ON hackatlas.catalog_hackathon (deadline_date, deadline_time, id)
    WHERE publication_status = 'PUBLISHED';
CREATE INDEX catalog_start_order ON hackatlas.catalog_hackathon (starts_date, starts_time, id)
    WHERE publication_status = 'PUBLISHED';
CREATE INDEX catalog_newest_order ON hackatlas.catalog_hackathon (published_at DESC, id)
    WHERE publication_status = 'PUBLISHED';
CREATE INDEX catalog_location_filter ON hackatlas.catalog_hackathon (country_code, city)
    WHERE publication_status = 'PUBLISHED';

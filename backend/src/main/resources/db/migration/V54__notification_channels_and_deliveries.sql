-- =============================================================================
-- V54 — notification channels, what they are subscribed to, and every delivery
--       that was attempted; and the drop of the JSONB column V1 has carried
--       since M0 for the same idea
--
-- Source: docs/plans/2026-08-15-notifications.md §3. High-level plan §5 names
--         `integration` in the module list and §6r requires integration
--         credentials encrypted at rest; both land here.
--
-- This is the first product consumer of the outbox's PAYLOAD arm. Everything
-- recorded so far is a coalesced state event ("this company's results moved"),
-- whose handler is a whole-company recompute that takes no arguments — so no
-- payload has ever crossed a module boundary and been read. A notification
-- whose entire content is the payload cannot be sent from an event that has
-- none, which is why this feature is the one that had to answer that debt.
--
-- -----------------------------------------------------------------------------
-- 1. WHY THE JSONB COLUMN ON `team` GOES
-- -----------------------------------------------------------------------------
--
-- team.notification_channel has been in the schema since V1 with no writer, no
-- reader and no entity mapping. That is the writerless-column shape §116, §117,
-- §132 and §151 each found one instance of and each treated as a defect.
--
-- Leaving it beside a real notification_channel table would be worse than
-- either: two spellings of one concept, one of them dead, and the dead one is
-- the one an agent greps first because it is in V1. A drop is a decision, and it
-- is recorded in the plan as well as here.
--
-- What it cost to keep is nothing and what it costs to drop is nothing: no row
-- in any deployment has ever held a value, because no code has ever written one.
--
-- -----------------------------------------------------------------------------
-- 2. A CHANNEL IS PROJECT-LEVEL, AND ITS SECRET IS THE AUTHENTICATION
-- -----------------------------------------------------------------------------
--
-- A Slack or Teams incoming-webhook URL is not a coordinate, it is a
-- credential: anyone holding it can post into that room. Unlike the API tokens
-- of V36/V40, a digest is not available — the value has to be RECOVERABLE to be
-- used — so `secret` is ciphertext (AES-GCM, key from the environment) and never
-- plaintext, and `secret_hint` is the only part of it that ever leaves this
-- table. The hint is deliberately short and end-anchored: it says WHICH webhook
-- is configured without carrying enough of it to replay.
--
-- `secret` is nullable because an EMAIL channel has no credential of its own —
-- it posts through the instance's configured relay, whose credentials are
-- deployment configuration and belong nowhere near a company-owned row.
--
-- Uniqueness is (company_id, project_id, name), leading with company_id per
-- ERD §0.1 point 3: two clients may both call a channel "Releases", and a
-- previously-global constraint here is the shape that lets one client discover
-- the other's naming.
--
-- -----------------------------------------------------------------------------
-- 3. A THRESHOLD IS A PREDICATE ON AN EVENT, NOT AN EVENT
-- -----------------------------------------------------------------------------
--
-- The plan's phase-2 line lists "run failed threshold" beside the event names,
-- and modelling it as a fourth event is the tempting reading. It is refused
-- here: `min_failures` is a column on the SUBSCRIPTION, so the producer never
-- learns what any consumer considers alarming.
--
-- The alternative would have `execution` record a distinct event against a
-- threshold configured in `integration`, which is an edge the module layout
-- forbids (plan §3 — `integration` may point only at the foundation) and which
-- would also be wrong on its own terms: two subscribers who disagree about what
-- counts as a bad run would need two events for one occurrence.
--
-- 0 means "every run", which is the default a channel gets when nobody says
-- otherwise. It is NOT NULL rather than nullable so that "no threshold" has one
-- spelling; a nullable predicate column is two, and every reader has to handle
-- both.
--
-- -----------------------------------------------------------------------------
-- 4. THE DELIVERY LOG IS THE IDEMPOTENCY KEY, AND THAT IS ITS SECOND JOB
-- -----------------------------------------------------------------------------
--
-- Outbox delivery is at least once — a handler that succeeded and then lost the
-- settle runs again — so a handler that posts to Slack MUST be able to tell that
-- it already did. (company_id, channel_id, event_id) is unique, and the handler
-- skips a channel whose row is already SENT. Without that, one crash between the
-- POST and the settle is a duplicate message in somebody's release channel, and
-- the outbox's own retry ladder makes it up to eight of them.
--
-- event_id is carried IN THE PAYLOAD by the producer rather than being the
-- outbox row's id: OutboxMessage deliberately hands a handler no row identity,
-- so that a handler cannot mutate the delivery state of the row it is being
-- delivered from.
--
-- `error` is REDACTED before it lands here. A failed POST's message routinely
-- contains the URL it was posted to, and that URL is the credential — so a
-- delivery log is exactly where a webhook secret leaks into a table an operator
-- browses and a backup copies. This is the class of leak that is invisible in
-- every green run, so it has its own test rather than only this sentence.
--
-- -----------------------------------------------------------------------------
-- 5. RLS AND THE INDEXES
-- -----------------------------------------------------------------------------
--
-- Three new company-owned tables, so three times company_id NOT NULL, FORCE ROW
-- LEVEL SECURITY and V4's nullif() predicate — policies 65, 66 and 67, which
-- SchemaConventionTest pins by count so a table added without one cannot pass by
-- not being looked at.
--
-- Every index leads with company_id. The subscription table gets two, because it
-- is read from both ends: by channel when the settings screen renders one
-- channel, and by event type when a handler asks which channels in this company
-- want this event. The delivery table is read newest-first per channel, which is
-- the operator's question and the only one it answers.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. The column V1 has carried for this idea, with nothing behind it
-- -----------------------------------------------------------------------------

ALTER TABLE team DROP COLUMN notification_channel;

-- -----------------------------------------------------------------------------
-- 2. The channel
-- -----------------------------------------------------------------------------

CREATE TABLE notification_channel (
    id           UUID PRIMARY KEY,
    company_id   UUID        NOT NULL REFERENCES company(id),
    project_id   UUID        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    kind         TEXT        NOT NULL,
    name         TEXT        NOT NULL,
    enabled      BOOLEAN     NOT NULL,
    config       JSONB       NOT NULL,
    secret       BYTEA,
    secret_hint  TEXT,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    lock_version BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_notification_channel_name UNIQUE (company_id, project_id, name)
);

COMMENT ON TABLE notification_channel IS
    'Where one project sends its notices — an incoming webhook or a mail recipient list. '
    'PROJECT-level and never person-level: a per-user digest is a different table with a '
    'different unsubscribe story, and mixing the two would make "who gets told" answerable two '
    'ways. Written only by `integration`; every other module reaches it through an outbox event.';

COMMENT ON COLUMN notification_channel.kind IS
    'SLACK, TEAMS or EMAIL — which adapter delivers, and therefore what `config` and `secret` '
    'mean. Three from the start on the plan''s own reasoning for the issue-tracker port: a port '
    'with a single implementation always leaks that implementation''s vocabulary.';

COMMENT ON COLUMN notification_channel.config IS
    'The kind-specific settings that are NOT credentials — an EMAIL channel''s recipients today. '
    'Anything an operator may read back goes here; anything that authenticates goes in `secret`, '
    'because this column is returned over the wire and that one never is.';

COMMENT ON COLUMN notification_channel.secret IS
    'The webhook URL, AES-GCM ciphertext, key from the environment (plan §6r). Encrypted rather '
    'than digested because unlike an API token this value has to be RECOVERABLE to be used, so '
    'the api_token_lookup trick is not available. NULL for EMAIL, which authenticates as the '
    'instance''s relay and therefore has no credential of its own on the row. Never returned, '
    'never in an outbox payload, never in `notification_delivery.error`.';

COMMENT ON COLUMN notification_channel.secret_hint IS
    'The last few characters of the secret, masked — enough for a person to recognise WHICH '
    'webhook is configured and not enough to post to it. The only part of the credential that '
    'ever leaves this table.';

CREATE INDEX notification_channel_project_idx
    ON notification_channel (company_id, project_id);

-- -----------------------------------------------------------------------------
-- 3. What a channel is subscribed to
-- -----------------------------------------------------------------------------

CREATE TABLE notification_subscription (
    id           UUID PRIMARY KEY,
    company_id   UUID NOT NULL REFERENCES company(id),
    channel_id   UUID NOT NULL REFERENCES notification_channel(id) ON DELETE CASCADE,
    event_type   TEXT NOT NULL,
    min_failures INT  NOT NULL,
    CONSTRAINT uq_notification_subscription UNIQUE (company_id, channel_id, event_type)
);

COMMENT ON TABLE notification_subscription IS
    'One row per event type a channel wants. A channel with no rows here is configured and '
    'silent, which is a legitimate state and the one a channel is created in.';

COMMENT ON COLUMN notification_subscription.event_type IS
    'The outbox event type, matched by string exactly as OutboxHandler matches it — '
    'execution.run_completed today. TEXT and not an enum: the vocabulary lives in Java '
    '(OutboxEvents) and a CHECK here would be a second copy of it that a migration has to move.';

COMMENT ON COLUMN notification_subscription.min_failures IS
    'How many failures a run must have before this subscription wants to hear about it. 0 is '
    'every run, and is the default. A predicate on the event rather than an event of its own, so '
    'that `execution` never learns what any subscriber considers alarming — see this migration''s '
    'header. Meaningless for event types that carry no failure count, where it stays 0.';

CREATE INDEX notification_subscription_channel_idx
    ON notification_subscription (company_id, channel_id);

CREATE INDEX notification_subscription_event_idx
    ON notification_subscription (company_id, event_type);

-- -----------------------------------------------------------------------------
-- 4. Every delivery that was attempted
-- -----------------------------------------------------------------------------

CREATE TABLE notification_delivery (
    id         UUID        PRIMARY KEY,
    company_id UUID        NOT NULL REFERENCES company(id),
    channel_id UUID        NOT NULL REFERENCES notification_channel(id) ON DELETE CASCADE,
    event_id   UUID        NOT NULL,
    event_type TEXT        NOT NULL,
    status     TEXT        NOT NULL,
    attempt    INT         NOT NULL,
    error      TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_notification_delivery UNIQUE (company_id, channel_id, event_id)
);

COMMENT ON TABLE notification_delivery IS
    'What was attempted, to which channel, and what came back — the operator-facing half, and the '
    'reason the feature is debuggable at all. It is ALSO the idempotency record: outbox delivery '
    'is at least once, so a handler that already posted has to be able to tell, and '
    '(company_id, channel_id, event_id) is the key it tells by.';

COMMENT ON COLUMN notification_delivery.event_id IS
    'The occurrence, minted by the producer and carried in the outbox payload. Not the outbox '
    'row''s id: a handler is deliberately given no row identity, so that it cannot mutate the '
    'delivery state of the row being delivered from inside the delivery.';

COMMENT ON COLUMN notification_delivery.status IS
    'SENT or FAILED. A row exists as soon as an attempt is made, so a channel that has never been '
    'reachable is visible here rather than absent — an empty history and a broken webhook must '
    'not look the same.';

COMMENT ON COLUMN notification_delivery.attempt IS
    'How many times THIS channel has been tried for THIS occurrence. The outbox''s own attempt '
    'count covers the whole event across every channel, and a fan-out of three where one webhook '
    'is dead makes those two numbers different — this is the one an operator is asking about.';

COMMENT ON COLUMN notification_delivery.error IS
    'The failure, REDACTED. A failed POST''s message routinely quotes the URL it was posted to, '
    'and that URL is the credential — so this column is exactly where a webhook secret leaks into '
    'something an operator browses and a backup copies. Redaction has its own test, because a '
    'leak here is invisible in every green run.';

CREATE INDEX notification_delivery_channel_idx
    ON notification_delivery (company_id, channel_id, created_at DESC);

-- -----------------------------------------------------------------------------
-- 5. RLS — three more forced policies, V4's predicate verbatim
-- -----------------------------------------------------------------------------

ALTER TABLE notification_channel ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification_channel FORCE ROW LEVEL SECURITY;
CREATE POLICY company_isolation ON notification_channel USING
    (company_id = nullif(current_setting('app.current_company', true), '')::uuid);

ALTER TABLE notification_subscription ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification_subscription FORCE ROW LEVEL SECURITY;
CREATE POLICY company_isolation ON notification_subscription USING
    (company_id = nullif(current_setting('app.current_company', true), '')::uuid);

ALTER TABLE notification_delivery ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification_delivery FORCE ROW LEVEL SECURITY;
CREATE POLICY company_isolation ON notification_delivery USING
    (company_id = nullif(current_setting('app.current_company', true), '')::uuid);

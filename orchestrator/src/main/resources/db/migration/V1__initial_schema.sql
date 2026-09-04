-- Orchestrator durable state.
--
-- A run can now sit in AWAITING_INPUT indefinitely waiting for a person, so losing it to a restart
-- is a real defect rather than a theoretical one. That is what this schema exists for.
--
-- Plans are stored as a JSON payload rather than shredded across six tables: a plan is immutable and
-- is always read whole, so normalising it would buy queryability nobody needs at the cost of a
-- mapping layer for every record type in the planning model.

CREATE TABLE plan (
    id          VARCHAR(64) PRIMARY KEY,
    created_at  TIMESTAMP    NOT NULL,
    payload     CLOB         NOT NULL
);

CREATE TABLE run (
    id              VARCHAR(64) PRIMARY KEY,
    plan_id         VARCHAR(64)  NOT NULL,
    autonomy_level  VARCHAR(32)  NOT NULL,
    status          VARCHAR(32)  NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    started_at      TIMESTAMP,
    ended_at        TIMESTAMP,
    stop_requested  BOOLEAN      NOT NULL DEFAULT FALSE,
    stop_reason     VARCHAR(512),
    CONSTRAINT fk_run_plan FOREIGN KEY (plan_id) REFERENCES plan (id)
);

-- Recovery on boot asks "which runs were still live?", so status is the index that matters.
CREATE INDEX idx_run_status ON run (status);

CREATE TABLE run_node (
    run_id      VARCHAR(64)  NOT NULL,
    task_id     VARCHAR(128) NOT NULL,
    state       VARCHAR(32)  NOT NULL,
    attempt     INT          NOT NULL,
    started_at  TIMESTAMP,
    ended_at    TIMESTAMP,
    message     VARCHAR(2048),
    outputs     CLOB,
    PRIMARY KEY (run_id, task_id),
    CONSTRAINT fk_node_run FOREIGN KEY (run_id) REFERENCES run (id)
);

CREATE TABLE run_event (
    run_id       VARCHAR(64)  NOT NULL,
    seq          BIGINT       NOT NULL,
    task_id      VARCHAR(128),
    type         VARCHAR(48)  NOT NULL,
    actor        VARCHAR(64),
    message      VARCHAR(2048),
    occurred_at  TIMESTAMP    NOT NULL,
    -- (run_id, seq) as the key is what makes the log append-only and resumable: a reader that has
    -- seen seq N asks for everything above it, and cannot be handed a gap or a duplicate.
    PRIMARY KEY (run_id, seq),
    CONSTRAINT fk_event_run FOREIGN KEY (run_id) REFERENCES run (id)
);

CREATE TABLE approval_request (
    id            VARCHAR(64) PRIMARY KEY,
    run_id        VARCHAR(64)  NOT NULL,
    task_id       VARCHAR(128) NOT NULL,
    task_title    VARCHAR(512),
    blast_radius  VARCHAR(16)  NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    reasons       CLOB,
    policy_ids    VARCHAR(512),
    requested_at  TIMESTAMP    NOT NULL,
    decided_by    VARCHAR(128),
    decided_at    TIMESTAMP,
    guidance      VARCHAR(2048),
    CONSTRAINT fk_approval_run FOREIGN KEY (run_id) REFERENCES run (id)
);

CREATE INDEX idx_approval_status ON approval_request (status);
CREATE INDEX idx_approval_run ON approval_request (run_id);

CREATE TABLE decision (
    id           VARCHAR(64) PRIMARY KEY,
    run_id       VARCHAR(64)  NOT NULL,
    task_id      VARCHAR(128),
    actor        VARCHAR(128) NOT NULL,
    question     VARCHAR(1024),
    choice       VARCHAR(256),
    rationale    CLOB,
    alternatives CLOB,
    occurred_at  TIMESTAMP    NOT NULL,
    CONSTRAINT fk_decision_run FOREIGN KEY (run_id) REFERENCES run (id)
);

CREATE INDEX idx_decision_run ON decision (run_id);

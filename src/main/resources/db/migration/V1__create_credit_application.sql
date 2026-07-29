CREATE TABLE credit_application (
    id                  VARCHAR(36)     NOT NULL,
    applicant_document  VARCHAR(32)     NOT NULL,
    applicant_name      VARCHAR(160)    NOT NULL,
    monthly_income      NUMERIC(15, 2)  NOT NULL,
    requested_amount    NUMERIC(15, 2)  NOT NULL,
    term_months         INTEGER         NOT NULL,
    status              VARCHAR(32)     NOT NULL,
    bureau_score        INTEGER,
    has_active_defaults BOOLEAN,
    risk_decision       VARCHAR(32),
    reservation_id      VARCHAR(64),
    disbursement_id     VARCHAR(64),
    resolution_reason   VARCHAR(255),
    process_instance_key BIGINT,
    CONSTRAINT pk_credit_application PRIMARY KEY (id)
);

CREATE INDEX idx_credit_application_document ON credit_application (applicant_document);
CREATE INDEX idx_credit_application_status ON credit_application (status);

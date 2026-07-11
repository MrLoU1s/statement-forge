-- Seed at scale: 100,000 customers, 200,000 accounts, 5,000,000 transactions,
-- 3 comm templates — all set-based via generate_series (an app-level loop
-- inserting 5M rows one at a time would take hours instead of minutes).
--
-- setseed() fixes Postgres's random() sequence so a full rebuild
-- (docker compose down -v, then reboot) regenerates the same shape of data
-- every time — reproducible EXPLAIN plans across machines/sessions.
SELECT setseed(0.42);

-- 100,000 customers. created_at spread 2019-01-01..2023-12-31 (1,826 days),
-- i.e. before the transaction window below — customers predate their activity.
INSERT INTO customer (name, segment, created_at)
SELECT
    'Customer ' || i,
    (ARRAY['RETAIL', 'PREMIUM', 'BUSINESS', 'STUDENT'])[1 + (i % 4)],
    TIMESTAMPTZ '2019-01-01 00:00:00+00' + (floor(random() * 1826))::int * INTERVAL '1 day'
FROM generate_series(1, 100000) AS s(i);

-- 200,000 accounts, exactly 2 per customer: i and i+100000 share a customer_id
-- (customer ids are a fresh IDENTITY sequence 1..100000, so the arithmetic lines up).
INSERT INTO account (customer_id, product_type, opened_at, status)
SELECT
    ((i - 1) % 100000) + 1,
    (ARRAY['CHECKING', 'SAVINGS', 'CREDIT_CARD', 'LOAN'])[1 + (i % 4)],
    DATE '2020-01-01' + (floor(random() * 1461))::int,
    (ARRAY['ACTIVE', 'DORMANT', 'CLOSED'])[1 + (i % 3)]
FROM generate_series(1, 200000) AS s(i);

-- 5,000,000 transactions, exactly 25 per account: i, i+200000, i+400000, ...
-- share an account_id (200,000 fresh IDENTITY account ids). txn_date is
-- uniform across 2024-01-01..2025-12-31 (731 days); amount is mixed-sign
-- in +/-10,000.00.
INSERT INTO transaction (account_id, txn_date, amount, description)
SELECT
    ((i - 1) % 200000) + 1,
    DATE '2024-01-01' + (floor(random() * 731))::int,
    round(((random() * 20000) - 10000)::numeric, 2),
    (ARRAY['POS purchase', 'Salary credit', 'ATM withdrawal', 'Online transfer',
           'Utility payment', 'Card refund', 'Direct debit', 'Interest credit'])[1 + (i % 8)]
FROM generate_series(1, 5000000) AS s(i);

INSERT INTO comm_template (name, channel, body_template, version) VALUES
    ('Monthly Statement - Email', 'EMAIL',
     'Dear {{customerName}}, your statement for {{period}} is ready. Closing balance: {{closingBalance}}.', 1),
    ('Monthly Statement - Print', 'PRINT',
     'STATEMENT OF ACCOUNT -- Customer: {{customerName}} -- Period: {{period}} -- Closing Balance: {{closingBalance}}', 1),
    ('Monthly Statement - SMS', 'SMS',
     'Hi {{customerName}}, your {{period}} statement closing balance is {{closingBalance}}. Reply STOP to opt out.', 1);

-- Fresh planner statistics. Without this, every EXPLAIN captured in later
-- milestones reads stale (zero-row) stats from before this seed existed.
ANALYZE;

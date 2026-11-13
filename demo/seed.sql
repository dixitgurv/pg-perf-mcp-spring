-- Demo schema with problems planted on purpose, so every tool has something to find.
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

DROP TABLE IF EXISTS order_items, orders, customers CASCADE;

CREATE TABLE customers (
    id         bigint PRIMARY KEY,
    email      text NOT NULL,
    country    text NOT NULL,
    city       text NOT NULL,
    created_at timestamptz NOT NULL
);

CREATE TABLE orders (
    id          bigint PRIMARY KEY,
    customer_id bigint NOT NULL REFERENCES customers (id),  -- no index on the FK: planted problem
    status      text NOT NULL,
    total_cents integer NOT NULL,
    created_at  timestamptz NOT NULL
);

CREATE TABLE order_items (
    order_id   bigint NOT NULL,
    sku        text NOT NULL,
    qty        integer NOT NULL,
    price_cents integer NOT NULL
);

INSERT INTO customers
SELECT g,
       'user' || g || '@example.com',
       'C' || (g % 20),
       -- city is a function of country, so the planner misjudges country+city filters
       'City' || (g % 20),
       now() - (g % 1000) * interval '1 day'
FROM generate_series(1, 100000) g;

INSERT INTO orders
SELECT g,
       1 + (g::bigint * 7919) % 100000,
       CASE WHEN g % 200 = 0 THEN 'disputed' WHEN g % 10 = 0 THEN 'pending' ELSE 'shipped' END,
       (g * 37) % 50000,
       now() - (g % 730) * interval '1 day'
FROM generate_series(1, 1000000) g;

INSERT INTO order_items
SELECT 1 + (g * 31) % 1000000, 'SKU-' || (g % 5000), 1 + g % 4, (g * 13) % 9000
FROM generate_series(1, 2000000) g;

-- Planted index problems
CREATE INDEX orders_total_idx ON orders (total_cents);                 -- never used by the demo queries
CREATE INDEX customers_country_idx ON customers (country);
CREATE INDEX customers_country_city_idx ON customers (country, city);  -- makes the one above redundant

ANALYZE;

-- 1. Seq scan that throws away 99.5% of rows (fix: index on status, ideally partial)
SELECT id, customer_id, total_cents FROM orders WHERE status = 'disputed';

-- 2. Correlated columns: planner expects ~250 rows, gets 5,000 (fix: CREATE STATISTICS)
SELECT count(*) FROM customers WHERE country = 'C3' AND city = 'City3';

-- 3. Large sort that spills to disk under default work_mem
SELECT * FROM order_items ORDER BY price_cents DESC, sku;

-- 4. Join on an unindexed foreign key
SELECT c.email, count(*) FROM customers c JOIN orders o ON o.customer_id = c.id
WHERE c.id BETWEEN 1 AND 50 GROUP BY c.email;

-- Test data of tools/live-hive/run.sh. Hive escapes an apostrophe with a backslash: two adjacent literals are concatenated.
CREATE DATABASE IF NOT EXISTS ebxtest;
DROP TABLE IF EXISTS ebxtest.customer;
CREATE TABLE ebxtest.customer (id INT COMMENT 'Customer number', name STRING COMMENT 'Full name', city STRING, amount DECIMAL(12,2), born DATE, updated_at TIMESTAMP, active BOOLEAN) COMMENT 'Customers of the test';
INSERT INTO ebxtest.customer VALUES
 (1, 'Ada Lovelace', 'London', 120.50, DATE '1815-12-10', TIMESTAMP '2026-11-20 10:15:00', true),
 (2, 'Grace Hopper', 'New York', 99.90, DATE '1906-12-09', TIMESTAMP '2026-11-21 08:00:00', true),
 (3, 'Zoé d\'Arc', 'L\'Haÿ-les-Roses', 15.00, DATE '1990-02-28', TIMESTAMP '2026-11-22 23:59:59', false),
 (4, 'Åsa Öberg', NULL, NULL, NULL, NULL, true),
 (5, 'Ada Lovelace', 'London', 0.01, DATE '2000-01-01', TIMESTAMP '2026-01-01 00:00:00', NULL);
DROP TABLE IF EXISTS ebxtest.order_line;
CREATE TABLE ebxtest.order_line (order_id BIGINT, line INT, customer_id INT, label VARCHAR(40), quantity DOUBLE);
INSERT INTO ebxtest.order_line VALUES (100, 1, 1, 'Widget', 2.0), (100, 2, 1, 'Gadget', 1.5), (101, 1, 3, 'Widget', 10.0);
DROP VIEW IF EXISTS ebxtest.customer_city;
CREATE VIEW ebxtest.customer_city AS SELECT city, COUNT(*) AS customers FROM ebxtest.customer GROUP BY city;
DROP TABLE IF EXISTS ebxtest.odd_names;
CREATE TABLE ebxtest.odd_names (`date` STRING, `select` INT, `my col` STRING, `ünï` STRING);
INSERT INTO ebxtest.odd_names VALUES ('2026-01-01', 1, 'a b', 'é'), ('2026-01-02', 2, NULL, 'è');

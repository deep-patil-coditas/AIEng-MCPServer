-- Per-test reset (test.md fixtures): empty the table, restart ids at 1, re-insert the data.sql seed rows.
TRUNCATE TABLE product RESTART IDENTITY;
INSERT INTO product (name, description, price, quantity) VALUES ('Wireless Mouse', '2.4 GHz wireless mouse, black', 799.00, 25);
INSERT INTO product (name, description, price, quantity) VALUES ('Mechanical Keyboard', 'Blue switches, full size', 2499.00, 10);
INSERT INTO product (name, description, price, quantity) VALUES ('USB-C Hub', '6-in-1 hub with HDMI', 1299.50, 0);

-- Local development only. Production credentials must be provisioned separately.
CREATE DATABASE pueblo_member CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'pueblo_member'@'%' IDENTIFIED BY 'local_member_only';
GRANT ALL PRIVILEGES ON pueblo_member.* TO 'pueblo_member'@'%';
CREATE DATABASE pueblo_catalog CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'pueblo_catalog'@'%' IDENTIFIED BY 'local_catalog_only';
GRANT ALL PRIVILEGES ON pueblo_catalog.* TO 'pueblo_catalog'@'%';
CREATE DATABASE pueblo_order CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'pueblo_order'@'%' IDENTIFIED BY 'local_order_only';
GRANT ALL PRIVILEGES ON pueblo_order.* TO 'pueblo_order'@'%';
CREATE DATABASE pueblo_payment CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'pueblo_payment'@'%' IDENTIFIED BY 'local_payment_only';
GRANT ALL PRIVILEGES ON pueblo_payment.* TO 'pueblo_payment'@'%';

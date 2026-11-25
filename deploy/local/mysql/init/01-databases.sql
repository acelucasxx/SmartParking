-- 首次创建数据卷时执行一次。业务库 smartparking 与账号由 MYSQL_DATABASE / MYSQL_USER 自动创建，
-- 这里额外创建集成测试库并授权。表结构由服务端 Flyway 迁移管理（INF-04），不要在这里建表。
CREATE DATABASE IF NOT EXISTS smartparking_test
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
GRANT ALL PRIVILEGES ON smartparking_test.* TO 'smartparking'@'%';
FLUSH PRIVILEGES;

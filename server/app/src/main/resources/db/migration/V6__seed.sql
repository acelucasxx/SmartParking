-- 初始数据：三个后台角色与 PRD 第 7 章“系统设置”的业务参数默认值。
-- 不预置后台账号：首个超级管理员由 BIZ-08 的初始化机制创建，避免默认密码进入代码库。

INSERT INTO role (code, name, permissions) VALUES
    ('SUPER_ADMIN', '超级管理员', JSON_ARRAY('*')),
    ('OPS', '运维', JSON_ARRAY()),
    ('SUPPORT', '客服', JSON_ARRAY());

INSERT INTO system_config (config_key, config_value, value_type, description) VALUES
    ('member.max_per_device', '5', 'INT', '每台设备被授权人上限（含待接受邀请）'),
    ('card.ttl_hours', '24', 'INT', '邀请 / 转让卡片有效期（小时）'),
    ('command.timeout_seconds', '15', 'INT', '控制指令超时（秒）'),
    ('online.sleep_minutes', '10', 'INT', '超过该时长无上行判为休眠（分钟）'),
    ('online.offline_hours', '24', 'INT', '超过该时长无上行判为离线（小时）'),
    ('status_poll.interval_minutes', '5', 'INT', '定时状态查询间隔（分钟）');

-- 后台角色的权限点（BIZ-08）：前端据此控制按钮，服务端仍按接口的 @AdminRoles（契约 x-roles）判定。超级管理员为 ["*"]。

UPDATE role SET permissions = JSON_ARRAY(
    'dashboard:view',
    'device:view', 'device:import', 'device:disable', 'device:replace-part', 'device:self-check',
    'device:force-unbind', 'device:reset-code',
    'command:view', 'command:export',
    'alarm:view', 'alarm:handle'
) WHERE code = 'OPS';

UPDATE role SET permissions = JSON_ARRAY(
    'dashboard:view',
    'device:view', 'device:force-unbind', 'device:reset-code',
    'user:view', 'user:ban',
    'command:view', 'command:export'
) WHERE code = 'SUPPORT';

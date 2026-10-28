-- 仅在专用示例库中手动执行；生成器不会执行此脚本。
-- 表结构按提取 SQL 所需字段构造，并非业务数据库 DDL；数据均为人工示例。
-- 不设置自增列、外键或触发器，以便演示受限 DML 自动生成。
CREATE TABLE ai_menu (
    id BIGINT PRIMARY KEY,
    pid BIGINT,
    name VARCHAR(64),
    url VARCHAR(128),
    icon VARCHAR(64),
    icon_selected VARCHAR(64),
    version VARCHAR(32),
    sort INTEGER
);
CREATE TABLE ai_menu_role_relation (
    id BIGINT PRIMARY KEY,
    menu_id BIGINT NOT NULL,
    role VARCHAR(32) NOT NULL,
    version VARCHAR(32)
);
INSERT INTO ai_menu(id,pid,name,url,icon,icon_selected,version,sort) VALUES
    (10,0,'菜单示例一','/example/one','one','one-selected',NULL,1),
    (20,0,'菜单示例二','/example/two','two','two-selected','v1',2),
    (30,0,'菜单示例三','/example/three','three','three-selected','v2',3);
INSERT INTO ai_menu_role_relation(id,menu_id,role,version) VALUES
    (100,10,'reader',NULL),
    (101,20,'editor','v1'),
    (102,30,'admin','v2');

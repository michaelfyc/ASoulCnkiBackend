create database if not exists bilibili_cnki;

use bilibili_cnki;

drop table if exists user_dynamic;
create table user_dynamic
(
  `dynamic_id` bigint comment '动态唯一 id',
  `uid`        int comment '成员 id',
  `type_id`    int comment '动态类型, 用于获取评论',
  `oid`        bigint comment '评论区 id',
  `status`     int comment '评论爬取状态，0: 未爬取， 1: 至少爬取完成过一次',
  PRIMARY KEY (dynamic_id) USING BTREE
) DEFAULT CHARACTER SET = utf8mb4;

drop table if exists kv;
create table kv_store
(
  `field_name`  varchar(255) NOT NULL,
  `field_value` text         NOT NULL,
  PRIMARY KEY (field_name) USING BTREE
) DEFAULT CHARACTER SET = utf8mb4;

drop table if exists reply;
create table reply (
  `id`         int auto_increment,
  `rpid`       bigint not null comment '回复 id',
  `uid`        int comment '成员 id',
  `type_id`    int,
  `dynamic_id` bigint,
  `mid`        int comment '评论用户 id',
  `oid`        bigint comment '源稿件 id',
  `ctime`      int comment '创建时间',
  `m_name`     varchar(255) comment '昵称',
  `content`    text comment '评论内容',
  `like_num`   int,
  PRIMARY KEY (id) USING BTREE,
  index `idx_rpid` (rpid)
) DEFAULT CHARACTER SET = utf8mb4;

-- 演示用户昵称（评价区展示作者昵称；头像仍用昵称首字 + 稳定背景色）
-- 无条件按 id 更新，避免历史乱码值导致条件更新跳过
USE `llmall-user`;

UPDATE `user` SET `nick_name` = '爱吃火锅的Jack' WHERE `id` = 1;
UPDATE `user` SET `nick_name` = 'Rose的探店日记' WHERE `id` = 2;
UPDATE `user` SET `nick_name` = 'Hope小馆' WHERE `id` = 3;

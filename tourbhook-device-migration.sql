CREATE TABLE IF NOT EXISTS `user_devices` (
                                              `created_at` datetime(6) NOT NULL,
    `first_seen_at` datetime(6) NOT NULL,
    `last_seen_at` datetime(6) NOT NULL,
    `updated_at` datetime(6) NOT NULL,
    `device_hash` varchar(64) NOT NULL,
    `id` varchar(255) NOT NULL,
    `user_id` varchar(255) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_devices_hash_user` (`device_hash`,`user_id`),
    KEY `idx_user_devices_user` (`user_id`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `blocked_phone_numbers` (
                                                       `created_at` datetime(6) NOT NULL,
    `updated_at` datetime(6) NOT NULL,
    `phone_e164` varchar(20) NOT NULL,
    `reason` varchar(255) DEFAULT NULL,
    `id` varchar(255) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_blocked_phone_e164` (`phone_e164`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

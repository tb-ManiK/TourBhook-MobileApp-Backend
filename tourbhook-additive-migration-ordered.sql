CREATE TABLE `users` (
  `blocked` bit(1) NOT NULL,
  `deleted` bit(1) NOT NULL,
  `dob` date DEFAULT NULL,
  `level_progress` decimal(5,2) NOT NULL,
  `premium` bit(1) NOT NULL,
  `trips_completed` int NOT NULL,
  `blocked_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `preferred_language` varchar(10) NOT NULL,
  `phone` varchar(20) NOT NULL,
  `level` varchar(50) NOT NULL,
  `name` varchar(100) NOT NULL,
  `email` varchar(150) NOT NULL,
  `blocked_reason` varchar(500) DEFAULT NULL,
  `avatar_url` varchar(255) DEFAULT NULL,
  `id` varchar(255) NOT NULL,
  `password` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_users_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `community_groups` (
  `end_date` date DEFAULT NULL,
  `max_members` int NOT NULL,
  `start_date` date DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `name` varchar(100) NOT NULL,
  `city` varchar(120) NOT NULL,
  `description` varchar(500) DEFAULT NULL,
  `id` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_community_groups_city` (`city`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `trips` (
  `adults` int NOT NULL,
  `days` int NOT NULL,
  `end_date` date NOT NULL,
  `kids` int NOT NULL,
  `places_count` int NOT NULL,
  `start_date` date NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `trip_style` varchar(40) NOT NULL,
  `city` varchar(120) NOT NULL,
  `image_url` varchar(2000) DEFAULT NULL,
  `id` varchar(255) NOT NULL,
  `purposeof_travel` varchar(255) DEFAULT NULL,
  `starting_point_id` varchar(255) DEFAULT NULL,
  `user_id` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FK8wb14dx6ed0bpp3planbay88u` (`user_id`),
  CONSTRAINT `FK8wb14dx6ed0bpp3planbay88u` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `places` (
  `latitude` double DEFAULT NULL,
  `longitude` double DEFAULT NULL,
  `rating` decimal(38,2) DEFAULT NULL,
  `reviews_count` int DEFAULT NULL,
  `reviews_count_at_last_summary` int DEFAULT NULL,
  `suggested` bit(1) NOT NULL,
  `verified` bit(1) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `review_summary_generated_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `address` varchar(1000) DEFAULT NULL,
  `image_url` varchar(2000) DEFAULT NULL,
  `review_summary` varchar(3000) DEFAULT NULL,
  `category` varchar(255) DEFAULT NULL,
  `city` varchar(255) DEFAULT NULL,
  `google_place_id` varchar(255) DEFAULT NULL,
  `id` varchar(255) NOT NULL,
  `name` varchar(255) NOT NULL,
  `source_query` varchar(255) DEFAULT NULL,
  `typical_duration` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKc4tbm0moqxiavawlj1e95dyge` (`google_place_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `subscriptions` (
  `renews_at` date NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `id` varchar(255) NOT NULL,
  `plan` varchar(255) NOT NULL,
  `status` varchar(255) NOT NULL,
  `user_id` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKl3ommhd1n0tu0k2va0cbp87qe` (`user_id`),
  CONSTRAINT `FKhro52ohfqfbay9774bev0qinr` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `itineraries` (
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `city` varchar(255) NOT NULL,
  `id` varchar(255) NOT NULL,
  `title` varchar(255) NOT NULL,
  `trip_id` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKc73fljqq86fxrcj7akme5rrfc` (`trip_id`),
  CONSTRAINT `FK5x0i0sik3lh1hn5mt3ssifga5` FOREIGN KEY (`trip_id`) REFERENCES `trips` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `packing_lists` (
  `created_at` datetime(6) NOT NULL,
  `packing_generated_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `packing_source` varchar(20) DEFAULT NULL,
  `itinerary_signature` varchar(128) DEFAULT NULL,
  `id` varchar(255) NOT NULL,
  `trip_id` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKn3qc83invoif7b5itw87lfk1w` (`trip_id`),
  CONSTRAINT `FKija37w9i4b6mh8wn30bra1dxm` FOREIGN KEY (`trip_id`) REFERENCES `trips` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `itinerary_days` (
  `day_number` int NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `id` varchar(255) NOT NULL,
  `itinerary_id` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKib3kgo39dhcha9umbhwn7emue` (`itinerary_id`),
  CONSTRAINT `FKib3kgo39dhcha9umbhwn7emue` FOREIGN KEY (`itinerary_id`) REFERENCES `itineraries` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `packing_categories` (
  `ai_generated` bit(1) NOT NULL,
  `checked` bit(1) NOT NULL,
  `template_only` bit(1) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `icon` varchar(255) DEFAULT NULL,
  `id` varchar(255) NOT NULL,
  `name` varchar(255) NOT NULL,
  `packing_list_id` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_packing_categories_template` (`template_only`),
  KEY `FKowdveebuh5bwn4nis3ntp77ib` (`packing_list_id`),
  CONSTRAINT `FKowdveebuh5bwn4nis3ntp77ib` FOREIGN KEY (`packing_list_id`) REFERENCES `packing_lists` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `subscription_features` (
  `feature_name` varchar(255) DEFAULT NULL,
  `subscription_id` varchar(255) NOT NULL,
  KEY `FKlok2cvnfptni5oxvl0arwpuda` (`subscription_id`),
  CONSTRAINT `FKlok2cvnfptni5oxvl0arwpuda` FOREIGN KEY (`subscription_id`) REFERENCES `subscriptions` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `activities` (
  `cost` decimal(10,2) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `activity_time` varchar(255) NOT NULL,
  `duration` varchar(255) NOT NULL,
  `icon` varchar(255) NOT NULL,
  `id` varchar(255) NOT NULL,
  `itinerary_day_id` varchar(255) DEFAULT NULL,
  `place_id` varchar(255) DEFAULT NULL,
  `title` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FK6qc6poqy57n1ive0p1l0cgy1o` (`itinerary_day_id`),
  CONSTRAINT `FK6qc6poqy57n1ive0p1l0cgy1o` FOREIGN KEY (`itinerary_day_id`) REFERENCES `itinerary_days` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `feature_feedback` (
  `rating` int NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `reference_id` varchar(36) DEFAULT NULL,
  `context` varchar(80) DEFAULT NULL,
  `comment` varchar(1000) DEFAULT NULL,
  `id` varchar(255) NOT NULL,
  `user_id` varchar(255) NOT NULL,
  `feature` enum('DATA_IMPORT') NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_feature_feedback_user_created` (`user_id`,`created_at`),
  KEY `idx_feature_feedback_feature_created` (`feature`,`created_at`),
  CONSTRAINT `FK3siq7yluabyr1ju7b1e3mw5o4` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `group_members` (
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `group_id` varchar(255) NOT NULL,
  `id` varchar(255) NOT NULL,
  `user_id` varchar(255) NOT NULL,
  `role` enum('MEMBER','OWNER') NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_group_members_group_user` (`group_id`,`user_id`),
  KEY `idx_group_members_user` (`user_id`),
  CONSTRAINT `FK69k58usfpds1vs4esxkuwbgu` FOREIGN KEY (`group_id`) REFERENCES `community_groups` (`id`),
  CONSTRAINT `FKnr9qg33qt2ovmv29g4vc3gtdx` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `group_messages` (
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `content` varchar(2000) NOT NULL,
  `group_id` varchar(255) NOT NULL,
  `id` varchar(255) NOT NULL,
  `sender_id` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_group_messages_group_created` (`group_id`,`created_at`,`id`),
  KEY `FKcuf7bhtxj1qc0ajswsjrn9vvd` (`sender_id`),
  CONSTRAINT `FK9rrj1sv4x18uw0gbi2nt4n2tq` FOREIGN KEY (`group_id`) REFERENCES `community_groups` (`id`),
  CONSTRAINT `FKcuf7bhtxj1qc0ajswsjrn9vvd` FOREIGN KEY (`sender_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `import_drafts` (
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `committed_trip_id` varchar(36) DEFAULT NULL,
  `id` varchar(255) NOT NULL,
  `user_id` varchar(255) NOT NULL,
  `payload_json` tinytext NOT NULL,
  `source_type` enum('CONFIRMATION_EMAIL','CSV_FILE') NOT NULL,
  `status` enum('COMMITTED','DISCARDED','DRAFT') NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_import_drafts_user_status` (`user_id`,`status`),
  CONSTRAINT `FKb8vp2tibq4hf8unxoe8x0q78f` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `packing_items` (
  `checked` bit(1) NOT NULL,
  `quantity` int NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `id` varchar(255) NOT NULL,
  `name` varchar(255) NOT NULL,
  `packing_category_id` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKj9kcmkdq5femsa63w1p65kkah` (`packing_category_id`),
  CONSTRAINT `FKj9kcmkdq5femsa63w1p65kkah` FOREIGN KEY (`packing_category_id`) REFERENCES `packing_categories` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `packing_list_transports` (
  `packing_list_id` varchar(255) NOT NULL,
  `transport_name` varchar(255) DEFAULT NULL,
  KEY `FKseajl48qvd57qcspj4qkbb0wk` (`packing_list_id`),
  CONSTRAINT `FKseajl48qvd57qcspj4qkbb0wk` FOREIGN KEY (`packing_list_id`) REFERENCES `packing_lists` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `payment_methods` (
  `last_four` varchar(4) NOT NULL,
  `primary_method` bit(1) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `expiry` varchar(255) NOT NULL,
  `holder_name` varchar(255) NOT NULL,
  `id` varchar(255) NOT NULL,
  `type` varchar(255) NOT NULL,
  `user_id` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_payment_methods_user` (`user_id`),
  CONSTRAINT `FKin7rtmim3ljrrhh5kxbq27s2v` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `payment_orders` (
  `amount` int NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `paid_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `currency` varchar(10) NOT NULL,
  `status` varchar(30) NOT NULL,
  `plan_code` varchar(40) NOT NULL,
  `razorpay_order_id` varchar(80) NOT NULL,
  `razorpay_payment_id` varchar(80) DEFAULT NULL,
  `receipt` varchar(80) NOT NULL,
  `notes_json` varchar(500) DEFAULT NULL,
  `gateway_response` varchar(1000) DEFAULT NULL,
  `id` varchar(255) NOT NULL,
  `razorpay_signature` varchar(255) DEFAULT NULL,
  `user_id` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_payment_orders_razorpay_order` (`razorpay_order_id`),
  UNIQUE KEY `UK9yqv3qxfnlanitrl34x91cdur` (`receipt`),
  KEY `idx_payment_orders_user` (`user_id`),
  KEY `idx_payment_orders_payment_id` (`razorpay_payment_id`),
  CONSTRAINT `FKhu5t5owt2qq73w97i7y43s2gg` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `reviews` (
  `rating` int NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `comment` varchar(2000) DEFAULT NULL,
  `id` varchar(255) NOT NULL,
  `place_id` varchar(255) NOT NULL,
  `user_id` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_reviews_user_place` (`user_id`,`place_id`),
  KEY `FKcnnkvk0h9vrih5xqxef7sv2r6` (`place_id`),
  CONSTRAINT `FKcgy7qjc1r99dp117y9en6lxye` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`),
  CONSTRAINT `FKcnnkvk0h9vrih5xqxef7sv2r6` FOREIGN KEY (`place_id`) REFERENCES `places` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `trip_places` (
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `id` varchar(255) NOT NULL,
  `place_id` varchar(255) NOT NULL,
  `trip_id` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_trip_places_trip_place` (`trip_id`,`place_id`),
  KEY `FKgn03ug5cxgm3n5v41fs3ohlgg` (`place_id`),
  CONSTRAINT `FKgn03ug5cxgm3n5v41fs3ohlgg` FOREIGN KEY (`place_id`) REFERENCES `places` (`id`),
  CONSTRAINT `FKq5s9ydlwtwbfo0so818xdcge8` FOREIGN KEY (`trip_id`) REFERENCES `trips` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `ugc_links` (
  `created_at` datetime(6) NOT NULL,
  `moderated_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `external_id` varchar(40) NOT NULL,
  `destination` varchar(120) NOT NULL,
  `destination_key` varchar(120) NOT NULL,
  `title` varchar(300) DEFAULT NULL,
  `url` varchar(2000) NOT NULL,
  `id` varchar(255) NOT NULL,
  `place_id` varchar(255) DEFAULT NULL,
  `user_id` varchar(255) NOT NULL,
  `platform` enum('INSTAGRAM','YOUTUBE') NOT NULL,
  `status` enum('ACTIVE','REMOVED') NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ugc_links_user_destination_content` (`user_id`,`destination_key`,`platform`,`external_id`),
  KEY `idx_ugc_links_destination` (`destination_key`,`status`,`created_at`),
  KEY `idx_ugc_links_user_created` (`user_id`,`created_at`),
  KEY `FKbc7hxqqtbwxx5lo86se25hg7a` (`place_id`),
  CONSTRAINT `FK9rv0rm774p6k0m7eiilrw1eod` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`),
  CONSTRAINT `FKbc7hxqqtbwxx5lo86se25hg7a` FOREIGN KEY (`place_id`) REFERENCES `places` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `local_events` (
  `active` bit(1) NOT NULL,
  `end_date` date NOT NULL,
  `latitude` double DEFAULT NULL,
  `longitude` double DEFAULT NULL,
  `start_date` date NOT NULL,
  `start_time` time(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `last_synced_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `source` varchar(40) NOT NULL,
  `category` varchar(60) DEFAULT NULL,
  `city` varchar(120) NOT NULL,
  `city_key` varchar(120) NOT NULL,
  `venue_name` varchar(200) DEFAULT NULL,
  `external_id` varchar(300) NOT NULL,
  `title` varchar(300) NOT NULL,
  `address` varchar(500) DEFAULT NULL,
  `description` varchar(1000) DEFAULT NULL,
  `image_url` varchar(2000) DEFAULT NULL,
  `url` varchar(2000) DEFAULT NULL,
  `id` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_local_events_source_external` (`source`,`external_id`),
  KEY `idx_local_events_city_dates` (`city_key`,`start_date`,`end_date`),
  KEY `idx_local_events_geo` (`latitude`,`longitude`),
  KEY `idx_local_events_active_end` (`active`,`end_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `news_items` (
  `created_at` datetime(6) NOT NULL,
  `published_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `category` varchar(100) NOT NULL,
  `region` varchar(100) NOT NULL,
  `title` varchar(300) NOT NULL,
  `summary` varchar(2000) DEFAULT NULL,
  `id` varchar(255) NOT NULL,
  `source_url` varchar(255) DEFAULT NULL,
  `type` enum('ADVISORY','GENERAL') NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_news_region` (`region`),
  KEY `idx_news_category` (`category`),
  KEY `idx_news_type` (`type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `otp_codes` (
  `consumed` bit(1) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `code` varchar(10) NOT NULL,
  `email` varchar(255) NOT NULL,
  `id` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_otp_email_code_consumed` (`email`,`code`,`consumed`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `static_pages` (
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `content` varchar(4000) NOT NULL,
  `id` varchar(255) NOT NULL,
  `slug` varchar(255) NOT NULL,
  `title` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_static_pages_slug` (`slug`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

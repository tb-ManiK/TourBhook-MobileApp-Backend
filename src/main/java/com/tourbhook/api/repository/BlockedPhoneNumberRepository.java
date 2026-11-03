package com.tourbhook.api.repository;

import com.tourbhook.api.entity.BlockedPhoneNumber;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BlockedPhoneNumberRepository extends JpaRepository<BlockedPhoneNumber, String> {

    boolean existsByPhoneE164(String phoneE164);
}
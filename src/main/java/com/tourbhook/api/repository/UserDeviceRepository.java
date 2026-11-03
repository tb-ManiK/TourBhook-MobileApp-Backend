package com.tourbhook.api.repository;

import com.tourbhook.api.entity.UserDevice;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserDeviceRepository extends JpaRepository<UserDevice, String> {

    long countByDeviceHash(String deviceHash);

    boolean existsByDeviceHashAndUserId(String deviceHash, String userId);
}
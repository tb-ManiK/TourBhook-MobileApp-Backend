package com.tourbhook.api.service;

import com.tourbhook.api.entity.User;

public interface DeviceRegistrationService {

    void assertSignupAllowed(String deviceId);

    void linkDevice(User user, String deviceId);
}
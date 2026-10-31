package com.tourbhook.api.dto.community;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CreateGroupRequest(
        @NotBlank(message = "name is required")
        @Size(max = 100, message = "name must be at most 100 characters")
        String name,

        @Size(max = 500, message = "description must be at most 500 characters")
        String description,

        @NotBlank(message = "city is required")
        @Size(max = 120, message = "city must be at most 120 characters")
        String city,

        @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate startDate,

        @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate endDate,

        Integer maxMembers
) {
}
package com.waitero.back.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ContactDemoRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 150) String venue,
        @NotBlank @Email @Size(max = 254) String email,
        @Size(max = 40) String phone,
        @NotBlank @Size(max = 100) String city,
        @Size(max = 2000) String message,
        @Size(max = 200) String website
) {}

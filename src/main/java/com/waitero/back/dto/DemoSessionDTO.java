package com.waitero.back.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data @Builder
public class DemoSessionDTO {
    private String token;
    private Long restaurantId;
    private Integer tableId;
    private String tablePublicId;
    private String restaurantName;
    private LocalDateTime expiresAt;
}

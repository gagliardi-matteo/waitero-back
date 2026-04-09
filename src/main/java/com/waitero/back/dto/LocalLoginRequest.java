package com.waitero.back.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LocalLoginRequest {
    private String email;
    private String password;
}

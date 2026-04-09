package com.waitero.back.dto.admin;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateRestaurantRequest {
    private String nome;
    private String email;
    private String password;
    private String address;
    private String city;
}

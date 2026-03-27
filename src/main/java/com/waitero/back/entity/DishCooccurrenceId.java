package com.waitero.back.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode
public class DishCooccurrenceId implements Serializable {

    @Column(name = "base_dish_id")
    private Long baseDishId;

    @Column(name = "suggested_dish_id")
    private Long suggestedDishId;
}

package com.waitero.back.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "dish_cooccurrence")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DishCooccurrence {

    @EmbeddedId
    private DishCooccurrenceId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("baseDishId")
    @JoinColumn(name = "base_dish_id")
    private Piatto baseDish;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("suggestedDishId")
    @JoinColumn(name = "suggested_dish_id")
    private Piatto suggestedDish;

    @Column(name = "count", nullable = false)
    private Long count;

    @Column(nullable = false)
    private Double confidence;
}

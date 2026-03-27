package com.waitero.back.repository;

public interface DishCooccurrenceCountProjection {
    Long getBaseDishId();
    Long getSuggestedDishId();
    Long getPairCount();
}

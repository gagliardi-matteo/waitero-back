package com.waitero.back.repository;

import com.waitero.back.entity.ServiceHour;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.DayOfWeek;
import java.util.List;

public interface ServiceHourRepository extends JpaRepository<ServiceHour, Long> {
    List<ServiceHour> findAllByRistoratoreIdAndDayOfWeekOrderByStartTimeAsc(Long ristoratoreId, DayOfWeek dayOfWeek);
    List<ServiceHour> findAllByRistoratoreIdOrderByDayOfWeekAscStartTimeAsc(Long ristoratoreId);
    void deleteAllByRistoratoreId(Long ristoratoreId);
}

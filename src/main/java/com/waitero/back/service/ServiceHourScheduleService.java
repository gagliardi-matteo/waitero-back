package com.waitero.back.service;

import com.waitero.back.entity.ServiceHour;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class ServiceHourScheduleService {

    public boolean isOpenAt(List<ServiceHour> serviceHours, ZonedDateTime now) {
        if (serviceHours == null || serviceHours.isEmpty()) {
            return true;
        }

        DayOfWeek currentDay = now.getDayOfWeek();
        DayOfWeek previousDay = currentDay.minus(1);
        LocalTime currentTime = now.toLocalTime();

        return serviceHours.stream().anyMatch(slot -> matches(slot, currentDay, previousDay, currentTime));
    }

    public void validateNoOverlaps(List<ServiceHour> serviceHours) {
        if (serviceHours == null || serviceHours.isEmpty()) {
            return;
        }

        List<WeekInterval> intervals = new ArrayList<>();
        for (ServiceHour slot : serviceHours) {
            if (slot.getDayOfWeek() == null || slot.getStartTime() == null || slot.getEndTime() == null) {
                throw new RuntimeException("Fascia oraria incompleta");
            }
            if (slot.getStartTime().equals(slot.getEndTime())) {
                throw new RuntimeException("La fascia oraria non puo avere inizio e fine uguali");
            }
            intervals.addAll(toWeekIntervals(slot));
        }

        intervals.sort(Comparator.comparingInt(WeekInterval::startMinute));
        WeekInterval previous = null;
        for (WeekInterval current : intervals) {
            if (previous != null && current.startMinute() < previous.endMinute()) {
                throw new RuntimeException("Le fasce orarie si sovrappongono");
            }
            previous = current;
        }
    }

    public boolean isOvernight(ServiceHour slot) {
        return !slot.getEndTime().isAfter(slot.getStartTime());
    }

    private boolean matches(ServiceHour slot, DayOfWeek currentDay, DayOfWeek previousDay, LocalTime currentTime) {
        boolean overnight = isOvernight(slot);
        if (slot.getDayOfWeek() == currentDay) {
            if (!overnight) {
                return !currentTime.isBefore(slot.getStartTime()) && !currentTime.isAfter(slot.getEndTime());
            }
            return !currentTime.isBefore(slot.getStartTime());
        }

        return overnight
                && slot.getDayOfWeek() == previousDay
                && !currentTime.isAfter(slot.getEndTime());
    }

    private List<WeekInterval> toWeekIntervals(ServiceHour slot) {
        int dayOffset = (slot.getDayOfWeek().getValue() - 1) * 1440;
        int startMinute = dayOffset + toMinutes(slot.getStartTime());
        int endMinute = dayOffset + toMinutes(slot.getEndTime());

        if (slot.getEndTime().isAfter(slot.getStartTime())) {
            return List.of(new WeekInterval(startMinute, endMinute));
        }

        int nextDayOffset = (slot.getDayOfWeek().plus(1).getValue() - 1) * 1440;
        return List.of(
                new WeekInterval(startMinute, dayOffset + 1440),
                new WeekInterval(nextDayOffset, nextDayOffset + toMinutes(slot.getEndTime()))
        );
    }

    private int toMinutes(LocalTime time) {
        return (time.getHour() * 60) + time.getMinute();
    }

    private record WeekInterval(int startMinute, int endMinute) {
    }
}

package com.waitero.back.repository;

import com.waitero.back.entity.TableDevice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface TableDeviceRepository extends JpaRepository<TableDevice, Long> {
    Optional<TableDevice> findByTavoloIdAndDeviceId(Long tableId, String deviceId);
    boolean existsByTavoloIdAndDeviceId(Long tableId, String deviceId);
    void deleteAllByTavoloId(Long tableId);
    long deleteByLastSeenBefore(LocalDateTime cutoff);
}

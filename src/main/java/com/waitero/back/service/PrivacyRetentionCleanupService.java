package com.waitero.back.service;

import com.waitero.back.repository.AdminAuditLogRepository;
import com.waitero.back.repository.EventLogRepository;
import com.waitero.back.repository.StripeWebhookEventRepository;
import com.waitero.back.repository.TableAccessLogRepository;
import com.waitero.back.repository.TableDeviceRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class PrivacyRetentionCleanupService {

    private static final Logger log = LoggerFactory.getLogger(PrivacyRetentionCleanupService.class);

    private final TableAccessLogRepository tableAccessLogRepository;
    private final TableDeviceRepository tableDeviceRepository;
    private final EventLogRepository eventLogRepository;
    private final StripeWebhookEventRepository stripeWebhookEventRepository;
    private final AdminAuditLogRepository adminAuditLogRepository;

    @Value("${app.retention.table-access-log-days:90}")
    private long tableAccessLogDays;

    @Value("${app.retention.table-device-days:180}")
    private long tableDeviceDays;

    @Value("${app.retention.event-log-days:180}")
    private long eventLogDays;

    @Value("${app.retention.stripe-webhook-days:90}")
    private long stripeWebhookDays;

    @Value("${app.retention.admin-audit-days:730}")
    private long adminAuditDays;

    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void cleanupExpiredData() {
        long deletedTableAccessLogs = tableAccessLogRepository.deleteByTimestampBefore(LocalDateTime.now().minusDays(normalize(tableAccessLogDays)));
        long deletedTableDevices = tableDeviceRepository.deleteByLastSeenBefore(LocalDateTime.now().minusDays(normalize(tableDeviceDays)));
        long deletedEventLogs = eventLogRepository.deleteByCreatedAtBefore(LocalDateTime.now().minusDays(normalize(eventLogDays)));
        long deletedWebhookEvents = stripeWebhookEventRepository.deleteByProcessedAtBefore(LocalDateTime.now().minusDays(normalize(stripeWebhookDays)));
        long deletedAdminAuditLogs = adminAuditLogRepository.deleteByCreatedAtBefore(LocalDateTime.now().minusDays(normalize(adminAuditDays)));

        log.info("Privacy retention cleanup completed tableAccessLogsDeleted={} tableDevicesDeleted={} eventLogsDeleted={} webhookEventsDeleted={} adminAuditLogsDeleted={}",
                deletedTableAccessLogs, deletedTableDevices, deletedEventLogs, deletedWebhookEvents, deletedAdminAuditLogs);
    }

    private long normalize(long value) {
        return value <= 0 ? 1 : value;
    }
}

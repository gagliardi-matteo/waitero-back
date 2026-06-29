package com.waitero.back.service;

import com.waitero.back.dto.SecureTableAccessRequest;
import com.waitero.back.entity.Ristoratore;
import com.waitero.back.entity.TableAccessLog;
import com.waitero.back.entity.TableDevice;
import com.waitero.back.entity.Tavolo;
import com.waitero.back.repository.OrdineRepository;
import com.waitero.back.repository.TableAccessLogRepository;
import com.waitero.back.repository.TableDeviceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LocationUnverifiedOrderFlowTest {

    @Mock private TavoloService tavoloService;
    @Mock private OrdineRepository ordineRepository;
    @Mock private TableDeviceRepository tableDeviceRepository;
    @Mock private TableAccessLogRepository tableAccessLogRepository;
    @Mock private OrderStreamService orderStreamService;
    @Mock private PrivacyProtectionService privacyProtectionService;
    @Mock private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Mock private com.waitero.back.repository.OrdineItemRepository ordineItemRepository;
    @Mock private com.waitero.back.repository.OrdinePagamentoRepository ordinePagamentoRepository;
    @Mock private com.waitero.back.repository.OrdinePagamentoAllocazioneRepository ordinePagamentoAllocazioneRepository;
    @Mock private com.waitero.back.repository.RistoratoreRepository ristoratoreRepository;
    @Mock private com.waitero.back.repository.PiattoRepository piattoRepository;
    @Mock private JwtService jwtService;
    @Mock private CustomerDraftService customerDraftService;
    @Mock private UpsellService upsellService;
    @Mock private EventTrackingService eventTrackingService;
    @Mock private ExperimentService experimentService;
    @Mock private com.waitero.back.security.AccessContextService accessContextService;
    @Mock private DishPortionService dishPortionService;
    @Mock private OrderPrintService orderPrintService;

    @InjectMocks private TableAccessService tableAccessService;
    @InjectMocks private OrdineService ordineService;

    @Test
    void tableAccessStoresLocationUnverifiedOnCurrentDeviceWhenPositionIsUnavailable() {
        Ristoratore restaurant = Ristoratore.builder()
                .id(10L)
                .build();
        Tavolo table = Tavolo.builder()
                .id(20L)
                .numero(7)
                .tablePublicId("table-public")
                .qrToken("qr-token")
                .ristoratore(restaurant)
                .build();
        SecureTableAccessRequest request = new SecureTableAccessRequest();
        request.setTablePublicId("table-public");
        request.setQrToken("qr-token");
        request.setDeviceId("device-1");
        request.setLocationUnavailable(true);

        when(tavoloService.resolveActiveTableForAccess("table-public", null, null)).thenReturn(table);
        when(tavoloService.validateQrAccess("qr-token", "10", 7)).thenReturn(true);
        when(tavoloService.isWithinServiceHours(10L)).thenReturn(true);
        when(privacyProtectionService.normalizeDeviceId("device-1")).thenReturn("device-1");
        when(tableDeviceRepository.findFirstByTavoloIdAndDeviceIdOrderByLastSeenDescIdDesc(20L, "device-1")).thenReturn(Optional.empty());

        tableAccessService.validateAndRegister(request);

        ArgumentCaptor<TableDevice> deviceCaptor = ArgumentCaptor.forClass(TableDevice.class);
        verify(tableDeviceRepository).save(deviceCaptor.capture());
        assertThat(deviceCaptor.getValue().getLocationUnverified()).isTrue();
    }

    @Test
    void orderSubmissionTreatsCurrentDeviceLocationUnverifiedAsOrderWarningSource() {
        when(tableDeviceRepository.findFirstByTavoloRistoratoreIdAndTavoloNumeroAndDeviceIdOrderByLastSeenDescIdDesc(10L, 7, "device-1"))
                .thenReturn(Optional.of(TableDevice.builder()
                        .deviceId("device-1")
                        .locationUnverified(true)
                        .build()));

        Boolean result = ReflectionTestUtils.invokeMethod(
                ordineService,
                "resolveLocationUnverified",
                10L,
                7,
                "device-1",
                false
        );

        assertThat(result).isTrue();
    }

    @Test
    void orderSubmissionFallsBackToLastAccessLogWhenDeviceStateIsMissing() {
        when(tableDeviceRepository.findFirstByTavoloRistoratoreIdAndTavoloNumeroAndDeviceIdOrderByLastSeenDescIdDesc(10L, 7, "device-1"))
                .thenReturn(Optional.empty());
        when(tableAccessLogRepository.findFirstByTavoloRistoratoreIdAndTavoloNumeroAndDeviceIdOrderByTimestampDescIdDesc(10L, 7, "device-1"))
                .thenReturn(Optional.of(TableAccessLog.builder()
                        .reason("location_unverified")
                        .build()));

        Boolean result = ReflectionTestUtils.invokeMethod(
                ordineService,
                "resolveLocationUnverified",
                10L,
                7,
                "device-1",
                false
        );

        assertThat(result).isTrue();
    }

    @Test
    void tableAccessBlocksWhenLocationIsProvidedButOutsideAllowedRadius() {
        Ristoratore restaurant = Ristoratore.builder()
                .id(10L)
                .latitude(45.0)
                .longitude(9.0)
                .allowedRadiusMeters(100)
                .build();
        Tavolo table = Tavolo.builder()
                .id(20L)
                .numero(7)
                .tablePublicId("table-public")
                .qrToken("qr-token")
                .ristoratore(restaurant)
                .build();
        SecureTableAccessRequest request = new SecureTableAccessRequest();
        request.setTablePublicId("table-public");
        request.setQrToken("qr-token");
        request.setDeviceId("device-1");
        request.setLatitude(0.0);
        request.setLongitude(0.0);
        request.setLocationUnavailable(false);

        when(tavoloService.resolveActiveTableForAccess("table-public", null, null)).thenReturn(table);
        when(tavoloService.validateQrAccess("qr-token", "10", 7)).thenReturn(true);
        when(tavoloService.isWithinServiceHours(10L)).thenReturn(true);
        when(privacyProtectionService.normalizeDeviceId("device-1")).thenReturn("device-1");
        when(tableDeviceRepository.findFirstByTavoloIdAndDeviceIdOrderByLastSeenDescIdDesc(20L, "device-1")).thenReturn(Optional.empty());
        when(ordineRepository.existsByRistoratoreIdAndTableIdAndStatusIn(eq(10L), eq(7), anyList())).thenReturn(false);

        var response = tableAccessService.validateAndRegister(request);

        assertThat(response.isAllowed()).isFalse();
        assertThat(response.getStatus()).isEqualTo("ACCESS_DENIED_DISTANCE");
        verify(tableDeviceRepository, never()).save(any());
    }
}

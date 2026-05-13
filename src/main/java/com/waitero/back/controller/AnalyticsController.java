package com.waitero.back.controller;

import com.waitero.back.dto.AnalyticsDashboardDTO;
import com.waitero.back.dto.AnalyticsOverviewDTO;
import com.waitero.back.dto.BenchmarkInsightDTO;
import com.waitero.back.dto.DishPerformanceDTO;
import com.waitero.back.dto.ExperimentMetricsDTO;
import com.waitero.back.dto.ExperimentDecision;
import com.waitero.back.dto.RevenueOpportunityDTO;
import com.waitero.back.dto.RevenueKpiDTO;
import com.waitero.back.security.AccessContextService;
import com.waitero.back.service.AnalyticsService;
import com.waitero.back.service.ExperimentIntelligenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;
    private final AccessContextService accessContextService;
    private final ExperimentIntelligenceService experimentIntelligenceService;

    // Ritorna la vista sintetica del locale: KPI principali, trend e stato generale.
    @GetMapping("/overview")
    public AnalyticsOverviewDTO getOverview() {
        return analyticsService.getOverview(accessContextService.getActingRestaurantIdOrThrow());
    }

    // Ritorna il dashboard completo con tutti i blocchi usati dalla pagina analytics.
    @GetMapping("/dashboard")
    public AnalyticsDashboardDTO getDashboard() {
        return analyticsService.getDashboard(accessContextService.getActingRestaurantIdOrThrow());
    }

    // Ritorna le metriche per singolo piatto: views, click, add to cart e conversioni.
    @GetMapping("/dish-performance")
    public List<DishPerformanceDTO> getDishPerformance() {
        return analyticsService.getDishPerformance(accessContextService.getActingRestaurantIdOrThrow());
    }

    // Ritorna le opportunita di fatturato individuate dal motore analytics.
    @GetMapping("/revenue-opportunities")
    public List<RevenueOpportunityDTO> getRevenueOpportunities() {
        return analyticsService.getRevenueOpportunities(accessContextService.getActingRestaurantIdOrThrow());
    }

    // Ritorna il breakdown economico del locale, inclusa la parte upsell.
    @GetMapping("/revenue-kpis")
    public RevenueKpiDTO getRevenueKpis() {
        return analyticsService.getRevenueBreakdown(accessContextService.getActingRestaurantIdOrThrow());
    }

    // Ritorna i risultati degli esperimenti A/B attivi o recenti.
    @GetMapping("/ab-test")
    public ExperimentMetricsDTO getAbTestMetrics() {
        return analyticsService.getExperimentMetrics(accessContextService.getActingRestaurantIdOrThrow());
    }

    // Ritorna la decisione sintetica del motore esperimenti.
    @GetMapping("/experiment-decision")
    public ExperimentDecision getExperimentDecision() {
        return experimentIntelligenceService.evaluateExperiment(accessContextService.getActingRestaurantIdOrThrow());
    }

    // Ritorna confronti con i benchmark usati dalla UI analytics.
    @GetMapping("/benchmarks")
    public List<BenchmarkInsightDTO> getBenchmarkInsights() {
        return analyticsService.getBenchmarkInsights(accessContextService.getActingRestaurantIdOrThrow());
    }
}



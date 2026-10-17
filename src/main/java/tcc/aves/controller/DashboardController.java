package tcc.aves.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tcc.aves.dto.DashboardPeriod;
import tcc.aves.dto.DashboardResponse;
import tcc.aves.service.DashboardService;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping
    public ResponseEntity<DashboardResponse> getDashboard(
            @RequestParam(defaultValue = "7") String period,
            @RequestParam(required = false) String speciesId) {
        return ResponseEntity.ok(dashboardService.getDashboard(
                DashboardPeriod.fromRequest(period), speciesId));
    }
}

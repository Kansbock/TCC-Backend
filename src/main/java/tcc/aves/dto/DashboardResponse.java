package tcc.aves.dto;

import java.util.List;

public record DashboardResponse(
        int totalSightings,
        int uniqueSpecies,
        List<DashboardSpeciesCount> leadingSpecies,
        List<DashboardChartPoint> trend,
        List<DashboardSpeciesCount> species,
        List<DashboardChartPoint> hourly
) {
}

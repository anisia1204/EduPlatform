package ro.upt.eduplatform.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ro.upt.eduplatform.service.CorrelationService;
import ro.upt.eduplatform.service.StatisticsService;

@Controller
@RequiredArgsConstructor
public class WebController {

    private final StatisticsService statisticsService;
    private final CorrelationService correlationService;

    @GetMapping("/statistics/bac")
    public String bacStatistics(
            @RequestParam(required = false, defaultValue = "TM") String county,
            @RequestParam(required = false, defaultValue = "2023") Integer year,
            Model model) {
        var stats = statisticsService.calculateCountyStatistics(county, year);
        var distribution = statisticsService.getGradeDistribution(county, year);
        var trends = statisticsService.getLongitudinalTrends();
        model.addAttribute("stats", stats);
        model.addAttribute("distribution", distribution);
        model.addAttribute("trends", trends);
        model.addAttribute("selectedCounty", county.toUpperCase());
        model.addAttribute("selectedYear", year);
        model.addAttribute("availableYears", statisticsService.getAvailableBacYears());
        model.addAttribute("availableCounties", statisticsService.getAvailableCounties());
        model.addAttribute("pageTitle", "BAC Statistics — " + county.toUpperCase() + " " + year);
        return "bac-statistics";
    }

    @GetMapping("/statistics/en")
    public String enStatistics(
            @RequestParam(required = false, defaultValue = "TM") String county,
            @RequestParam(required = false, defaultValue = "2021") Integer year,
            Model model) {
        var stats = statisticsService.calculateEnCountyStatistics(county, year);
        var trends = statisticsService.getEnLongitudinalTrends();
        model.addAttribute("stats", stats);
        model.addAttribute("trends", trends);
        model.addAttribute("selectedCounty", county.toUpperCase());
        model.addAttribute("selectedYear", year);
        model.addAttribute("availableYears", statisticsService.getAvailableEnYears());
        model.addAttribute("availableCounties", statisticsService.getAvailableCounties());
        model.addAttribute("pageTitle", "National Evaluation — " + county.toUpperCase() + " " + year);
        return "en-statistics";
    }

    @GetMapping("/correlation")
    public String correlation(
            @RequestParam(required = false, defaultValue = "2019") Integer enYear,
            Model model) {
        var cohorts = correlationService.getAllCohortStatistics();
        var countyStats = correlationService.getCountyStatisticsForCohort(enYear);
        var evolution = correlationService.getEvolutionPerCounty(enYear);
        var availableYears = correlationService.getCompleteEnYears();
        model.addAttribute("cohorts", cohorts);
        model.addAttribute("countyStats", countyStats);
        model.addAttribute("evolution", evolution);
        model.addAttribute("selectedEnYear", enYear);
        model.addAttribute("availableEnYears", availableYears);
        model.addAttribute("pageTitle", "EN → BAC Correlation");
        return "correlation";
    }

    @GetMapping("/admin")
    public String admin(Model model) {
        model.addAttribute("pageTitle", "Administration — EduPlatform");
        return "admin";
    }
}
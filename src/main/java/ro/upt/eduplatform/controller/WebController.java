package ro.upt.eduplatform.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ro.upt.eduplatform.service.CorrelationService;
import ro.upt.eduplatform.service.StatisticsService;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class WebController {

    private final StatisticsService statisticsService;
    private final CorrelationService correlationService;

    @GetMapping("/")
    public String dashboard(Model model) {
        model.addAttribute("availableYears", statisticsService.getAvailableBacYears());
        model.addAttribute("availableCounties", statisticsService.getAvailableCounties());
        model.addAttribute("availableEnYears", statisticsService.getAvailableEnYears());
        model.addAttribute("pageTitle", "EduPlatform — Dashboard");
        return "dashboard";
    }

    @GetMapping("/statistics/bac")
    public String bacStatistics(
            @RequestParam(required = false, defaultValue = "TM") String county,
            @RequestParam(required = false, defaultValue = "2023") Integer year,
            Model model) {
        var stats = statisticsService.calculateCountyStatistics(county, year);
        var distribution = statisticsService.getGradeDistribution(county, year);
        var trends = statisticsService.getLongitudinalTrends();
        var countyTrend = statisticsService.getCountyLongitudinalTrend(county);
        model.addAttribute("stats", stats);
        model.addAttribute("distribution", distribution);
        model.addAttribute("trends", trends);
        model.addAttribute("countyTrend", countyTrend);
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

    @GetMapping("/comparison")
    public String comparison(
            @RequestParam(required = false, defaultValue = "2023") Integer year,
            Model model) {
        var comparison = statisticsService.compareCounties(year);
        model.addAttribute("comparison", comparison);
        model.addAttribute("selectedYear", year);
        model.addAttribute("availableYears", statisticsService.getAvailableBacYears());
        model.addAttribute("pageTitle", "County Comparison — " + year);
        return "comparison";
    }

    @GetMapping("/prediction")
    public String prediction(Model model) {
        model.addAttribute("counties", statisticsService.getAvailableCounties());
        model.addAttribute("profiles", List.of(
                "Mathematics-Informatics", "Natural Sciences",
                "Philology", "Social Sciences", "Technical", "Arts", "Sports"
        ));
        model.addAttribute("pageTitle", "BAC Outcome Prediction");
        return "prediction";
    }

    @GetMapping("/environment")
    public String environment(
            @RequestParam(required = false, defaultValue = "2023") Integer year,
            Model model) {
        var nationalStats = statisticsService.getBacEnvironmentStatistics(year);
        var countyStats   = statisticsService.getBacCountyEnvironmentStatistics(year);
        var bacTrends     = statisticsService.getBacEnvironmentTrends();
        var enTrends      = statisticsService.getEnEnvironmentTrends();
        model.addAttribute("nationalStats", nationalStats);
        model.addAttribute("countyStats", countyStats);
        model.addAttribute("bacTrends", bacTrends);
        model.addAttribute("enTrends", enTrends);
        model.addAttribute("selectedYear", year);
        model.addAttribute("availableYears", statisticsService.getAvailableBacYears());
        model.addAttribute("pageTitle", "Urban vs Rural — " + year);
        return "environment";
    }

    @GetMapping("/admin")
    public String admin(Model model) {
        model.addAttribute("pageTitle", "Administration — EduPlatform");
        return "admin";
    }
}
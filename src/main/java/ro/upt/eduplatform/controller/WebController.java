package ro.upt.eduplatform.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import ro.upt.eduplatform.service.BacScraperService;

@Controller
@RequiredArgsConstructor
public class WebController {

    @GetMapping("/admin")
    public String admin(Model model) {
        model.addAttribute("judete", BacScraperService.JUDETE);
        model.addAttribute("ani", BacScraperService.ANI);
        model.addAttribute("pageTitle", "Admin - EduPlatform");
        return "admin";
    }
}

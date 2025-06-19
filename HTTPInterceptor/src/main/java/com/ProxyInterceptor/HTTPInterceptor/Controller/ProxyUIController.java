package com.ProxyInterceptor.HTTPInterceptor.Controller;

import com.ProxyInterceptor.HTTPInterceptor.Model.RecordingState;
import com.ProxyInterceptor.HTTPInterceptor.Service.ProxyStateService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class ProxyUIController {

    private final ProxyStateService stateService;

    public ProxyUIController(ProxyStateService stateService) {
        this.stateService = stateService;
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("mode", stateService.getMode());
        model.addAttribute("folder", stateService.getOutputFolder().toString());
        return "index";
    }

    @PostMapping("/setMode")
    public String setMode(@RequestParam("mode") RecordingState mode) {
        stateService.setMode(mode);
        return "redirect:/";
    }

    @PostMapping("/setFolder")
    public String setFolder(@RequestParam("folder") String folder) {
        stateService.setOutputFolder(folder);
        return "redirect:/";
    }
}

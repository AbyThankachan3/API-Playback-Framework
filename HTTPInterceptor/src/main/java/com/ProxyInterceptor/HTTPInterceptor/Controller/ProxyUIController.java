package com.ProxyInterceptor.HTTPInterceptor.Controller;

import com.ProxyInterceptor.HTTPInterceptor.Model.RecordingState;
import com.ProxyInterceptor.HTTPInterceptor.Service.ProxyStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

@Controller
public class ProxyUIController {

    private static final Logger logger = LoggerFactory.getLogger(ProxyUIController.class);
    private final ProxyStateService stateService;

    public ProxyUIController(ProxyStateService stateService) {
        this.stateService = stateService;
    }

    @GetMapping("/")
    public String index(Model model) {

        RecordingState mode = stateService.getMode();
        String currentMode = stateService.getReplayMode();
        List<String> captureFields = stateService.getCaptureFields();

        model.addAttribute("mode", mode);
        model.addAttribute("currentMode", currentMode);
        model.addAttribute("captureFields", captureFields);


        return "index";
    }

    @PostMapping("/setMode")
    public String setMode(@RequestParam("mode") RecordingState mode) {
        try {
            stateService.setMode(mode);
            logger.info("Mode successfully set to: {}", stateService.getMode());
        } catch (Exception e) {
            logger.error("Error setting mode to {}: {}", mode, e.getMessage(), e);
            throw e;
        }

        return "redirect:/";
    }

//    @PostMapping("/setFolder")
//    public String setFolder(@RequestParam("folder") String folder) {
//        stateService.setOutputFolder(folder);
//        return "redirect:/";
//    }

    @PostMapping("/setReplayMode")
    public String setReplayMode(@RequestParam("replayMode") String replayMode) {

        try {
            stateService.setReplayMode(replayMode);  // either "db" or "vector"
            logger.info("Replay mode successfully set to: {}", replayMode);
        } catch (Exception e) {
            logger.error("Error setting replay mode to {}: {}", replayMode, e.getMessage(), e);
            throw e;
        }

        return "redirect:/";
    }

    @PostMapping("/setCaptureFields")
    public String setCaptureFields(@RequestParam(value = "captureFields", required = false) String[] captureFields) {
        List<String> fieldList = captureFields != null ? Arrays.asList(captureFields) : Arrays.asList();

        try {
            stateService.setCaptureFields(fieldList);
            logger.info("Capture fields successfully set to: {}", fieldList);
        } catch (Exception e) {
            logger.error("Error setting capture fields to {}: {}", fieldList, e.getMessage(), e);
        }

        return "redirect:/";
    }
}
package com.xcweb.controller;

import com.xcweb.service.ModelService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/models")
public class ModelController {

    private final ModelService modelService;

    public ModelController(ModelService modelService) {
        this.modelService = modelService;
    }

    @GetMapping
    public Map<String, Object> listAvailableModels() {
        return modelService.getPublicModelsResponse();
    }

    @GetMapping("/manage")
    public Map<String, Object> listAllManageableModels() {
        return modelService.getAdminModelsResponse();
    }

    @PostMapping
    public Map<String, Object> addModel(@RequestBody Map<String, Object> body) {
        return modelService.addModel(body);
    }

    @PutMapping
    public Map<String, Object> updateModel(@RequestBody Map<String, Object> body) {
        return modelService.updateModelConfig(null, body);
    }

    @PutMapping("/{*modelId}")
    public Map<String, Object> updateModelById(@PathVariable("modelId") String modelId,
                                               @RequestBody(required = false) Map<String, Object> body) {
        return modelService.updateModelConfig(modelId, body);
    }

    @DeleteMapping
    public Map<String, Object> deleteModelByQueryOrBody(@RequestParam(value = "id", required = false) String idParam,
                                                        @RequestBody(required = false) Map<String, Object> body) {
        String targetId = idParam;
        if ((targetId == null || targetId.isBlank()) && body != null) {
            Object fromBody = body.getOrDefault("id", body.get("modelId"));
            if (fromBody != null) {
                targetId = String.valueOf(fromBody);
            }
        }
        return modelService.deleteModel(targetId);
    }

    @DeleteMapping("/{*modelId}")
    public Map<String, Object> deleteModelByPath(@PathVariable("modelId") String modelId) {
        return modelService.deleteModel(modelId);
    }

    @PostMapping("/restore-presets")
    public Map<String, Object> restorePresetModels() {
        return modelService.restorePresetModels();
    }

    @PostMapping("/import-env")
    public Map<String, Object> importEnvironmentVariables(@RequestBody(required = false) Map<String, Object> body,
                                                          @RequestParam(value = "consent", required = false) Boolean paramConsent) {
        boolean consent = false;
        if (paramConsent != null && paramConsent) {
            consent = true;
        } else if (body != null && Boolean.TRUE.equals(body.get("consent"))) {
            consent = true;
        }
        return modelService.importSystemEnvironmentVariables(consent);
    }
}

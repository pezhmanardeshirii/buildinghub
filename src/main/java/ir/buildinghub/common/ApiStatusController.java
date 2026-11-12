package ir.buildinghub.common;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class ApiStatusController {
    @GetMapping("/api/v1/status")
    public Map<String, String> status() {
        return Map.of("application", "BuildingHub", "status", "UP");
    }
}

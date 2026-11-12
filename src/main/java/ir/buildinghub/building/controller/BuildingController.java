package ir.buildinghub.building.controller;

import ir.buildinghub.building.dto.CreateBuildingRequest;
import ir.buildinghub.building.entity.Building;
import ir.buildinghub.building.service.BuildingService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/buildings")
public class BuildingController {

    private final BuildingService buildingService;

    public BuildingController(BuildingService buildingService) {
        this.buildingService = buildingService;
    }

    @GetMapping
    public List<Building> findAll() {
        return buildingService.findAll();
    }

    @PostMapping
    public Building create(@Valid @RequestBody CreateBuildingRequest request) {
        return buildingService.create(request);
    }

    @GetMapping("/{id}")
    public Building findById(@PathVariable Long id) {
        return buildingService.findById(id);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        buildingService.deleteById(id);
    }
}

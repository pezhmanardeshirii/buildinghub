
package ir.buildinghub.building.service;

import ir.buildinghub.building.dto.CreateBuildingRequest;
import ir.buildinghub.building.entity.Building;
import ir.buildinghub.building.repository.BuildingRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class BuildingService {

    private final BuildingRepository buildingRepository;

    public BuildingService(BuildingRepository buildingRepository) {
        this.buildingRepository = buildingRepository;
    }

    public List<Building> findAll() {
        return buildingRepository.findAll();
    }

    public Building create (CreateBuildingRequest request){
        Building building = new Building(
                request.name(),
                request.address()
        );
        return buildingRepository.save(building);
    }

    public Building findById(Long id) {
        return buildingRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("building not found id: " + id));
    }

    public void deleteById(Long id) {
        Building building = findById(id);
        buildingRepository.delete(building);
    }
}

package tcc.aves.service;

import org.springframework.stereotype.Service;
import tcc.aves.dto.SpeciesRequest;
import tcc.aves.dto.SpeciesResponse;
import tcc.aves.model.Species;
import tcc.aves.repository.SpeciesRepository;

import java.util.List;

@Service
public class SpeciesService {

    private final SpeciesRepository speciesRepository;

    public SpeciesService(SpeciesRepository speciesRepository) {
        this.speciesRepository = speciesRepository;
    }

    public SpeciesResponse create(SpeciesRequest request) {
        if (speciesRepository.existsByScientificName(request.scientificName())) {
            throw new IllegalArgumentException("Nome científico já cadastrado");
        }

        Species species = new Species();
        species.setName(request.name());
        species.setScientificName(request.scientificName());
        species.setDescription(request.description());
        species.setTips(request.tips());
        species.setImageUrl(request.imageUrl());

        return toResponse(speciesRepository.save(species));
    }

    public List<SpeciesResponse> findAll() {
        return speciesRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    public SpeciesResponse findById(Long id) {
        return toResponse(getOrThrow(id));
    }

    public List<SpeciesResponse> search(String name) {
        return speciesRepository.findByNameContainingIgnoreCase(name).stream()
                .map(this::toResponse)
                .toList();
    }

    public SpeciesResponse update(Long id, SpeciesRequest request) {
        Species species = getOrThrow(id);

        speciesRepository.findByScientificName(request.scientificName())
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("Nome científico já cadastrado por outra espécie");
                });

        species.setName(request.name());
        species.setScientificName(request.scientificName());
        species.setDescription(request.description());
        species.setTips(request.tips());
        species.setImageUrl(request.imageUrl());

        return toResponse(speciesRepository.save(species));
    }

    public void delete(Long id) {
        if (!speciesRepository.existsById(id)) {
            throw new IllegalArgumentException("Espécie não encontrada");
        }
        speciesRepository.deleteById(id);
    }

    private Species getOrThrow(Long id) {
        return speciesRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Espécie não encontrada"));
    }

    private SpeciesResponse toResponse(Species species) {
        return new SpeciesResponse(
                species.getId(),
                species.getName(),
                species.getScientificName(),
                species.getDescription(),
                species.getTips(),
                species.getImageUrl(),
                species.getCreatedAt(),
                species.getUpdatedAt()
        );
    }
}

package tcc.aves.service;

import org.springframework.stereotype.Service;
import tcc.aves.dto.SpeciesRequest;
import tcc.aves.dto.SpeciesResponse;
import tcc.aves.exception.ResourceNotFoundException;
import tcc.aves.repository.SpeciesRecord;
import tcc.aves.repository.SpeciesRepository;

import java.util.List;

@Service
public class SpeciesService {

    private final SpeciesRepository speciesRepository;

    public SpeciesService(SpeciesRepository speciesRepository) {
        this.speciesRepository = speciesRepository;
    }

    public SpeciesResponse create(SpeciesRequest request) {
        SpeciesRecord saved = speciesRepository.create(
                request.name(), request.scientificName(), request.description(), request.tips(), request.imageUrl());
        return toResponse(saved);
    }

    public List<SpeciesResponse> findAll() {
        return speciesRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    public SpeciesResponse findById(String id) {
        return speciesRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Espécie não encontrada"));
    }

    public List<SpeciesResponse> search(String query) {
        return speciesRepository.search(query).stream()
                .map(this::toResponse)
                .toList();
    }

    public SpeciesResponse update(String id, SpeciesRequest request) {
        SpeciesRecord saved = speciesRepository.update(
                id, request.name(), request.scientificName(), request.description(), request.tips(), request.imageUrl());
        return toResponse(saved);
    }

    public void delete(String id) {
        speciesRepository.delete(id);
    }

    private SpeciesResponse toResponse(SpeciesRecord species) {
        return new SpeciesResponse(
                species.id(),
                species.name(),
                species.scientificName(),
                species.description(),
                species.tips(),
                species.imageUrl(),
                species.createdAt(),
                species.updatedAt()
        );
    }
}

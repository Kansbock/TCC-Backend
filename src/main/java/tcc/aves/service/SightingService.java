package tcc.aves.service;

import org.springframework.stereotype.Service;
import tcc.aves.dto.*;
import tcc.aves.model.Sighting;
import tcc.aves.model.SightingSpecies;
import tcc.aves.model.Species;
import tcc.aves.repository.SightingRepository;
import tcc.aves.repository.SpeciesRepository;

import java.time.LocalDate;
import java.util.List;

@Service
public class SightingService {

    private final SightingRepository sightingRepository;
    private final SpeciesRepository speciesRepository;

    public SightingService(SightingRepository sightingRepository,
                           SpeciesRepository speciesRepository) {
        this.sightingRepository = sightingRepository;
        this.speciesRepository = speciesRepository;
    }

    public SightingResponse create(SightingRequest request) {
        Sighting sighting = new Sighting();
        sighting.setDate(request.date());
        sighting.setTime(request.time());
        sighting.setImageUrl(request.imageUrl());

        List<SightingSpecies> entries = buildEntries(request.species(), sighting);
        sighting.getSpecies().addAll(entries);

        return toResponse(sightingRepository.save(sighting));
    }

    public List<SightingResponse> findAll() {
        return sightingRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    public SightingResponse findById(Long id) {
        return toResponse(getOrThrow(id));
    }

    public List<SightingResponse> findBySpecies(Long speciesId) {
        return sightingRepository.findBySpeciesId(speciesId).stream()
                .map(this::toResponse)
                .toList();
    }

    public List<SightingResponse> findByDateRange(LocalDate from, LocalDate to) {
        return sightingRepository.findByDateBetween(from, to).stream()
                .map(this::toResponse)
                .toList();
    }

    public SightingResponse update(Long id, SightingRequest request) {
        Sighting sighting = getOrThrow(id);
        sighting.setDate(request.date());
        sighting.setTime(request.time());
        sighting.setImageUrl(request.imageUrl());

        sighting.getSpecies().clear();
        sighting.getSpecies().addAll(buildEntries(request.species(), sighting));

        return toResponse(sightingRepository.save(sighting));
    }

    public void delete(Long id) {
        if (!sightingRepository.existsById(id)) {
            throw new IllegalArgumentException("Avistamento não encontrado");
        }
        sightingRepository.deleteById(id);
    }

    private List<SightingSpecies> buildEntries(List<SightingSpeciesRequest> requests,
                                               Sighting sighting) {
        return requests.stream().map(req -> {
            Species species = speciesRepository.findById(req.speciesId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Espécie não encontrada: id " + req.speciesId()));

            SightingSpecies entry = new SightingSpecies();
            entry.setSighting(sighting);
            entry.setSpecies(species);
            entry.setQuantity(req.quantity());
            entry.setGender(req.gender());
            return entry;
        }).toList();
    }

    private Sighting getOrThrow(Long id) {
        return sightingRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Avistamento não encontrado"));
    }

    private SightingSpeciesResponse toSpeciesEntry(SightingSpecies entry) {
        Species s = entry.getSpecies();
        return new SightingSpeciesResponse(
                entry.getId(),
                new SpeciesResponse(
                        s.getId(),
                        s.getName(),
                        s.getScientificName(),
                        s.getDescription(),
                        s.getTips(),
                        s.getImageUrl(),
                        s.getCreatedAt(),
                        s.getUpdatedAt()
                ),
                entry.getQuantity(),
                entry.getGender()
        );
    }

    private SightingResponse toResponse(Sighting sighting) {
        return new SightingResponse(
                sighting.getId(),
                sighting.getSpecies().stream().map(this::toSpeciesEntry).toList(),
                sighting.getDate(),
                sighting.getTime(),
                sighting.getImageUrl(),
                sighting.getCreatedAt(),
                sighting.getUpdatedAt()
        );
    }
}

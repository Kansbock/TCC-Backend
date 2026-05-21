package tcc.aves.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tcc.aves.dto.SpeciesRequest;
import tcc.aves.dto.SpeciesResponse;
import tcc.aves.service.SpeciesService;

import java.util.List;

@RestController
@RequestMapping("/api/species")
public class SpeciesController {

    private final SpeciesService speciesService;

    public SpeciesController(SpeciesService speciesService) {
        this.speciesService = speciesService;
    }

    @PostMapping
    public ResponseEntity<SpeciesResponse> create(@Valid @RequestBody SpeciesRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(speciesService.create(request));
    }

    @GetMapping
    public ResponseEntity<List<SpeciesResponse>> findAll(
            @RequestParam(required = false) String name) {
        if (name != null && !name.isBlank()) {
            return ResponseEntity.ok(speciesService.search(name));
        }
        return ResponseEntity.ok(speciesService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<SpeciesResponse> findById(@PathVariable Long id) {
        return ResponseEntity.ok(speciesService.findById(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<SpeciesResponse> update(@PathVariable Long id,
                                                   @Valid @RequestBody SpeciesRequest request) {
        return ResponseEntity.ok(speciesService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        speciesService.delete(id);
        return ResponseEntity.noContent().build();
    }
}

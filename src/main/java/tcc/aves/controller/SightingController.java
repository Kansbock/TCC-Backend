package tcc.aves.controller;

import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tcc.aves.dto.SightingRequest;
import tcc.aves.dto.SightingResponse;
import tcc.aves.service.SightingService;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/sightings")
public class SightingController {

    private final SightingService sightingService;

    public SightingController(SightingService sightingService) {
        this.sightingService = sightingService;
    }

    @PostMapping
    public ResponseEntity<SightingResponse> create(@Valid @RequestBody SightingRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(sightingService.create(request));
    }

    @GetMapping
    public ResponseEntity<List<SightingResponse>> findAll(
            @RequestParam(required = false) Long speciesId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        if (speciesId != null) {
            return ResponseEntity.ok(sightingService.findBySpecies(speciesId));
        }
        if (from != null && to != null) {
            return ResponseEntity.ok(sightingService.findByDateRange(from, to));
        }
        return ResponseEntity.ok(sightingService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<SightingResponse> findById(@PathVariable Long id) {
        return ResponseEntity.ok(sightingService.findById(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<SightingResponse> update(@PathVariable Long id,
                                                    @Valid @RequestBody SightingRequest request) {
        return ResponseEntity.ok(sightingService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        sightingService.delete(id);
        return ResponseEntity.noContent().build();
    }
}

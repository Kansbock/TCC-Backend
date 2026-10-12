package tcc.aves.controller;

import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tcc.aves.dto.CursorPage;
import tcc.aves.dto.SightingRequest;
import tcc.aves.dto.SightingResponse;
import tcc.aves.service.SightingService;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/sightings")
public class SightingController {

    private final SightingService sightingService;

    public SightingController(SightingService sightingService) {
        this.sightingService = sightingService;
    }

    @PostMapping
    public ResponseEntity<SightingResponse> create(
            @Valid @RequestBody SightingRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        SightingResponse response = (idempotencyKey == null || idempotencyKey.isBlank())
                ? sightingService.create(request)
                : sightingService.create(request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /** Filtros combináveis; {@code from}/{@code to} devem ser enviados juntos. */
    @GetMapping
    public ResponseEntity<CursorPage<SightingResponse>> query(
            @RequestParam(required = false) String speciesId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(sightingService.query(speciesId, from, to, cursor, limit));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SightingResponse> findById(@PathVariable String id) {
        return ResponseEntity.ok(sightingService.findById(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<SightingResponse> update(@PathVariable String id,
                                                    @Valid @RequestBody SightingRequest request) {
        return ResponseEntity.ok(sightingService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        sightingService.delete(id);
        return ResponseEntity.noContent().build();
    }
}

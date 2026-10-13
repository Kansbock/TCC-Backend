package tcc.aves.controller;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tcc.aves.dto.CursorPage;
import tcc.aves.dto.SightingRequest;
import tcc.aves.dto.SightingResponse;
import tcc.aves.dto.SightingUpdateRequest;
import tcc.aves.service.SightingService;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/sightings")
public class SightingController {

    private final SightingService sightingService;
    @Value("${app.sightings.writes-paused:false}")
    private boolean writesPaused;

    public SightingController(SightingService sightingService) {
        this.sightingService = sightingService;
    }

    @PostMapping
    public ResponseEntity<SightingResponse> create(
            @Valid @RequestBody SightingRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        requireWritesEnabled();
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
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Boolean includeUnidentified,
            @RequestParam(required = false) String sort) {
        return ResponseEntity.ok(sightingService.query(
                speciesId, from, to, cursor, limit, Boolean.TRUE.equals(includeUnidentified), sort));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SightingResponse> findById(@PathVariable String id) {
        return ResponseEntity.ok(sightingService.findById(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<SightingResponse> update(@PathVariable String id,
                                                    @Valid @RequestBody SightingUpdateRequest request) {
        requireWritesEnabled();
        return ResponseEntity.ok(request.imageUrls() == null
                ? sightingService.update(id, new SightingRequest(request.speciesIds(), request.date(), request.time(), request.imageUrl()))
                : sightingService.updateGallery(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        requireWritesEnabled();
        sightingService.delete(id);
        return ResponseEntity.noContent().build();
    }

    private void requireWritesEnabled() {
        if (writesPaused) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Avistamentos temporariamente indisponíveis para escrita durante manutenção");
    }
}

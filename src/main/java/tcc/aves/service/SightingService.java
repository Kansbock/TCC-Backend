package tcc.aves.service;

import org.springframework.stereotype.Service;
import tcc.aves.config.SightingProperties;
import tcc.aves.dto.CursorPage;
import tcc.aves.dto.SightingRequest;
import tcc.aves.dto.SightingResponse;
import tcc.aves.dto.SpeciesResponse;
import tcc.aves.exception.ResourceNotFoundException;
import tcc.aves.repository.SightingPage;
import tcc.aves.repository.SightingQuery;
import tcc.aves.repository.SightingRecord;
import tcc.aves.repository.SightingRepository;
import tcc.aves.repository.SightingSort;
import tcc.aves.repository.SpeciesRecord;
import tcc.aves.repository.SpeciesRepository;

import java.time.LocalDate;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

@Service
public class SightingService {

    private static final Set<String> UNIDENTIFIED_SPECIES_NAMES = Set.of(
            "outros", "ave nao identificada", "especie nao identificada");

    private final SightingRepository sightingRepository;
    private final SpeciesRepository speciesRepository;
    private final SightingProperties properties;

    public SightingService(SightingRepository sightingRepository,
                           SpeciesRepository speciesRepository,
                           SightingProperties properties) {
        this.sightingRepository = sightingRepository;
        this.speciesRepository = speciesRepository;
        this.properties = properties;
    }

    public SightingResponse create(SightingRequest request) {
        validateSpeciesIds(request.speciesIds());
        SightingRecord saved = sightingRepository.create(
                request.speciesIds(), request.date(), request.time(), request.imageUrl());
        return toResponse(saved);
    }

    /**
     * Igual a {@link #create(SightingRequest)}, mas associada a uma chave de
     * idempotência: uma repetição com a mesma chave e o mesmo payload
     * devolve o avistamento original em vez de criar um duplicado.
     */
    public SightingResponse create(SightingRequest request, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return create(request);
        }
        validateSpeciesIds(request.speciesIds());
        SightingRecord saved = sightingRepository.createIdempotent(
                idempotencyKey, request.speciesIds(), request.date(), request.time(), request.imageUrl());
        return toResponse(saved);
    }

    public SightingResponse findById(String id) {
        return sightingRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Avistamento não encontrado"));
    }

    public SightingResponse update(String id, SightingRequest request) {
        validateSpeciesIds(request.speciesIds());
        SightingRecord saved = sightingRepository.update(
                id, request.speciesIds(), request.date(), request.time(), request.imageUrl());
        return toResponse(saved);
    }

    public void delete(String id) {
        sightingRepository.delete(id);
    }

    /**
     * Lista avistamentos do mais recente para o mais antigo. {@code speciesId},
     * {@code from}/{@code to} e {@code cursor} são opcionais e combináveis;
     * {@code from}/{@code to} devem ser enviados juntos e com {@code from <= to}.
     */
    public CursorPage<SightingResponse> query(String speciesId, LocalDate from, LocalDate to,
                                              String cursor, Integer limit,
                                              boolean includeUnidentified, String sortParameter) {
        validateDateRange(from, to);
        int effectiveLimit = resolveLimit(limit);
        SightingSort sort = SightingSort.fromRequest(sortParameter);

        // Selecting a specific species always takes precedence over the
        // checkbox. Otherwise, records classified as the special "Outros"
        // species stay hidden unless the user explicitly includes them.
        Set<String> unidentifiedSpeciesIds = speciesId == null && !includeUnidentified
                ? findUnidentifiedSpeciesIds()
                : null;

        List<SightingResponse> items = new ArrayList<>(effectiveLimit);
        String nextCursor = cursor;
        boolean hasMore;
        do {
            int remaining = effectiveLimit - items.size();
            SightingPage page = sightingRepository.query(
                    new SightingQuery(speciesId, from, to, nextCursor, remaining, sort));
            List<SightingResponse> hydrated = hydrate(page.items());
            if (unidentifiedSpeciesIds != null && !unidentifiedSpeciesIds.isEmpty()) {
                hydrated = hydrated.stream()
                        .filter(item -> item.species().stream()
                                .noneMatch(species -> unidentifiedSpeciesIds.contains(species.id())))
                        .toList();
            }
            items.addAll(hydrated);
            nextCursor = page.nextCursor();
            hasMore = page.hasMore();
        } while (items.size() < effectiveLimit && hasMore);

        return new CursorPage<>(List.copyOf(items), nextCursor, hasMore);
    }

    public CursorPage<SightingResponse> query(String speciesId, LocalDate from, LocalDate to,
                                              String cursor, Integer limit) {
        return query(speciesId, from, to, cursor, limit, false, null);
    }

    private Set<String> findUnidentifiedSpeciesIds() {
        return speciesRepository.findAll().stream()
                .filter(species -> UNIDENTIFIED_SPECIES_NAMES.contains(normalizeSpeciesName(species.name())))
                .map(SpeciesRecord::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private String normalizeSpeciesName(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private void validateSpeciesIds(List<String> speciesIds) {
        Set<String> distinct = new LinkedHashSet<>(speciesIds);
        if (distinct.size() != speciesIds.size()) {
            throw new IllegalArgumentException("A lista de espécies não pode conter ids duplicados");
        }
        if (speciesIds.size() > properties.maxSpeciesPerSighting()) {
            throw new IllegalArgumentException(
                    "Um avistamento pode referenciar no máximo " + properties.maxSpeciesPerSighting() + " espécies");
        }
    }

    private void validateDateRange(LocalDate from, LocalDate to) {
        if ((from == null) != (to == null)) {
            throw new IllegalArgumentException("Os filtros 'from' e 'to' devem ser informados juntos");
        }
        if (from != null && from.isAfter(to)) {
            throw new IllegalArgumentException("O filtro 'from' deve ser anterior ou igual a 'to'");
        }
    }

    private int resolveLimit(Integer limit) {
        if (limit == null) {
            return properties.defaultPageSize();
        }
        if (limit < 1 || limit > properties.maxPageSize()) {
            throw new IllegalArgumentException(
                    "O parâmetro 'limit' deve estar entre 1 e " + properties.maxPageSize());
        }
        return limit;
    }

    private List<SightingResponse> hydrate(List<SightingRecord> records) {
        if (records.isEmpty()) {
            return List.of();
        }
        Set<String> speciesIds = new LinkedHashSet<>();
        records.forEach(record -> speciesIds.addAll(record.speciesIds()));

        Map<String, SpeciesRecord> speciesById = speciesRepository.findAllByIds(speciesIds);
        return records.stream().map(record -> toResponse(record, speciesById)).toList();
    }

    private SightingResponse toResponse(SightingRecord record) {
        Map<String, SpeciesRecord> speciesById = speciesRepository.findAllByIds(record.speciesIds());
        return toResponse(record, speciesById);
    }

    private SightingResponse toResponse(SightingRecord record, Map<String, SpeciesRecord> speciesById) {
        List<SpeciesResponse> species = record.speciesIds().stream()
                .map(speciesId -> resolveSpecies(speciesId, speciesById))
                .toList();

        return new SightingResponse(
                record.id(),
                species,
                record.date(),
                record.time(),
                record.imageUrl(),
                record.createdAt(),
                record.updatedAt()
        );
    }

    /**
     * Toda espécie referenciada por um avistamento deve existir — a
     * integridade é garantida transacionalmente na escrita. Se ainda assim
     * uma referência não resolver, isso é reportado como inconsistência
     * interna (500 + log), nunca omitido silenciosamente da resposta.
     */
    private SpeciesResponse resolveSpecies(String speciesId, Map<String, SpeciesRecord> speciesById) {
        SpeciesRecord species = speciesById.get(speciesId);
        if (species == null) {
            throw new IllegalStateException(
                    "Inconsistência de dados: espécie referenciada não encontrada (id " + speciesId + ")");
        }
        return toSpeciesResponse(species);
    }

    private SpeciesResponse toSpeciesResponse(SpeciesRecord species) {
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

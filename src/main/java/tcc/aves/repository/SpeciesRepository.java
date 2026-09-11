package tcc.aves.repository;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Port de persistência de espécies. Sem dependência de JPA/DynamoDB na
 * assinatura — apenas as operações usadas pelos services.
 */
public interface SpeciesRepository {

    /** Lança {@code DuplicateResourceException} se o nome científico já existir. */
    SpeciesRecord create(String name, String scientificName, String description, String tips, String imageUrl);

    List<SpeciesRecord> findAll();

    Optional<SpeciesRecord> findById(String id);

    /** Busca case-insensitive por trecho do nome comum ou científico. */
    List<SpeciesRecord> search(String query);

    /**
     * Retorna as espécies encontradas entre os ids informados, indexadas por
     * id. Ids inexistentes simplesmente não aparecem no mapa resultante — cabe
     * ao chamador validar que todos os ids esperados foram encontrados.
     */
    Map<String, SpeciesRecord> findAllByIds(Collection<String> ids);

    /**
     * Lança {@code ResourceNotFoundException} se a espécie não existir ou
     * {@code DuplicateResourceException} se o novo nome científico já
     * pertencer a outra espécie.
     */
    SpeciesRecord update(String id, String name, String scientificName, String description, String tips, String imageUrl);

    /**
     * Lança {@code ResourceNotFoundException} se a espécie não existir ou
     * {@code ResourceInUseException} se {@code referenceCount > 0}.
     */
    void delete(String id);
}

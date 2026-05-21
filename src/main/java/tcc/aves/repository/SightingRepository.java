package tcc.aves.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tcc.aves.model.Sighting;

import java.time.LocalDate;
import java.util.List;

public interface SightingRepository extends JpaRepository<Sighting, Long> {

    @Query("SELECT DISTINCT s FROM Sighting s JOIN s.species ss WHERE ss.species.id = :speciesId")
    List<Sighting> findBySpeciesId(@Param("speciesId") Long speciesId);

    List<Sighting> findByDate(LocalDate date);

    List<Sighting> findByDateBetween(LocalDate from, LocalDate to);
}

package de.pia.inventur.backend.repository;

import java.util.UUID;
import de.pia.inventur.backend.entity.ArtikelEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArtikelRepository extends JpaRepository<ArtikelEntity, UUID> {
}

package com.ledgermind.ledger;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JournalCheckpointRepository extends JpaRepository<JournalCheckpoint, Long> {

    /** El ultimo checkpoint firmado. */
    Optional<JournalCheckpoint> findTopByOrderByIdDesc();
}

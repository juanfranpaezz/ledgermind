package com.ledgermind.ledger;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostingHashRepository extends JpaRepository<PostingHash, Long> {

    /** La cabeza de la cadena (el ultimo eslabon encadenado). */
    Optional<PostingHash> findTopByOrderBySeqDesc();

    /** Toda la cadena en orden, para verificar. */
    List<PostingHash> findAllByOrderBySeqAsc();
}

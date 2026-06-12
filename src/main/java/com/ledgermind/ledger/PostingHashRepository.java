package com.ledgermind.ledger;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostingHashRepository extends JpaRepository<PostingHash, Long> {

    /** La cabeza de la cadena (el ultimo eslabon encadenado). */
    Optional<PostingHash> findTopByOrderBySeqDesc();

    /** Un eslabon puntual por su posicion (para chequear que la cabeza firmada sigue presente). */
    Optional<PostingHash> findBySeq(long seq);

    /** Pagina de la cadena por seq (keyset/seek pagination) para verificar sin cargar todo en memoria. */
    List<PostingHash> findBySeqGreaterThanOrderBySeqAsc(long seq, Limit limit);
}

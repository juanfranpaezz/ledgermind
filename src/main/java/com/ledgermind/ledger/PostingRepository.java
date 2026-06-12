package com.ledgermind.ledger;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostingRepository extends JpaRepository<Posting, Long> {

    Optional<Posting> findByIdempotencyKey(String idempotencyKey);

    List<Posting> findByDebitAccountIdOrCreditAccountIdOrderByIdDesc(Long debitAccountId, Long creditAccountId);

    /** Asientos todavia NO encadenados (id mayor al ultimo encadenado), en orden, de a lotes. */
    List<Posting> findByIdGreaterThanOrderByIdAsc(Long id, Limit limit);
}

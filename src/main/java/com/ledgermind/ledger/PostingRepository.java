package com.ledgermind.ledger;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostingRepository extends JpaRepository<Posting, Long> {

    Optional<Posting> findByIdempotencyKey(String idempotencyKey);

    List<Posting> findByDebitAccountIdOrCreditAccountIdOrderByIdDesc(Long debitAccountId, Long creditAccountId);
}

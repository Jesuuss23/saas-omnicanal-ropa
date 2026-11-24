package com.saas.omnicanal.fiscal.service;

import com.saas.omnicanal.fiscal.repository.FiscalProductionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Consulta en segundo plano los comprobantes que quedaron "en revisión" (respuesta perdida o SUNAT lenta).
 * Espera creciente entre consultas y como máximo 10 intentos; después queda para revisión manual.
 * Nunca reenvía un comprobante: reenviar podría duplicar un documento que SUNAT sí recibió.
 */
@Component
public class FiscalReconciler {
    private static final Logger log = LoggerFactory.getLogger(FiscalReconciler.class);
    private final FiscalProductionRepository repo;
    private final BetaPosEmission emission;
    private final TransactionTemplate tx;

    FiscalReconciler(FiscalProductionRepository repo, BetaPosEmission emission, TransactionTemplate tx) {
        this.repo = repo;
        this.emission = emission;
        this.tx = tx;
    }

    @Scheduled(fixedDelayString = "${fiscal.reconcile-delay-ms:60000}", initialDelayString = "${fiscal.reconcile-initial-delay-ms:90000}")
    public void tick() {
        runOnce();
    }

    public int runOnce() {
        var due = tx.execute(s -> repo.claimDue(20));
        int resolved = 0;
        for (var d : due == null ? java.util.List.<FiscalProductionRepository.Due>of() : due) {
            try {
                if (!"BETA_REVIEW".equals(emission.reconcileNow(d.tenant(), d.sale()))) resolved++;
            } catch (RuntimeException e) {
                log.info("Conciliación pendiente para {} / {}: {}", d.tenant(), d.sale(), e.getMessage());
            }
        }
        return resolved;
    }
}

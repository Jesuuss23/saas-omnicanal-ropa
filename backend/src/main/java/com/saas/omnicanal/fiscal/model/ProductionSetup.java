package com.saas.omnicanal.fiscal.model;

import java.time.Instant;

/**
 * Estado del alta fiscal para producción, sin secretos: el panel solo sabe si cada paso está completo.
 * Pasos: 1 emisor (tenant_fiscal_issuer), 2 usuario SOL, 3 certificado digital, 4 series y activación.
 */
public record ProductionSetup(String issuerRuc, String legalName, boolean issuerReady, String solUser, boolean solReady,
                              String certificateSubject, Instant certificateNotAfter, boolean certificateReady,
                              String invoiceSeries, String receiptSeries, boolean seriesReady, String status,
                              Instant activatedAt, String activatedBy, boolean planIncludesProduction, long revision) {}

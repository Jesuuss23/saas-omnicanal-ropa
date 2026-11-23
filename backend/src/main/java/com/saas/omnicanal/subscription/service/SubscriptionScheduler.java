package com.saas.omnicanal.subscription.service;

import com.saas.omnicanal.subscription.repository.SubscriptionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Transiciones por tiempo: trial vencido, periodo vencido -> gracia, gracia vencida -> suspendida. */
@Component
class SubscriptionScheduler {
    private final SubscriptionRepository subscriptions;
    private final int graceDays;

    SubscriptionScheduler(SubscriptionRepository subscriptions, @Value("${subscription.grace-days:5}") int graceDays) {
        this.subscriptions = subscriptions;
        this.graceDays = graceDays;
    }

    @Scheduled(fixedDelayString = "${subscription.scheduler-delay-ms:600000}", initialDelay = 60000)
    @Transactional
    public void run() {
        subscriptions.applyTimeTransitions(graceDays);
    }
}

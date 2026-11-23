package com.saas.omnicanal.tenant.service;

import com.saas.omnicanal.subscription.service.SubscriptionService;
import com.saas.omnicanal.tenant.repository.StoreOverviewRepository;
import com.saas.omnicanal.tenant.repository.StoreOverviewRepository.*;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class StoreOverviewService {
    private final StoreOverviewRepository overview;
    private final SubscriptionService subscriptions;

    public StoreOverviewService(StoreOverviewRepository overview, SubscriptionService subscriptions) {
        this.overview = overview;
        this.subscriptions = subscriptions;
    }

    public record Plan(String status, String planCode, java.time.OffsetDateTime trialEndsAt) {}
    public record Overview(Metrics metrics, List<DaySales> sales, List<RecentOrder> recentOrders, Setup setup, Plan plan) {}

    public Overview overview(UUID tenant) {
        var sub = subscriptions.overview(tenant);
        return new Overview(overview.metrics(tenant), overview.salesByDay(tenant, 14), overview.recentOrders(tenant, 5),
                overview.setup(tenant), new Plan(sub.status(), sub.planCode(), sub.trialEndsAt()));
    }
}

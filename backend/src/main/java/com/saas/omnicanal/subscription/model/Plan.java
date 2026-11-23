package com.saas.omnicanal.subscription.model;

import java.util.List;

public record Plan(String code, String name, int monthlyPriceCents, String currency, boolean purchasable,
                   List<Entitlement> entitlements) {
}

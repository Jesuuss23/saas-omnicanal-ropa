package com.saas.omnicanal.tenant;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

public interface TenantRepository extends Repository<Tenant, UUID> {
    Optional<Tenant> findById(UUID id);

    Optional<Tenant> findByDomain(String domain);
}

package com.saas.omnicanal.owner.model;

import java.util.List;
import java.util.UUID;
import org.springframework.security.core.AuthenticationException;

/** El correo y la contraseña valen para varias tiendas: el propietario debe elegir una. */
public class ChooseStoreException extends AuthenticationException {
    public record StoreOption(UUID id, String name) {}

    private final transient List<StoreOption> stores;

    public ChooseStoreException(List<StoreOption> stores) {
        super("Elige una tienda");
        this.stores = List.copyOf(stores);
    }

    public List<StoreOption> stores() {
        return stores;
    }
}

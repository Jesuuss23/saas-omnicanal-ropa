-- Correo con el que la tienda pagó en Culqi. Los eventos de cargo de Culqi (charge.creation.succeeded) no traen el id de
-- la suscripción, solo el correo y el monto: con esto se ubica la suscripción a la que corresponde el cargo.
ALTER TABLE tenant_subscription ADD COLUMN culqi_email VARCHAR(254);
CREATE INDEX idx_tenant_subscription_culqi_email ON tenant_subscription (lower(culqi_email))
    WHERE culqi_email IS NOT NULL;

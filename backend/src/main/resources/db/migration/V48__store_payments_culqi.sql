-- Cobros de las tiendas: Mercado Pago se reemplaza por Culqi con las llaves propias de cada comercio.
-- El dinero va directo a la cuenta Culqi de la tienda; el SaaS guarda la llave secreta cifrada (SecretCipher).

-- Las credenciales OAuth de Mercado Pago no sirven para Culqi: cada tienda vuelve a conectar con sus llaves.
DELETE FROM store_payment_account WHERE provider = 'MERCADOPAGO';

ALTER TABLE store_payment_account
    DROP CONSTRAINT store_payment_account_provider_check,
    ADD CONSTRAINT ck_store_payment_account_provider CHECK (provider IN ('CULQI')),
    ALTER COLUMN provider SET DEFAULT 'CULQI',
    DROP COLUMN provider_user_id,
    DROP COLUMN refresh_token_ciphertext,
    DROP COLUMN token_expires_at,
    ALTER COLUMN public_key SET NOT NULL,
    ADD CONSTRAINT ck_store_payment_public_key CHECK (public_key ~ '^pk_(test|live)_[A-Za-z0-9]+$');
-- access_token_ciphertext guarda la secret_key (sk_test_/sk_live_) cifrada.
COMMENT ON COLUMN store_payment_account.access_token_ciphertext IS 'secret_key de Culqi de la tienda, cifrada con SecretCipher';

-- Solo servían al OAuth y a las preferencias de Checkout Pro de Mercado Pago.
DROP TABLE payment_oauth_state;
DROP TABLE web_order_payment_intent;

-- El historial de pagos de Mercado Pago se conserva como registro; los pagos nuevos son de Culqi.
ALTER TABLE web_order_payment
    DROP CONSTRAINT web_order_payment_provider_check,
    ADD CONSTRAINT ck_web_order_payment_provider CHECK (provider IN ('MERCADOPAGO','CULQI'));

-- La capacidad del plan deja de nombrar a la pasarela.
UPDATE saas_plan_entitlement SET entitlement_key = 'payments.online' WHERE entitlement_key = 'payments.mercadopago';
UPDATE tenant_entitlement_override SET entitlement_key = 'payments.online' WHERE entitlement_key = 'payments.mercadopago';

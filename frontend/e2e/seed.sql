-- Datos mínimos para las pruebas de humo: una tienda con propietario activo.
-- Contraseña del propietario: e2e-propietario-2026 (solo para entornos de prueba).
INSERT INTO tenant (id, name) VALUES ('e2e00000-0000-4000-8000-000000000001', 'Tienda E2E')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name;
INSERT INTO tenant_owner (tenant_id, email, password_hash)
VALUES ('e2e00000-0000-4000-8000-000000000001', 'propietario@e2e.test',
        '$2a$12$a7.d8vavxKibN53WiMzrteOgWWIZdZmxrg2/3ZDck4x4UtaqQ.7Vu')
ON CONFLICT (tenant_id) DO UPDATE SET password_hash = EXCLUDED.password_hash;
-- Cada corrida empieza sin segundo factor para poder probar el enrolamiento con QR.
DELETE FROM mfa_enrollment WHERE subject_type = 'OWNER' AND subject_id = 'e2e00000-0000-4000-8000-000000000001';
DELETE FROM owner_login_attempt WHERE email = 'propietario@e2e.test';
-- Una prenda con stock para el catálogo, el punto de venta y las fotos.
INSERT INTO product (id, tenant_id, name, category)
VALUES ('e2e00000-0000-4000-8000-0000000000a1', 'e2e00000-0000-4000-8000-000000000001', 'Polo E2E', 'Polos')
ON CONFLICT (id) DO NOTHING;
INSERT INTO product_variant (id, product_id, tenant_id, sku, size, color, price, stock_physical)
VALUES ('e2e00000-0000-4000-8000-0000000000b1', 'e2e00000-0000-4000-8000-0000000000a1',
        'e2e00000-0000-4000-8000-000000000001', 'E2E-POLO-M', 'M', 'Negro', 49.90, 5)
ON CONFLICT (id) DO NOTHING;
DELETE FROM product_image WHERE tenant_id = 'e2e00000-0000-4000-8000-000000000001';
DELETE FROM store_appearance WHERE tenant_id = 'e2e00000-0000-4000-8000-000000000001';
-- El equipo empieza vacío en cada corrida (el plan de prueba admite al propietario y un miembro).
DELETE FROM store_member WHERE tenant_id = 'e2e00000-0000-4000-8000-000000000001';
DELETE FROM owner_login_attempt WHERE email = 'vendedora@e2e.test';
-- La tienda sembrada está en prueba gratis, así tiene las capacidades del plan de prueba.
INSERT INTO tenant_subscription (tenant_id, plan_code, status, trial_ends_at)
VALUES ('e2e00000-0000-4000-8000-000000000001', 'TRIAL', 'TRIAL', now() + interval '14 days')
ON CONFLICT (tenant_id) DO UPDATE SET plan_code = 'TRIAL', status = 'TRIAL', trial_ends_at = EXCLUDED.trial_ends_at,
    pending_plan_code = NULL, revision = tenant_subscription.revision + 1, updated_at = now();
-- Contactos de corridas anteriores.
UPDATE web_checkout_order SET contact_id = NULL WHERE tenant_id = 'e2e00000-0000-4000-8000-000000000001';
DELETE FROM customer_session WHERE tenant_id = 'e2e00000-0000-4000-8000-000000000001';
DELETE FROM contact_merge_log WHERE tenant_id = 'e2e00000-0000-4000-8000-000000000001';
UPDATE contact SET merged_into = NULL WHERE tenant_id = 'e2e00000-0000-4000-8000-000000000001';
DELETE FROM contact WHERE tenant_id = 'e2e00000-0000-4000-8000-000000000001';

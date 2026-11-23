-- Datos mínimos para las pruebas de humo: una tienda con propietario activo.
-- Contraseña del propietario: e2e-propietario-2026 (solo para entornos de prueba).
INSERT INTO tenant (id, name) VALUES ('e2e00000-0000-4000-8000-000000000001', 'Tienda E2E')
ON CONFLICT (id) DO NOTHING;
INSERT INTO tenant_owner (tenant_id, email, password_hash)
VALUES ('e2e00000-0000-4000-8000-000000000001', 'propietario@e2e.test',
        '$2a$12$a7.d8vavxKibN53WiMzrteOgWWIZdZmxrg2/3ZDck4x4UtaqQ.7Vu')
ON CONFLICT (tenant_id) DO UPDATE SET password_hash = EXCLUDED.password_hash;

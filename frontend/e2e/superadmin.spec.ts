import {expect, test} from "@playwright/test";
import {totp, watchErrors} from "./helpers";

// Credenciales que CI le pasa al backend (ver .github/workflows/ci.yml).
const admin = {email: "admin@e2e.test", password: "e2e-superadmin-2026", secret: "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"};

test("consola del superadmin: alta, suspensión y auditoría", async ({page}, info) => {
  test.skip(!process.env.E2E_SEEDED || info.project.name !== "desktop", "Requiere el superadmin de CI; un login por paso TOTP");
  const errors = watchErrors(page);
  await page.goto("/superadmin/login");
  await page.getByLabel("Correo electrónico").fill(admin.email);
  await page.getByLabel("Contraseña").fill(admin.password);
  await page.getByRole("button", {name: "Continuar"}).click();
  await page.getByLabel("Código de verificación").fill(totp(admin.secret, 0));
  await page.getByRole("button", {name: "Verificar e ingresar"}).click();
  await expect(page.getByRole("heading", {name: "Resumen de plataforma"})).toBeVisible();
  await expect(page.getByText("Ingreso mensual recurrente")).toBeVisible();

  await page.getByRole("navigation").getByRole("link", {name: "Tiendas"}).click();
  await expect(page.getByRole("heading", {name: "Tiendas", exact: true})).toBeVisible();
  const name = "Moda E2E " + Date.now();
  await page.getByRole("button", {name: "Nueva tienda"}).click();
  await page.getByLabel("Nombre de la tienda").fill(name);
  await page.getByLabel("Correo del propietario").fill("dueno-" + Date.now() + "@e2e.test");
  await page.getByRole("button", {name: "Registrar tienda"}).click();
  await expect(page.getByRole("heading", {name})).toBeVisible();
  await expect(page.getByText("En prueba").first()).toBeVisible();

  await page.getByRole("button", {name: "Suspender"}).click();
  await page.getByLabel("Motivo").fill("Prueba automática");
  await page.getByRole("button", {name: "Suspender tienda"}).click();
  await expect(page.getByText(/Suspendida el/)).toBeVisible();
  await page.getByRole("button", {name: "Reactivar"}).click();
  await expect(page.getByText(/Suspendida el/)).toHaveCount(0);

  await page.getByRole("button", {name: "Ajustar suscripción"}).click();
  await page.getByLabel("Días adicionales").fill("5");
  await page.getByLabel("Motivo").fill("Piloto E2E");
  await page.getByRole("button", {name: "Guardar"}).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);

  await page.getByRole("tab", {name: "Auditoría"}).click();
  for (const action of ["Tienda creada", "Tienda suspendida", "Tienda reactivada", "Suscripción ajustada"]) {
    await expect(page.getByRole("cell", {name: action})).toBeVisible();
  }

  for (const [link, heading] of [["Planes", "Planes"], ["Pagos", "Pagos"], ["Auditoría", "Auditoría"], ["Seguridad", "Seguridad"]]) {
    await page.getByRole("navigation").getByRole("link", {name: link}).click();
    await expect(page.getByRole("heading", {name: heading, exact: true})).toBeVisible();
  }
  await page.getByRole("button", {name: "Cerrar sesión"}).click();
  await expect(page).toHaveURL(/\/superadmin\/login/);
  expect(errors).toEqual([]);
});

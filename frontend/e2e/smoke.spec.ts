import {expect, test} from "@playwright/test";
import {totp, watchErrors} from "./helpers";

test("portada", async ({page}) => {
  const errors = watchErrors(page);
  await page.goto("/");
  await expect(page.getByRole("heading", {level: 1})).toBeVisible();
  await expect(page.getByRole("link", {name: /Prueba gratis/}).first()).toBeVisible();
  expect(errors).toEqual([]);
});

test("precios muestra los planes públicos", async ({page}) => {
  const errors = watchErrors(page);
  await page.goto("/precios");
  for (const plan of ["Básico", "Pro", "Premium"]) await expect(page.getByRole("heading", {name: plan, exact: true})).toBeVisible();
  await expect(page.getByRole("table")).toBeVisible();
  expect(errors).toEqual([]);
});

test("prueba gratis envía el enlace de confirmación", async ({page}) => {
  const errors = watchErrors(page);
  await page.goto("/prueba-gratis?plan=PRO");
  await page.getByLabel("Nombre de tu tienda").fill("Tienda Playwright");
  await page.getByLabel("Tu nombre").fill("Prueba Automática");
  const email = "e2e-" + Date.now() + "-" + Math.random().toString(36).slice(2) + "@prueba.test";
  await page.getByLabel("Correo electrónico").fill(email);
  await expect(page.getByLabel("Plan que te interesa")).toHaveValue("PRO");
  await page.getByRole("checkbox").check();
  await page.getByRole("button", {name: "Crear mi tienda gratis"}).click();
  await expect(page.getByRole("heading", {name: "Revisa tu correo"})).toBeVisible();
  await expect(page.getByText(email)).toBeVisible();
  expect(errors).toEqual([]);
});

test("login de superadmin", async ({page}) => {
  const errors = watchErrors(page);
  await page.goto("/superadmin");
  await expect(page).toHaveURL(/\/superadmin\/login/);
  await expect(page.getByLabel(/correo/i)).toBeVisible();
  await expect(page.getByLabel(/contraseña/i)).toBeVisible();
  expect(errors).toEqual([]);
});

test("acceso del propietario", async ({page}) => {
  const errors = watchErrors(page);
  await page.goto("/propietario/00000000-0000-0000-0000-000000000000");
  await expect(page.getByLabel(/contraseña/i)).toBeVisible();
  expect(errors).toEqual([]);
});

test("tienda inexistente responde sin romperse", async ({page}) => {
  const errors = watchErrors(page);
  const res = await page.goto("/tienda/00000000-0000-0000-0000-000000000000");
  expect(res?.status()).toBeLessThan(500);
  expect(errors).toEqual([]);
});

const seededStore = "e2e00000-0000-4000-8000-000000000001";

test("panel del propietario carga todas sus secciones", async ({page}, info) => {
  test.skip(!process.env.E2E_SEEDED || info.project.name !== "desktop", "Requiere e2e/seed.sql; usa la cuenta sembrada antes de activar 2FA");
  const errors = watchErrors(page);
  await page.goto("/propietario/" + seededStore);
  await page.getByLabel("Correo electrónico").fill("propietario@e2e.test");
  await page.getByLabel("Contraseña").fill("e2e-propietario-2026");
  await page.getByRole("button", {name: "Ingresar"}).click();
  await expect(page.getByRole("heading", {name: "Tienda E2E"})).toBeVisible();
  for (const section of ["WhatsApp de la tienda", "Cobros en línea con Mercado Pago"]) {
    await expect(page.getByRole("heading", {name: section})).toBeVisible();
  }
  await expect(page.getByText("Cargando inventario…")).toHaveCount(0, {timeout: 10_000});
  await page.getByRole("button", {name: "Cerrar sesión"}).click();
  await expect(page.getByLabel(/contraseña/i)).toBeVisible();
  expect(errors).toEqual([]);
});

test.describe.serial("verificación en dos pasos del propietario", () => {
  test("activa con QR y luego pide el código al ingresar", async ({page}, info) => {
    test.skip(!process.env.E2E_SEEDED || info.project.name !== "desktop", "Modifica la cuenta sembrada: corre una sola vez");
    const errors = watchErrors(page);
    await page.goto("/ingresar");
    await page.getByLabel("Correo electrónico").fill("propietario@e2e.test");
    await page.getByLabel("Contraseña").fill("e2e-propietario-2026");
    await page.getByRole("button", {name: "Ingresar"}).click();
    await expect(page).toHaveURL(new RegExp("/propietario/" + seededStore));

    await page.getByRole("button", {name: "Activar con código QR"}).click();
    await page.getByRole("button", {name: "Empezar configuración"}).click();
    await expect(page.getByAltText("Código QR para tu app autenticadora")).toBeVisible();
    await page.getByText("¿No puedes escanear?").click();
    const secret = (await page.locator("code").first().innerText()).replace(/\s/g, "");
    await page.getByLabel("Código de 6 dígitos").fill(totp(secret, 0));
    await page.getByRole("button", {name: "Activar verificación"}).click();
    await expect(page.getByText("Verificación en dos pasos activada")).toBeVisible();
    await page.getByLabel("Ya guardé mis códigos de recuperación").check();
    await page.getByRole("button", {name: "Listo"}).click();
    await expect(page.getByText("Activa", {exact: true})).toBeVisible();

    await page.getByRole("button", {name: "Cerrar sesión"}).click();
    await page.getByLabel("Correo electrónico").fill("propietario@e2e.test");
    await page.getByLabel("Contraseña").fill("e2e-propietario-2026");
    await page.getByRole("button", {name: "Ingresar"}).click();
    await page.getByLabel("Código de verificación").fill(totp(secret, 1));
    await page.getByRole("button", {name: "Verificar e ingresar"}).click();
    await expect(page.getByRole("heading", {name: "Tienda E2E"})).toBeVisible();
    expect(errors).toEqual([]);
  });
});

test("catálogo público de la tienda", async ({page}) => {
  test.skip(!process.env.E2E_SEEDED, "Requiere e2e/seed.sql cargado en la base de datos");
  const errors = watchErrors(page);
  await page.goto("/tienda/" + seededStore);
  await expect(page.getByRole("heading", {name: /Tienda E2E/})).toBeVisible();
  expect(errors).toEqual([]);
});

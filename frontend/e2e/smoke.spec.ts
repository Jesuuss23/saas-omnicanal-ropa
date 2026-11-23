import {expect, test, type Page} from "@playwright/test";

/** Falla si la página lanza excepciones o escribe errores en consola (salvo respuestas HTTP esperadas). */
function watchErrors(page: Page) {
  const errors: string[] = [];
  page.on("pageerror", err => errors.push(err.message));
  page.on("console", msg => {
    if (msg.type() !== "error") return;
    // 401/404 de la API son parte del flujo (sesión no iniciada, tienda inexistente).
    if (/Failed to load resource: the server responded with a status of (401|403|404)/.test(msg.text())) return;
    errors.push(msg.text());
  });
  return errors;
}

test("portada", async ({page}) => {
  const errors = watchErrors(page);
  await page.goto("/");
  await expect(page.getByRole("heading", {level: 1})).toBeVisible();
  await expect(page.getByRole("link", {name: /Ingresar/})).toBeVisible();
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

test("panel del propietario carga todas sus secciones", async ({page}) => {
  test.skip(!process.env.E2E_SEEDED, "Requiere e2e/seed.sql cargado en la base de datos");
  const errors = watchErrors(page);
  await page.goto("/propietario/" + seededStore);
  await page.getByLabel(/contraseña/i).fill("e2e-propietario-2026");
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

test("catálogo público de la tienda", async ({page}) => {
  test.skip(!process.env.E2E_SEEDED, "Requiere e2e/seed.sql cargado en la base de datos");
  const errors = watchErrors(page);
  await page.goto("/tienda/" + seededStore);
  await expect(page.getByRole("heading", {name: /Tienda E2E/})).toBeVisible();
  expect(errors).toEqual([]);
});

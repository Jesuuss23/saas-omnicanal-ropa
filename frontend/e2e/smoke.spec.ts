import {createHmac} from "node:crypto";
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

/** TOTP (RFC 6238, SHA-1, 6 dígitos) para simular la app autenticadora. */
function totp(base32: string, offsetSteps: number) {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let bits = "";
  for (const c of base32.replace(/=+$/, "")) bits += alphabet.indexOf(c).toString(2).padStart(5, "0");
  const key = Buffer.from(bits.match(/.{8}/g)!.map(b => parseInt(b, 2)));
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30000) + offsetSteps));
  const hash = createHmac("sha1", key).update(counter).digest();
  const offset = hash[hash.length - 1] & 15;
  return String((hash.readUInt32BE(offset) & 0x7fffffff) % 1_000_000).padStart(6, "0");
}

test("catálogo público de la tienda", async ({page}) => {
  test.skip(!process.env.E2E_SEEDED, "Requiere e2e/seed.sql cargado en la base de datos");
  const errors = watchErrors(page);
  await page.goto("/tienda/" + seededStore);
  await expect(page.getByRole("heading", {name: /Tienda E2E/})).toBeVisible();
  expect(errors).toEqual([]);
});

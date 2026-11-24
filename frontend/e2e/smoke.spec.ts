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
  await expect(page.getByRole("heading", {name: "Hola, Tienda E2E"})).toBeVisible();
  await expect(page.getByText("Configura tu tienda")).toBeVisible();
  const nav = page.getByRole("navigation");
  for (const link of ["Pedidos web", "Punto de venta", "Catálogo", "Clientes", "WhatsApp", "Apariencia", "Asistente IA", "Marketing", "Cobros", "Facturación", "Equipo", "Plan", "Cuenta y seguridad"]) {
    await nav.getByRole("link", {name: link, exact: true}).click();
    await expect(page.getByRole("heading", {name: link, exact: true, level: 1})).toBeVisible();
    if (link === "WhatsApp") {
      await expect(page.getByText("Elige una conversación").or(page.getByText("Sin conversaciones aquí")).first()).toBeVisible();
      await page.getByRole("tab", {name: "Conexión"}).click();
      await expect(page.getByRole("heading", {name: "WhatsApp de la tienda"})).toBeVisible();
    }
    if (link === "Asistente IA") await expect(page.getByRole("heading", {name: "Pruébala"})).toBeVisible();
    if (link === "Marketing") await expect(page.getByText("Nueva campaña")).toBeVisible();
    if (link === "Facturación") await expect(page.getByText("Emisión en producción")).toBeVisible();
    if (link === "Cobros") await expect(page.getByRole("heading", {name: "Cobros en línea con Mercado Pago"})).toBeVisible();
    if (link === "Catálogo") await expect(page.getByRole("checkbox", {name: "Etiqueta QR de E2E-POLO-M"})).toBeVisible();
    if (link === "Punto de venta") {
      // El buscador muestra la prenda con su SKU; Enter con el SKU exacto la agrega al carrito.
      await page.getByLabel("Buscar prenda o SKU").fill("E2E-POLO-M");
      await expect(page.getByRole("button", {name: /Agregar Polo E2E talla M/})).toBeVisible();
      await page.getByLabel("Buscar prenda o SKU").press("Enter");
      await expect(page.getByRole("list", {name: "Carrito de mostrador"}).getByText("Polo E2E")).toBeVisible();
    }
  }
  await page.getByRole("button", {name: "Cerrar sesión"}).click();
  await expect(page.getByLabel(/contraseña/i)).toBeVisible();
  expect(errors).toEqual([]);
});

test("apariencia y fotos de producto", async ({page}, info) => {
  test.skip(!process.env.E2E_SEEDED || info.project.name !== "desktop", "Requiere e2e/seed.sql; usa la cuenta sembrada antes de activar 2FA");
  const errors = watchErrors(page);
  await page.goto("/propietario/" + seededStore + "/apariencia");
  await page.getByLabel("Correo electrónico").fill("propietario@e2e.test");
  await page.getByLabel("Contraseña").fill("e2e-propietario-2026");
  await page.getByRole("button", {name: "Ingresar"}).click();
  await page.getByLabel("Frase corta").fill("Moda de prueba");
  await page.getByLabel("WhatsApp").fill("51987654321");
  await page.getByRole("button", {name: "Guardar cambios"}).click();
  await expect(page.getByText("Apariencia guardada")).toBeVisible();
  await page.getByLabel("Elegir logo").setInputFiles({name: "logo.png", mimeType: "image/png", buffer: PNG});
  await expect(page.getByAltText("Logo actual")).toBeVisible();

  await page.getByRole("navigation").getByRole("link", {name: "Catálogo"}).click();
  await page.getByRole("button", {name: "Fotos de Polo E2E"}).click();
  await page.getByLabel("Elegir fotos").setInputFiles([{name: "a.png", mimeType: "image/png", buffer: PNG}, {name: "b.png", mimeType: "image/png", buffer: PNG}]);
  await expect(page.getByAltText("Foto 2 de Polo E2E")).toBeVisible();
  await expect(page.getByText("Portada", {exact: true})).toBeVisible();
  await page.getByRole("button", {name: "Quitar foto 2"}).click();
  await expect(page.getByAltText("Foto 2 de Polo E2E")).toHaveCount(0);
  await page.keyboard.press("Escape");
  await page.getByRole("navigation").getByRole("link", {name: "Inicio"}).click();
  await expect(page.getByRole("heading", {name: "Hola, Tienda E2E"})).toBeVisible();
  expect(errors).toEqual([]);
});

test("equipo: invitar a una vendedora y entrar con su rol", async ({page}, info) => {
  test.skip(!process.env.E2E_SEEDED || info.project.name !== "desktop", "Requiere e2e/seed.sql; usa la cuenta sembrada antes de activar 2FA");
  const errors = watchErrors(page);
  await page.goto("/propietario/" + seededStore + "/equipo");
  await page.getByLabel("Correo electrónico").fill("propietario@e2e.test");
  await page.getByLabel("Contraseña").fill("e2e-propietario-2026");
  await page.getByRole("button", {name: "Ingresar"}).click();
  await expect(page.getByRole("heading", {name: "Equipo", level: 1})).toBeVisible();
  await page.getByRole("button", {name: "Invitar persona"}).click();
  await page.getByLabel("Nombre").fill("Vendedora E2E");
  await page.getByLabel("Correo").fill("vendedora@e2e.test");
  await page.getByRole("button", {name: "Enviar invitación"}).click();
  const link = await page.getByLabel("Enlace de invitación").inputValue();
  await expect(page.getByText("vendedora@e2e.test", {exact: true})).toBeVisible();
  await expect(page.getByText("Invitación pendiente")).toBeVisible();
  await page.getByRole("button", {name: "Cerrar sesión"}).click();
  await expect(page.getByLabel(/contraseña/i)).toBeVisible();

  await page.goto(link);
  await expect(page.getByRole("heading", {name: "Únete al equipo"})).toBeVisible();
  await page.getByLabel("Nueva contraseña").fill("vendedora-e2e-2026");
  await page.getByRole("button", {name: "Activar cuenta"}).click();
  await expect(page.getByText("Cuenta activada")).toBeVisible();
  await page.getByLabel("Correo electrónico").fill("vendedora@e2e.test");
  await page.getByLabel("Contraseña").fill("vendedora-e2e-2026");
  await page.getByRole("button", {name: "Ingresar"}).click();
  await expect(page.getByRole("heading", {name: "Hola, Tienda E2E"})).toBeVisible();
  const nav = page.getByRole("navigation");
  await expect(nav.getByRole("link", {name: "Punto de venta"})).toBeVisible();
  await expect(nav.getByRole("link", {name: "Equipo"})).toHaveCount(0);
  await expect(nav.getByRole("link", {name: "Cobros"})).toHaveCount(0);
  await page.goto("/propietario/" + seededStore + "/plan");
  await expect(page.getByText("Esta sección es solo para el propietario")).toBeVisible();
  expect(errors).toEqual([]);
});

const PNG = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==", "base64");

test("bandeja de WhatsApp: tomar y devolver a la IA", async ({page}, info) => {
  test.skip(!process.env.E2E_SEEDED || info.project.name !== "desktop", "Requiere e2e/seed.sql; usa la cuenta sembrada antes de activar 2FA");
  const errors = watchErrors(page);
  await page.goto("/propietario/" + seededStore + "/whatsapp");
  await page.getByLabel("Correo electrónico").fill("propietario@e2e.test");
  await page.getByLabel("Contraseña").fill("e2e-propietario-2026");
  await page.getByRole("button", {name: "Ingresar"}).click();
  await page.getByRole("button", {name: /\+51999888777/}).click();
  await expect(page.getByText("Hola, ¿tienen el polo en talla M?").last()).toBeVisible();
  await page.getByRole("button", {name: "Tomar"}).click();
  await expect(page.getByText("Atiende propietario@e2e.test")).toBeVisible();
  await page.getByRole("button", {name: "Devolver a la IA"}).click();
  await page.getByLabel("Nota para la IA (opcional)").fill("Ya le confirmé la talla.");
  await page.getByRole("dialog").getByRole("button", {name: "Devolver a la IA"}).click();
  await expect(page.getByRole("button", {name: "Tomar"})).toBeVisible();

  await page.getByRole("navigation").getByRole("link", {name: "Asistente IA"}).click();
  await page.getByLabel("Indicaciones para tu tienda").fill("Tutea al cliente.");
  await page.getByRole("button", {name: "Guardar"}).click();
  await expect(page.getByText("Configuración guardada")).toBeVisible();
  expect(errors).toEqual([]);
});

test("marketing: borrador de campaña con segmento", async ({page}, info) => {
  test.skip(!process.env.E2E_SEEDED || info.project.name !== "desktop", "Requiere e2e/seed.sql; usa la cuenta sembrada antes de activar 2FA");
  const errors = watchErrors(page);
  await page.goto("/propietario/" + seededStore + "/marketing");
  await page.getByLabel("Correo electrónico").fill("propietario@e2e.test");
  await page.getByLabel("Contraseña").fill("e2e-propietario-2026");
  await page.getByRole("button", {name: "Ingresar"}).click();
  await expect(page.getByText("Aceptan correos")).toBeVisible();
  await page.getByLabel("Nombre interno").fill("Colección E2E");
  await page.getByLabel("Asunto").fill("Llegaron polos nuevos");
  await page.getByLabel("Mensaje").fill("Pasa a verlos en la tienda.");
  await page.getByLabel("Clientes").selectOption("BUYERS");
  await page.getByRole("button", {name: "Calcular destinatarios"}).click();
  await expect(page.getByText(/recibirían? esta campaña/)).toBeVisible();
  await page.getByRole("button", {name: "Guardar borrador"}).click();
  await expect(page.getByRole("cell", {name: /Colección E2E/})).toBeVisible();
  await expect(page.getByText("Borrador", {exact: true})).toBeVisible();
  expect(errors).toEqual([]);
});

test("baja de comunicaciones con enlace inválido", async ({page}) => {
  const errors = watchErrors(page);
  await page.goto("/baja/00000000000000000000000000000000");
  await expect(page.getByText("Este enlace no es válido o ya no existe.")).toBeVisible();
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
    await page.getByRole("navigation").getByRole("link", {name: "Cuenta y seguridad"}).click();

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
    await expect(page.getByRole("heading", {name: "Cuenta y seguridad"})).toBeVisible();
    expect(errors).toEqual([]);
  });
});

test("catálogo público de la tienda", async ({page}) => {
  test.skip(!process.env.E2E_SEEDED, "Requiere e2e/seed.sql cargado en la base de datos");
  const errors = watchErrors(page);
  await page.goto("/tienda/" + seededStore);
  await expect(page.getByRole("heading", {name: /Tienda E2E/})).toBeVisible();
  await expect(page.getByRole("heading", {name: "Polo E2E"})).toBeVisible();
  await page.getByRole("button", {name: "Agregar al carrito"}).first().click();
  await expect(page.getByRole("region", {name: "Carrito"}).getByText("Polo E2E", {exact: true})).toBeVisible();
  await expect(page.getByRole("button", {name: "Reservar por 15 minutos"})).toBeVisible();
  await page.getByRole("link", {name: "Mi cuenta"}).click();
  await expect(page.getByRole("heading", {name: "Mi cuenta"})).toBeVisible();
  await expect(page.getByRole("heading", {name: "Ingresa para ver tus pedidos"})).toBeVisible();
  expect(errors).toEqual([]);
});

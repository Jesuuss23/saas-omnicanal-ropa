import {createHmac} from "node:crypto";
import type {Page} from "@playwright/test";

/** Falla si la página lanza excepciones o escribe errores en consola (salvo respuestas HTTP esperadas). */
export function watchErrors(page: Page) {
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

/** TOTP (RFC 6238, SHA-1, 6 dígitos) para simular la app autenticadora. */
export function totp(base32: string, offsetSteps: number) {
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


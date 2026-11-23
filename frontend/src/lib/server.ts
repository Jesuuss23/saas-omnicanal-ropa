import {cookies} from "next/headers";
import {backendBase} from "./api";

/** GET al backend desde un server component reenviando la cookie de sesión del navegador. */
export async function backendGet(path: string): Promise<Response> {
  const session = (await cookies()).get("JSESSIONID");
  return fetch(backendBase() + "/" + path.replace(/^\//, ""), {
    headers: session ? {Cookie: "JSESSIONID=" + encodeURIComponent(session.value)} : {},
    cache: "no-store",
  });
}

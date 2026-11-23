import {cookies} from "next/headers";
import {redirect} from "next/navigation";
import {backendGet} from "@/lib/server";
import {SuperadminShell} from "./SuperadminShell";

/** Todas las páginas de la consola exigen sesión completa (contraseña + 2FA). */
export default async function ConsoleLayout({children}: {children: React.ReactNode}) {
  if (!(await cookies()).get("JSESSIONID")) redirect("/superadmin/login");
  const me = await backendGet("superadmin/me");
  if (me.status === 401 || me.status === 403) redirect("/superadmin/login");
  if (!me.ok) throw new Error("No se pudo cargar la consola.");
  const profile: {email: string; setupRequired: boolean} = await me.json();
  if (profile.setupRequired) redirect("/superadmin/seguridad/activar");
  return <SuperadminShell email={profile.email}>{children}</SuperadminShell>;
}

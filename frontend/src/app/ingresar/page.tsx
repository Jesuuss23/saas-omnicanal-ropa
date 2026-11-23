"use client";

import Link from "next/link";
import {useRouter} from "next/navigation";
import {AuthShell} from "@/components/auth/AuthShell";
import {OwnerLoginForm} from "@/components/auth/OwnerLoginForm";

export default function OwnerSignIn() {
  const router = useRouter();
  return <AuthShell eyebrow="Panel de tu tienda" title="Ingresa a tu tienda" description="Usa el correo con el que registraste tu tienda.">
    <OwnerLoginForm onSuccess={store => router.push("/propietario/" + encodeURIComponent(store))}/>
    <p className="mt-8 text-center text-sm text-muted-foreground">¿Aún no tienes tienda? <Link href="/" className="font-medium text-primary hover:underline">Conoce los planes</Link></p>
  </AuthShell>;
}

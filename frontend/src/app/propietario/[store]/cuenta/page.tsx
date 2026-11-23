"use client";

import {PageHeader} from "@/components/console/ConsoleShell";
import {TwoFactorCard} from "@/components/account/TwoFactorCard";
import {Card, CardContent, CardHeader, CardTitle} from "@/components/ui/card";
import {ownerApi} from "@/lib/api";
import {useStore} from "../_panel/StoreContext";

export default function Account() {
  const {profile} = useStore();
  return <>
    <PageHeader title="Cuenta y seguridad" description="Tu acceso al panel de la tienda."/>
    <div className="grid max-w-3xl gap-6">
      <Card><CardHeader><CardTitle>Propietario</CardTitle></CardHeader><CardContent className="grid gap-1 text-sm">
        <p className="font-medium">{profile.email}</p>
        <p className="text-muted-foreground">Para cambiar la contraseña usa “¿Olvidaste tu contraseña?” en la pantalla de ingreso.</p>
      </CardContent></Card>
      <TwoFactorCard client={ownerApi} statusPath="security" base="security/mfa" allowDisable/>
    </div>
  </>;
}

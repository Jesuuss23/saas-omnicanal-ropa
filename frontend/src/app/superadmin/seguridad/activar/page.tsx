"use client";

import {AuthShell} from "@/components/auth/AuthShell";
import {TwoFactorSetup} from "@/components/auth/TwoFactorSetup";
import {superadminApi} from "@/lib/api";

export default function ActivateTwoFactor() {
  return <AuthShell eyebrow="Paso obligatorio" title="Protege tu cuenta"
    description="Configura la verificación en dos pasos para entrar al panel de administración.">
    <TwoFactorSetup client={superadminApi} base="mfa" doneLabel="Ir al panel" onDone={() => window.location.replace("/superadmin")}/>
  </AuthShell>;
}

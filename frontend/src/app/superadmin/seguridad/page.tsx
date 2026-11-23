"use client";

import Link from "next/link";
import {ArrowLeft} from "lucide-react";
import {TwoFactorCard} from "@/components/account/TwoFactorCard";
import {superadminApi} from "@/lib/api";

export default function Security() {
  return <main className="min-h-dvh bg-background">
    <div className="mx-auto grid max-w-3xl gap-6 px-4 py-10 sm:px-6">
      <Link href="/superadmin" className="flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"><ArrowLeft className="size-4"/>Volver al panel</Link>
      <div><h1 className="text-3xl font-semibold tracking-tight">Seguridad</h1>
        <p className="mt-1 text-muted-foreground">Administra el acceso a tu cuenta de superadministrador.</p></div>
      <TwoFactorCard client={superadminApi} statusPath="me" base="mfa" required/>
    </div>
  </main>;
}

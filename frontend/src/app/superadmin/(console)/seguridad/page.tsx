"use client";

import {PageHeader} from "@/components/console/ConsoleShell";
import {TwoFactorCard} from "@/components/account/TwoFactorCard";
import {superadminApi} from "@/lib/api";

export default function Security() {
  return <>
    <PageHeader title="Seguridad" description="Administra el acceso a tu cuenta de superadministrador."/>
    <div className="max-w-3xl"><TwoFactorCard client={superadminApi} statusPath="me" base="mfa" required/></div>
  </>;
}

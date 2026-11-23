"use client";

import {PageHeader} from "@/components/console/ConsoleShell";
import {Tabs, TabsContent, TabsList, TabsTrigger} from "@/components/ui/tabs";
import {useStore} from "../_panel/StoreContext";
import ContactsCrm from "../ContactsCrm";
import UnifiedContacts from "./UnifiedContacts";

export default function Customers() {
  const {store, send, profile} = useStore();
  return <>
    <PageHeader title="Clientes" description="Cada persona una sola vez, aunque te haya comprado como invitada, con su cuenta de Google o por WhatsApp."/>
    <Tabs defaultValue="contactos">
      <TabsList><TabsTrigger value="contactos">Contactos</TabsTrigger><TabsTrigger value="whatsapp">Seguimiento por WhatsApp</TabsTrigger></TabsList>
      <TabsContent value="contactos"><UnifiedContacts store={store} canMerge={profile.role !== "SELLER"}/></TabsContent>
      <TabsContent value="whatsapp"><ContactsCrm store={store} send={send}/></TabsContent>
    </Tabs>
  </>;
}

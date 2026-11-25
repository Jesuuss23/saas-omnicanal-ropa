"use client";

import {useSearchParams} from "next/navigation";
import {Suspense} from "react";
import {PageHeader} from "@/components/console/ConsoleShell";
import {Tabs, TabsContent, TabsList, TabsTrigger} from "@/components/ui/tabs";
import {useStore} from "../_panel/StoreContext";
import WhatsAppChannels from "../WhatsAppChannels";
import Inbox from "./Inbox";

export default function WhatsApp() {
  return <Suspense><WhatsAppContent/></Suspense>;
}

function WhatsAppContent() {
  const {store, send, profile} = useStore();
  const params = useSearchParams();
  const chat = params.get("chat");
  // "Abrir en otra ventana": la bandeja sola, a pantalla completa.
  if (params.get("ventana") === "1") return <Inbox store={store} initialChat={chat} popup/>;
  return <>
    <PageHeader title="WhatsApp" description="Responde a tus clientes con ayuda de la IA y toma la conversación cuando haga falta."/>
    <Tabs defaultValue="bandeja">
      <TabsList><TabsTrigger value="bandeja">Bandeja</TabsTrigger>
        {profile.role !== "SELLER" && <TabsTrigger value="conexion">Conexión</TabsTrigger>}</TabsList>
      <TabsContent value="bandeja"><Inbox store={store} initialChat={chat}/></TabsContent>
      {profile.role !== "SELLER" && <TabsContent value="conexion"><WhatsAppChannels store={store} send={send}/></TabsContent>}
    </Tabs>
  </>;
}

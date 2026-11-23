"use client";

import {PageHeader} from "@/components/console/ConsoleShell";
import {Tabs, TabsContent, TabsList, TabsTrigger} from "@/components/ui/tabs";
import {useStore} from "../_panel/StoreContext";
import Catalog from "../Catalog";
import CategoryResources from "../CategoryResources";

export default function CatalogPage() {
  const {store, send} = useStore();
  return <>
    <PageHeader title="Catálogo" description="Prendas, variantes, stock, fotos y etiquetas QR. Lo que publiques aquí se ve en tu tienda y en el punto de venta."/>
    <Tabs defaultValue="prendas">
      <TabsList><TabsTrigger value="prendas">Prendas</TabsTrigger><TabsTrigger value="recursos">Catálogos por categoría</TabsTrigger></TabsList>
      <TabsContent value="prendas"><Catalog store={store} send={send}/></TabsContent>
      <TabsContent value="recursos"><CategoryResources store={store} send={send}/></TabsContent>
    </Tabs>
  </>;
}

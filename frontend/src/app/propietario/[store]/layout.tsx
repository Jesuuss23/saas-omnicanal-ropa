import {OwnerGate} from "./_panel/OwnerGate";

export default async function StoreLayout({children, params}: {children: React.ReactNode; params: Promise<{store: string}>}) {
  const {store} = await params;
  return <OwnerGate key={store} store={store}>{children}</OwnerGate>;
}

import {StoreList} from "./StoreList";

export default async function Stores({searchParams}: {searchParams: Promise<Record<string, string | string[] | undefined>>}) {
  const params = await searchParams;
  const one = (v: string | string[] | undefined) => (Array.isArray(v) ? v[0] : v) ?? "";
  return <StoreList initialQuery={one(params.q)} initialStatus={one(params.estado)} openNew={one(params.nueva) === "1"}/>;
}

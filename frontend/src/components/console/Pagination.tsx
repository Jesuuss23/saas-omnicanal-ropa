import {ChevronLeft, ChevronRight} from "lucide-react";
import {Button} from "@/components/ui/button";

export type Page<T> = {items: T[]; page: number; hasNext: boolean};

export function Pagination({page, hasNext, onPage}: {page: number; hasNext: boolean; onPage: (page: number) => void}) {
  if (page === 0 && !hasNext) return null;
  return <div className="flex items-center justify-end gap-2 text-sm">
    <span className="mr-2 text-muted-foreground">Página {page + 1}</span>
    <Button variant="outline" size="sm" disabled={page === 0} onClick={() => onPage(page - 1)}><ChevronLeft/>Anterior</Button>
    <Button variant="outline" size="sm" disabled={!hasNext} onClick={() => onPage(page + 1)}>Siguiente<ChevronRight/></Button>
  </div>;
}

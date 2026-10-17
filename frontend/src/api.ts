export class ApiError extends Error {
  constructor(message: string, public status: number) { super(message); }
}
export type Row = Record<string, any>;
export async function api<T = Row[]>(
  path: string,
  method = "GET",
  body?: unknown,
): Promise<T> {
  const headers: Record<string, string> = {};
  if (method !== "GET") {
    const csrf = await fetch("/api/auth/csrf", {
      credentials: "same-origin",
    }).then((r) => r.json());
    headers[csrf.header] = csrf.token;
    headers["Content-Type"] =
      body instanceof URLSearchParams
        ? "application/x-www-form-urlencoded"
        : "application/json";
  }
  const response = await fetch("/api" + path, {
    method,
    headers,
    credentials: "same-origin",
    body:
      body instanceof URLSearchParams
        ? body
        : body === undefined
          ? undefined
          : JSON.stringify(body),
  });
  if (!response.ok) {
    const detail = await response.json().catch(() => ({}));
    throw new ApiError(
      detail.detail ||
        (response.status === 401
          ? "Sessão expirada ou credenciais inválidas. Entre novamente."
          : response.status === 404
            ? "Registro não encontrado ou indisponível."
            : response.status === 403
              ? "Você não tem permissão para esta operação."
              : "Não foi possível concluir. Verifique os dados e tente novamente."), response.status,
    );
  }
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}
export const localDay = (value: string) =>
  new Intl.DateTimeFormat("en-CA", {
    timeZone: "America/Fortaleza",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(new Date(value));
export const dateTime = (value: string) =>
  new Date(value).toLocaleString("pt-BR", {
    timeZone: "America/Fortaleza",
    day: "2-digit",
    month: "short",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
export function inView(value: string, day: string, view: string) {
  const date = localDay(value);
  if (view === "all") return true;
  if (view === "day") return date === day;
  const start = new Date(day + "T12:00:00-03:00");
  start.setUTCDate(start.getUTCDate() - ((start.getUTCDay() + 6) % 7));
  const end = new Date(start);
  end.setUTCDate(end.getUTCDate() + 6);
  return (
    date >= localDay(start.toISOString()) && date <= localDay(end.toISOString())
  );
}

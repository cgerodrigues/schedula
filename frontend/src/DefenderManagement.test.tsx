// @vitest-environment jsdom
import React from "react";
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import DefenderManagement from "./DefenderManagement";
import { InvitationModal } from "./InvitationModal";
import { api } from "./api";
import { navigate, defendersPath } from "./navigation";
vi.mock("./api", () => ({ api: vi.fn() }));
const mock = vi.mocked(api);
const pending = {
  id: "pending",
  name: "Pessoa Pendente",
  username: "pessoa",
  status: "PENDING_ACTIVATION",
  invitation_status: "AVAILABLE",
};
const active = {
  id: "active",
  name: "Pessoa Ativa",
  username: "ativa",
  status: "ACTIVE",
  invitation_status: "USED",
};
const invitation = {
  token: "x".repeat(43),
  expiresAt: new Date(Date.now() + 86400000).toISOString(),
};
const page = { items: [pending, active], total: 21, page: 1, totalPages: 3 };
const defaults = async (path: string, method = "GET") => {
  if (path.startsWith("/rh/defenders/search"))
    return {
      ...page,
      page: Number(new URLSearchParams(path.split("?")[1]).get("page") || 1),
    };
  if (path.endsWith("/invitation")) return invitation;
  if (path === "/rh/defenders" && method === "POST") return { id: "created" };
  return pending;
};
beforeEach(() => {
  window.history.replaceState({}, "", defendersPath);
  mock.mockImplementation(defaults as typeof api);
});
afterEach(() => {
  cleanup();
  vi.resetAllMocks();
  vi.useRealTimers();
});
it("lists a server page, combines filters and varies activation actions by account state", async () => {
  render(<DefenderManagement />);
  await screen.findByText("Pessoa Pendente");
  const activeRow = screen.getByText("Pessoa Ativa").closest("tr")!;
  expect(within(activeRow).queryByText(/convite/i)).toBeNull();
  fireEvent.click(screen.getByRole("button", {name:"Ações de convite de Pessoa Pendente"}));
  expect(screen.getByText("Regenerar convite")).toBeTruthy();
  expect(screen.getByText("Revogar convite")).toBeTruthy();
  expect(screen.queryByLabelText("Nome completo")).toBeNull();
  fireEvent.click(screen.getByText("Próxima"));
  await screen.findByText("21 defensores · Página 2 de 3");
  fireEvent.change(screen.getByLabelText("Buscar defensor"), {
    target: { value: "Pessoa" },
  });
  await waitFor(() => expect(window.location.search).toContain("name=Pessoa"));
  fireEvent.change(screen.getByLabelText("Situação da conta"), {
    target: { value: "ACTIVE" },
  });
  await waitFor(() =>
    expect(mock.mock.calls.at(-1)?.[0]).toContain("status=ACTIVE"),
  );
  expect(new URLSearchParams(window.location.search).get("page")).toBe("1");
  expect(mock.mock.calls.some((c) => c[0] === "/rh/defenders")).toBe(false);
});
it("navigates to new, cancels to filtered list and supports history", async () => {
  window.history.replaceState({}, "", defendersPath + "?status=ACTIVE&page=2");
  render(<DefenderManagement />);
  fireEvent.click(await screen.findByText("+ Novo defensor"));
  await screen.findByText("Cadastrar defensor");
  expect(window.location.pathname).toBe(defendersPath + "/novo");
  fireEvent.click(screen.getByText("Cancelar"));
  await screen.findByText("21 defensores · Página 2 de 3");
  act(() => window.history.back());
  await screen.findByText("Cadastrar defensor");
  act(() => window.history.forward());
  await screen.findByText("21 defensores · Página 2 de 3");
});
it("loads direct edit and submits only name, retaining username and hiding password fields", async () => {
  window.history.replaceState({}, "", defendersPath + "/pending/editar");
  render(<DefenderManagement />);
  const name = await screen.findByLabelText("Nome completo");
  expect(screen.getByText(/Usuário: pessoa/)).toBeTruthy();
  expect(screen.queryByLabelText("Usuário de acesso")).toBeNull();
  expect(document.querySelector('input[type="password"]')).toBeNull();
  fireEvent.change(name, { target: { value: "Nome corrigido" } });
  fireEvent.click(screen.getByText("Salvar alterações"));
  await screen.findByText("Dados do defensor atualizados.");
  expect(mock).toHaveBeenCalledWith("/rh/defenders/pending", "PUT", {
    name: "Nome corrigido",
  });
});
it("creates once, then shows a transient invitation modal and copies the link", async () => {
  window.history.replaceState({}, "", defendersPath + "/novo");
  const copy = vi.fn().mockResolvedValue(undefined);
  Object.defineProperty(navigator, "clipboard", {
    configurable: true,
    value: { writeText: copy },
  });
  render(<DefenderManagement />);
  fireEvent.change(screen.getByLabelText("Nome completo"), {
    target: { value: "Nova Pessoa" },
  });
  fireEvent.change(screen.getByLabelText("Usuário de acesso"), {
    target: { value: "novapessoa" },
  });
  fireEvent.click(screen.getByText("Cadastrar defensor"));
  const modal = await screen.findByRole("dialog");
  expect(within(modal).getByText("24 horas")).toBeTruthy();
  expect(
    mock.mock.calls.findIndex((c) => c[0] === "/rh/defenders"),
  ).toBeLessThan(
    mock.mock.calls.findIndex(
      (c) => c[0] === "/rh/defenders/created/invitation",
    ),
  );
  fireEvent.click(screen.getByText("Copiar link"));
  await screen.findByText("Link copiado.");
  expect(copy).toHaveBeenCalledWith(
    window.location.origin + "/#activate=" + invitation.token,
  );
  expect(JSON.stringify(window.history.state)).not.toContain(invitation.token);
  expect(localStorage.length + sessionStorage.length).toBe(0);
  fireEvent.click(screen.getByText("Fechar"));
  await screen.findByText("Defensor cadastrado com sucesso.");
  expect(screen.queryByRole("dialog")).toBeNull();
});
it("does not recreate a defender if invitation generation fails, and prevents duplicate submissions", async () => {
  window.history.replaceState({}, "", defendersPath + "/novo");
  let finish!: (value: unknown) => void,
    attempts = 0;
  mock.mockImplementation((async (path: string, method: string) => {
    if (path === "/rh/defenders")
      return new Promise<unknown>((resolve) => {
        finish = resolve;
      });
    if (path.endsWith("/invitation") && ++attempts === 1)
      throw new Error("Serviço indisponível.");
    return defaults(path, method);
  }) as typeof api);
  render(<DefenderManagement />);
  fireEvent.change(screen.getByLabelText("Nome completo"), {
    target: { value: "Nova Pessoa" },
  });
  fireEvent.change(screen.getByLabelText("Usuário de acesso"), {
    target: { value: "novapessoa" },
  });
  const submit = screen.getByText("Cadastrar defensor");
  fireEvent.click(submit);
  fireEvent.click(submit);
  expect(mock.mock.calls.filter((c) => c[0] === "/rh/defenders")).toHaveLength(
    1,
  );
  await act(async () => finish({ id: "created" }));
  await screen.findByText("Serviço indisponível.");
  expect(
    (screen.getByLabelText("Nome completo") as HTMLInputElement).value,
  ).toBe("Nova Pessoa");
  expect(screen.queryByRole("dialog")).toBeNull();
  fireEvent.click(screen.getByText("Gerar convite"));
  await screen.findByRole("dialog");
  expect(mock.mock.calls.filter((c) => c[0] === "/rh/defenders")).toHaveLength(
    1,
  );
});
it("revokes and regenerates through existing endpoints", async () => {
  render(<DefenderManagement />);
  fireEvent.click(await screen.findByRole("button", {name:"Ações de convite de Pessoa Pendente"}));
  fireEvent.click(screen.getByText("Revogar convite"));
  expect(mock.mock.calls.some(c => c[1] === "DELETE")).toBe(false);
  fireEvent.click(within(screen.getByRole("dialog")).getByText("Cancelar"));
  expect(mock.mock.calls.some(c => c[1] === "DELETE")).toBe(false);
  fireEvent.click(screen.getByRole("button", {name:"Ações de convite de Pessoa Pendente"}));
  fireEvent.click(screen.getByText("Revogar convite"));
  fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", {name:"Revogar convite"}));
  await screen.findByText("Convite revogado com sucesso.");
  expect(mock).toHaveBeenCalledWith(
    "/rh/defenders/pending/invitation",
    "DELETE",
  );
  fireEvent.click(await screen.findByRole("button", {name:"Ações de convite de Pessoa Pendente"}));
  fireEvent.click(screen.getByText("Regenerar convite"));
  expect(mock.mock.calls.some(c => c[1] === "POST")).toBe(false);
  fireEvent.click(within(screen.getByRole("dialog")).getByText("Gerar novo convite"));
  await screen.findByLabelText("Link de ativação");
  expect(mock).toHaveBeenCalledWith("/rh/defenders/pending/invitation", "POST");
  fireEvent(
    screen.getByRole("dialog"),
    new Event("cancel", { bubbles: true, cancelable: true }),
  );
  expect(screen.queryByRole("dialog")).toBeNull();
});
it("distinguishes empty, filtered empty and loading failure, and ignores stale responses", async () => {
  let finish!: (value: unknown) => void;
  mock.mockImplementationOnce(
    () =>
      new Promise<unknown>((resolve) => {
        finish = resolve;
      }) as never,
  );
  render(<DefenderManagement />);
  expect(screen.getByText("Carregando defensores…")).toBeTruthy();
  act(() => navigate(defendersPath + "?name=Pessoa"));
  await screen.findByText("Pessoa Ativa");
  await act(async () =>
    finish({ ...page, items: [{ ...pending, name: "Resultado antigo" }] }),
  );
  expect(screen.queryByText("Resultado antigo")).toBeNull();
  mock.mockResolvedValue({ ...page, items: [], total: 0, totalPages: 1 });
  act(() => navigate(defendersPath));
  await screen.findByText("Nenhum defensor cadastrado.");
  act(() => navigate(defendersPath + "?name=ausente"));
  await screen.findByText("Nenhum defensor corresponde aos filtros.");
  mock.mockRejectedValueOnce(new Error("Falha na consulta."));
  fireEvent.click(screen.getByText("Atualizar lista"));
  await screen.findByText("Falha na consulta.");
  expect(
    screen.queryByText("Nenhum defensor corresponde aos filtros."),
  ).toBeNull();
});
it("does not show empty editable fields when detail lookup fails", async () => {
  window.history.replaceState({}, "", defendersPath + "/missing/editar");
  mock.mockRejectedValue(new Error("Registro não encontrado."));
  render(<DefenderManagement />);
  await screen.findByRole("alert");
  expect(screen.queryByLabelText("Nome completo")).toBeNull();
});
it("never presents an expired invitation link and offers manual copying if clipboard fails", async () => {
  const onClose = vi.fn();
  Object.defineProperty(navigator, "clipboard", {
    configurable: true,
    value: { writeText: vi.fn().mockRejectedValue(new Error("denied")) },
  });
  const { rerender } = render(
    <InvitationModal invitation={invitation} name="Pessoa" onClose={onClose} />,
  );
  fireEvent.click(screen.getByText("Copiar link"));
  await screen.findByText(/Selecione o link e copie manualmente/);
  rerender(
    <InvitationModal
      key="expired"
      invitation={{ ...invitation, expiresAt: new Date(0).toISOString() }}
      name="Pessoa"
      onClose={onClose}
    />,
  );
  expect(screen.queryByText("Copiar link")).toBeNull();
  expect(screen.queryByLabelText("Link de ativação")).toBeNull();
});
it.each([0,1,2])("uses correct counters for %i defenders", async total => {
  mock.mockResolvedValue({items:total ? [pending] : [], total,page:1,totalPages:1});
  render(<DefenderManagement/>);
  await screen.findByText(`${total} ${total === 1 ? "defensor encontrado" : "defensores encontrados"}`);
  expect(screen.getByText(`${total} ${total === 1 ? "defensor" : "defensores"} · Página 1 de 1`)).toBeTruthy();
});
it.each(["REVOKED","EXPIRED","NONE"])("generates directly for %s without replacement warning", async invitation_status => {
  mock.mockImplementation((async (path:string) => path.endsWith("/invitation") ? invitation : {...page,items:[{...pending,invitation_status}]}) as typeof api);
  render(<DefenderManagement/>);
  fireEvent.click(await screen.findByRole("button",{name:"Ações de convite de Pessoa Pendente"}));
  expect(screen.queryByText("Regenerar convite")).toBeNull();
  expect(screen.queryByText("Revogar convite")).toBeNull();
  fireEvent.click(screen.getByText("Gerar convite"));
  await screen.findByLabelText("Link de ativação");
  expect(mock).toHaveBeenCalledWith("/rh/defenders/pending/invitation","POST");
});
it("keeps failed confirmation open, prevents double revocation, and never announces success", async () => {
  let reject!: (reason: Error) => void;
  mock.mockImplementation((async (path:string,method:string) => method === "DELETE" ? new Promise((_,fail) => {reject=fail}) : defaults(path,method)) as typeof api);
  render(<DefenderManagement/>);
  fireEvent.click(await screen.findByRole("button",{name:"Ações de convite de Pessoa Pendente"}));
  fireEvent.click(screen.getByText("Revogar convite"));
  const button = within(screen.getByRole("dialog")).getByRole("button",{name:"Revogar convite"});
  fireEvent.click(button); fireEvent.click(button);
  expect(mock.mock.calls.filter(c=>c[1]==="DELETE")).toHaveLength(1);
  expect((within(screen.getByRole("dialog")).getByText("Cancelar") as HTMLButtonElement).disabled).toBe(true);
  await act(async()=>reject(new Error("Não foi possível revogar. Tente novamente.")));
  expect(within(screen.getByRole("dialog")).getByRole("alert").textContent).toContain("Não foi possível revogar");
  expect(screen.queryByText("Convite revogado com sucesso.")).toBeNull();
  fireEvent.click(screen.getByText("Cancelar"));
  expect(screen.queryByRole("dialog")).toBeNull();
});

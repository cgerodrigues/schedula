// @vitest-environment jsdom
import React from "react";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { App } from "./main";
import { api } from "./api";
vi.mock("./api", async (original) => ({
  ...(await original<typeof import("./api")>()),
  api: vi.fn(),
}));
const mock = vi.mocked(api);
beforeEach(() => {
  window.history.replaceState({}, "", "/rh/defensores");
  mock.mockImplementation((async (path: string) =>
    path === "/auth/me"
      ? { role: "RH", name: "RH" }
      : path.startsWith("/rh/defenders/search")
        ? { items: [], total: 0, page: 1, totalPages: 1 }
        : []) as typeof api);
});
afterEach(() => {
  cleanup();
  vi.resetAllMocks();
});
it("opens the direct defender URL without preloading legacy arrays", async () => {
  render(<App />);
  await screen.findByText("Nenhum defensor cadastrado.");
  expect(
    mock.mock.calls.some(([path]) =>
      ["/rh/defenders", "/rh/units"].includes(path),
    ),
  ).toBe(false);
  fireEvent.click(screen.getByText("+ Novo defensor"));
  await screen.findByText("Cadastrar defensor");
  expect(window.location.pathname).toBe("/rh/defensores/novo");
});
it("preserves direct create route through login", async () => {
  window.history.replaceState({}, "", "/rh/defensores/novo");
  mock.mockRejectedValueOnce(new Error("Unauthenticated"));
  render(<App />);
  fireEvent.change(await screen.findByLabelText("Usuário"), {
    target: { value: "rh" },
  });
  fireEvent.change(screen.getByLabelText("Senha"), {
    target: { value: "test-only-password" },
  });
  fireEvent.click(screen.getByText("Entrar no Schedula →"));
  await screen.findByText("Cadastrar defensor");
  expect(window.location.pathname).toBe("/rh/defensores/novo");
});
it("denies administrative routes to defender sessions", async () => {
  mock.mockImplementation((async (path: string) =>
    path === "/auth/me"
      ? { role: "DEFENSOR", name: "Defensor" }
      : []) as typeof api);
  render(<App />);
  await screen.findByText(
    "Você não tem permissão para acessar a gestão de defensores.",
  );
  expect(mock.mock.calls.some(([path]) => path.startsWith("/rh/"))).toBe(false);
});
it("loads aggregate count on overview and keeps the legacy list for affiliations", async () => {
  window.history.replaceState({}, "", "/");
  mock.mockImplementation((async (path:string) => path === "/auth/me" ? {role:"RH", name:"RH"} : path === "/rh/defenders/indicators" ? {total:1234} : []) as typeof api);
  render(<App/>);
  await screen.findByText("1234");
  expect(mock.mock.calls.some(([path]) => path === "/rh/defenders")).toBe(false);
  fireEvent.click(screen.getByText("Vínculos e áreas"));
  await screen.findByText("Autorizar atuação");
  expect(mock.mock.calls.some(([path]) => path === "/rh/defenders")).toBe(true);
});
it.each(["/rh/defensores", "/rh/defensores/novo", "/rh/defensores/pending/editar"])("keeps one shared defender header and footer at %s", async path => {
  window.history.replaceState({}, "", path);
  mock.mockImplementation((async (url:string) => url === "/auth/me" ? {role:"RH",name:"RH"}
    : url.startsWith("/rh/defenders/search") ? {items:[],total:0,page:1,totalPages:1}
    : {id:"pending",name:"Nome de teste",username:"teste"}) as typeof api);
  render(<App/>);
  const title = await screen.findByRole("heading", {name:"Defensores",level:1});
  const subtitle = screen.getByText("Organize a rede. Amplie o acesso ao atendimento.");
  expect(title.closest("header")).toBe(subtitle.closest("header"));
  expect(subtitle.closest(".card")).toBeNull();
  expect(screen.getAllByText("Organize a rede. Amplie o acesso ao atendimento.")).toHaveLength(1);
  expect(document.querySelectorAll("main footer")).toHaveLength(1);
});

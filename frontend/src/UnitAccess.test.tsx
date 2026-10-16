// @vitest-environment jsdom
import React from "react";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { App } from "./main";
import { api } from "./api";
vi.mock("./api", async (original) => ({
  ...(await original<typeof import("./api")>()),
  api: vi.fn(),
}));
const mock = vi.mocked(api);
const defaults = async (path: string) => {
  if (path === "/auth/me") return { role: "RH", name: "RH" };
  if (path.startsWith("/rh/units/search"))
    return { items: [], total: 0, page: 1, size: 10, totalPages: 1 };
  if (path === "/rh/states") return [{ code: "CE", name: "Ceará" }];
  if (path.startsWith("/rh/catalog"))
    return { complete: true, municipalities: [] };
  return [];
};
beforeEach(() => {
  window.history.replaceState({}, "", "/rh/unidades");
  mock.mockImplementation(defaults as typeof api);
});
afterEach(() => {
  cleanup();
  vi.resetAllMocks();
});
it("restores a direct list URL without fetching the legacy full unit list", async () => {
  render(<App />);
  await screen.findByText("Nenhuma unidade cadastrada.");
  expect(mock).not.toHaveBeenCalledWith("/rh/units");
  fireEvent.click(screen.getByText("Visão geral"));
  await waitFor(() => expect(mock).toHaveBeenCalledWith("/rh/units"));
  fireEvent.click(screen.getByRole("button", { name: "Unidades" }));
  await screen.findByText("Nenhuma unidade cadastrada.");
  expect(window.location.pathname).toBe("/rh/unidades");
});
it("retains the intended create route after authentication", async () => {
  window.history.replaceState({}, "", "/rh/unidades/nova");
  mock.mockRejectedValueOnce(new Error("Unauthenticated"));
  render(<App />);
  await screen.findByLabelText("Usuário");
  fireEvent.change(screen.getByLabelText("Usuário"), {
    target: { value: "rh" },
  });
  fireEvent.change(screen.getByLabelText("Senha"), {
    target: { value: "test-only-password" },
  });
  fireEvent.click(screen.getByText("Entrar no Schedula →"));
  await screen.findByText("Cadastrar unidade");
  expect(window.location.pathname).toBe("/rh/unidades/nova");
});
it("denies unit routes to defenders without calling unit APIs", async () => {
  mock.mockImplementation((async (path: string) =>
    path === "/auth/me"
      ? { role: "DEFENSOR", name: "Defensor" }
      : []) as typeof api);
  render(<App />);
  await screen.findByText(
    "Você não tem permissão para acessar a gestão de unidades.",
  );
  expect(mock.mock.calls.some(([path]) => path.startsWith("/rh/"))).toBe(false);
});

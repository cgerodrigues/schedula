// @vitest-environment jsdom
import React from "react";
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import UnitPanel from "./UnitPanel";
import { api } from "./api";
import { navigate, unitsPath } from "./navigation";
vi.mock("./api", () => ({ api: vi.fn() }));
const mock = vi.mocked(api);
const unit = {
  id: "one",
  name: "Unidade Centro",
  municipality: "Fortaleza",
  state_code: "CE",
  municipality_id: "city",
  phone: "8533334444",
  active: true,
  areas: "CIVEL",
  postal_code: "60175000",
  street: "Rua",
  street_number: "10",
  neighborhood: "Centro",
};
const page = { items: [unit], total: 21, page: 1, size: 10, totalPages: 3 };
const defaults = async (path: string) => {
  if (path.startsWith("/rh/units/search"))
    return {
      ...page,
      page: Number(new URLSearchParams(path.split("?")[1]).get("page") || 1),
    };
  if (path === "/rh/states") return [{ code: "CE", name: "Ceará" }];
  if (path.startsWith("/rh/catalog"))
    return {
      complete: true,
      municipalities: [{ id: "city", name: "Fortaleza" }],
    };
  return unit;
};
beforeEach(() => {
  window.history.replaceState({}, "", unitsPath);
  mock.mockImplementation(defaults as typeof api);
});
afterEach(() => {
  cleanup();
  vi.resetAllMocks();
});
it("lists only a server page and combines debounced filters, resetting page", async () => {
  render(<UnitPanel />);
  await screen.findByText("(85) 3333-4444");
  expect(screen.queryByLabelText("Nome da unidade")).toBeNull();
  expect(mock.mock.calls.some((c) => c[0] === "/rh/units")).toBe(false);
  fireEvent.click(screen.getByText("Próxima"));
  await screen.findByText("21 unidades · Página 2 de 3");
  fireEvent.change(screen.getByLabelText("Buscar unidade"), {
    target: { value: "Centro" },
  });
  await waitFor(() => expect(window.location.search).toContain("name=Centro"));
  expect(window.location.search).toContain("page=1");
  fireEvent.change(screen.getByLabelText("Filtrar município"), {
    target: { value: "Fortaleza" },
  });
  fireEvent.change(screen.getByLabelText("Área"), {
    target: { value: "CIVEL" },
  });
  fireEvent.change(screen.getByLabelText("Situação"), {
    target: { value: "false" },
  });
  await waitFor(() =>
    expect(mock.mock.calls.at(-1)?.[0]).toContain("active=false"),
  );
  const q = new URLSearchParams(window.location.search);
  expect(Object.fromEntries(q)).toMatchObject({
    name: "Centro",
    municipality: "Fortaleza",
    area: "CIVEL",
    active: "false",
    page: "1",
  });
});
it("keeps filters on cancel and supports browser back and forward", async () => {
  window.history.replaceState({}, "", unitsPath + "?area=CIVEL&page=2");
  render(<UnitPanel />);
  fireEvent.click(await screen.findByText("+ Nova unidade"));
  await screen.findByText("Cadastrar unidade");
  expect(window.location.pathname).toBe(unitsPath + "/nova");
  fireEvent.click(screen.getByText("Cancelar"));
  await screen.findByText("21 unidades · Página 2 de 3");
  expect(window.location.search).toBe("?area=CIVEL&page=2");
  act(() => window.history.back());
  await screen.findByText("Cadastrar unidade");
  act(() => window.history.forward());
  await screen.findByText("21 unidades · Página 2 de 3");
});
it("direct edit loads by id and save returns to list with feedback and locator", async () => {
  window.history.replaceState({}, "", unitsPath + "/one/editar");
  render(<UnitPanel />);
  await screen.findByLabelText("Nome da unidade");
  fireEvent.change(screen.getByLabelText("Nome da unidade"), {
    target: { value: "Nome alterado" },
  });
  fireEvent.click(screen.getByText("Salvar alterações"));
  await screen.findByText("Unidade salva com sucesso.");
  expect(window.location.pathname).toBe(unitsPath);
  expect(mock).toHaveBeenCalledWith(
    "/rh/units/one",
    "PUT",
    expect.objectContaining({ name: "Nome alterado", phone: "8533334444" }),
  );
  fireEvent.click(screen.getByText("Localizar unidade salva"));
  expect(new URLSearchParams(window.location.search).get("name")).toBe(
    "Nome alterado",
  );
});
it("creates through the separate form then returns to list", async () => {
  window.history.replaceState({}, "", unitsPath + "/nova");
  render(<UnitPanel />);
  await screen.findByText("Catálogo oficial salvo localmente");
  for (const [label, value] of [
    ["Nome da unidade", "Nova sede"],
    ["CEP", "60175000"],
    ["Logradouro", "Rua"],
    ["Número", "10"],
    ["Bairro", "Centro"],
    ["Telefone de contato", "8533334444"],
  ])
    fireEvent.change(screen.getByLabelText(label), { target: { value } });
  fireEvent.click(screen.getByLabelText("Cível"));
  fireEvent.click(screen.getByRole("combobox", { name: "Município" }));
  fireEvent.click(screen.getByRole("option", { name: "Fortaleza" }));
  fireEvent.click(screen.getByText("Cadastrar unidade"));
  await screen.findByText("Unidade salva com sucesso.");
  expect(mock).toHaveBeenCalledWith(
    "/rh/units",
    "POST",
    expect.objectContaining({ name: "Nova sede", municipalityId: "city" }),
  );
});
it("does not expose an empty edit form when a record fails to load", async () => {
  window.history.replaceState({}, "", unitsPath + "/missing/editar");
  mock.mockRejectedValue(new Error("Registro não encontrado ou indisponível."));
  render(<UnitPanel />);
  await screen.findByRole("alert");
  expect(screen.queryByLabelText("Nome da unidade")).toBeNull();
  expect(screen.queryByText("Cadastrar unidade")).toBeNull();
});
it("distinguishes empty, filtered-empty, network failure, and retry", async () => {
  mock.mockResolvedValue({ ...page, items: [], total: 0, totalPages: 1 });
  render(<UnitPanel />);
  await screen.findByText("Nenhuma unidade cadastrada.");
  act(() => navigate(unitsPath + "?name=ausente"));
  await screen.findByText("Nenhuma unidade corresponde aos filtros.");
  mock.mockRejectedValueOnce(new TypeError("Failed to fetch"));
  fireEvent.click(screen.getByText("Atualizar lista"));
  await screen.findByText(
    "Não foi possível conectar ao serviço. Tente novamente.",
  );
  expect(
    screen.queryByText("Nenhuma unidade corresponde aos filtros."),
  ).toBeNull();
  fireEvent.click(screen.getByText("Atualizar lista"));
  await screen.findByText("Nenhuma unidade corresponde aos filtros.");
});
it("ignores old list responses after a newer filter and accepts page clamping", async () => {
  let old!: (value: unknown) => void;
  mock.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        old = resolve;
      }),
  );
  render(<UnitPanel />);
  act(() => navigate(unitsPath + "?name=atual"));
  await screen.findByText("Unidade Centro");
  await act(async () =>
    old({ ...page, items: [{ ...unit, name: "Resposta antiga" }] }),
  );
  expect(screen.queryByText("Resposta antiga")).toBeNull();
  mock.mockResolvedValue({ ...page, page: 1, total: 1, totalPages: 1 });
  act(() => navigate(unitsPath + "?page=99"));
  await waitFor(() => expect(window.location.search).toBe("?page=1"));
  await screen.findByText("1 unidade · Página 1 de 1");
});

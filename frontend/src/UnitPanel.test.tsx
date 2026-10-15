// @vitest-environment jsdom
import React from "react";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  act,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import UnitPanel, { UnitForm } from "./UnitPanel";
import { api } from "./api";
import { phoneMask, cepMask } from "./unitFields";
vi.mock("./api", () => ({ api: vi.fn() }));
const mock = vi.mocked(api);
const city = {
  id: "fortaleza",
  name: "Fortaleza",
  state_code: "CE",
  ibge_code: "2304400",
};
const catalog = {
  complete: true,
  syncedAt: "2026-10-14",
  warning: "",
  municipalities: [
    city,
    { id: "caucaia", name: "Caucaia", ibge_code: "2303709" },
  ],
};
const unit = {
  id: "unit",
  name: "Unidade Centro",
  state_code: "CE",
  municipality_id: "fortaleza",
  municipality: "Fortaleza",
  areas: "CIVEL,CRIMINAL",
  active: true,
  address: "Endereço antigo",
  phone: "(85) 3000-0000",
  street: "Rua manual",
  street_number: "100",
  neighborhood: "Centro",
  postal_code: "60175000",
};
const postal = {
  postalCode: "60175001",
  street: "Rua automática",
  neighborhood: "Bairro automático",
  city: "Fortaleza",
  stateCode: "CE",
  ibgeCode: "2304400",
};
const defaults = async (path: string) => {
  if (path === "/rh/states")
    return [
      { code: "CE", name: "Ceará" },
      { code: "PE", name: "Pernambuco" },
    ];
  if (path.startsWith("/rh/catalog/PE"))
    return {
      ...catalog,
      municipalities: [{ id: "recife", name: "Recife", ibge_code: "2611606" }],
    };
  if (path.startsWith("/rh/catalog/")) return catalog;
  if (path.startsWith("/rh/postal/")) return postal;
  return { id: "saved" };
};
beforeEach(() => mock.mockImplementation(defaults as typeof api));
afterEach(() => {
  cleanup();
  vi.resetAllMocks();
});
const field = (label: string) =>
  screen.getByLabelText(label) as HTMLInputElement;
const change = (label: string, value: string) =>
  fireEvent.change(field(label), { target: { value } });
async function editor(u: typeof unit = unit) {
  render(<UnitForm unit={u} onSaved={vi.fn()} onCancel={vi.fn()} />);
  await screen.findByText("Catálogo oficial salvo localmente");
}

describe("unit fields", () => {
  it("masks landlines, mobiles, partial input, deletion and postal code", () => {
    expect(phoneMask("8533334444")).toBe("(85) 3333-4444");
    expect(phoneMask("85999999999")).toBe("(85) 99999-9999");
    expect(phoneMask("85")).toBe("(85");
    expect(phoneMask("")).toBe("");
    expect(phoneMask("(85) 3333-444")).toBe("(85) 3333-444");
    expect(cepMask("60175000")).toBe("60175-000");
    expect(cepMask("601")).toBe("601");
  });
  it("edits all fields with same id and retains original address visibility", async () => {
    await editor();
    expect(
      screen.getByText(/Endereço anterior preservado/).textContent,
    ).toContain("Endereço antigo");
    change("Nome da unidade", "Novo nome");
    change("Telefone de contato", "85999999999");
    change("E-mail institucional (opcional)", "unidade@example.org");
    change("Logradouro", "Rua corrigida");
    change("Número", "S/N");
    change("Complemento (opcional)", "Sala 2");
    change("Bairro", "Novo bairro");
    fireEvent.click(screen.getByText("Salvar unidade"));
    await screen.findByText("Unidade salva com sucesso.");
    expect(mock).toHaveBeenCalledWith(
      "/rh/units/unit",
      "PUT",
      expect.objectContaining({
        name: "Novo nome",
        phone: "(85) 99999-9999",
        street: "Rua corrigida",
        streetNumber: "S/N",
        complement: "Sala 2",
        email: "unidade@example.org",
        areas: ["CIVEL", "CRIMINAL"],
      }),
    );
  });
  it("filters municipalities by search and clears selection after changing state", async () => {
    await editor();
    change("Pesquisar município", "cauc");
    expect(screen.getByRole("option", { name: "Caucaia" })).toBeTruthy();
    change("Estado", "PE");
    await screen.findByRole("option", { name: "Recife" });
    expect(field("Município").value).toBe("");
    expect(screen.queryByRole("option", { name: "Caucaia" })).toBeNull();
  });
  it("blocks missing areas and preserves form after backend failure", async () => {
    await editor();
    fireEvent.click(field("Cível"));
    fireEvent.click(field("Criminal"));
    fireEvent.click(screen.getByText("Salvar unidade"));
    await screen.findByText("Selecione ao menos uma área de atendimento.");
    fireEvent.click(field("Cível"));
    mock.mockRejectedValueOnce(new Error("Área possui vínculos"));
    fireEvent.click(screen.getByText("Salvar unidade"));
    await screen.findByText("Área possui vínculos");
    expect(field("Nome da unidade").value).toBe("Unidade Centro");
  });
  it("autofills empty fields without overwriting manual edits", async () => {
    await editor({ ...unit, street: "", neighborhood: "" });
    change("CEP", "60175001");
    change("Logradouro", "Manual durante consulta");
    await waitFor(() =>
      expect(field("Bairro").value).toBe("Bairro automático"),
    );
    expect(field("Logradouro").value).toBe("Manual durante consulta");
  });
  it("shows failures without erasing address and permits manual submission", async () => {
    mock.mockImplementation((async (path: string) => {
      if (path.startsWith("/rh/postal/"))
        throw new Error("ViaCEP indisponível. Preencha manualmente.");
      return defaults(path);
    }) as typeof api);
    await editor();
    change("CEP", "60175001");
    await screen.findByText("ViaCEP indisponível. Preencha manualmente.");
    expect(field("Logradouro").value).toBe("Rua manual");
    fireEvent.click(screen.getByText("Salvar unidade"));
    await screen.findByText("Unidade salva com sucesso.");
  });
  it("requires explicit confirmation and official matching before changing CEP city", async () => {
    mock.mockImplementation((async (path: string) =>
      path.startsWith("/rh/postal/")
        ? { ...postal, city: "Recife", stateCode: "PE", ibgeCode: "2611606" }
        : defaults(path)) as typeof api);
    await editor();
    change("CEP", "50000000");
    await screen.findByText(/O CEP indica Recife/);
    expect(field("Estado").value).toBe("CE");
    fireEvent.click(screen.getByText("Salvar unidade"));
    await screen.findByText(/Confirme ou mantenha/);
    fireEvent.click(screen.getByText("Usar município do CEP"));
    await waitFor(() => expect(field("Município").value).toBe("recife"));
    expect(field("Estado").value).toBe("PE");
    expect(field("Logradouro").value).toBe("Rua manual");
  });
  it("can retain current location after a divergent CEP", async () => {
    mock.mockImplementation((async (path: string) =>
      path.startsWith("/rh/postal/")
        ? { ...postal, city: "Recife", stateCode: "PE", ibgeCode: "2611606" }
        : defaults(path)) as typeof api);
    await editor();
    change("CEP", "50000000");
    await screen.findByText(/O CEP indica Recife/);
    fireEvent.click(screen.getByText("Manter localização selecionada"));
    expect(field("Estado").value).toBe("CE");
    expect(field("Município").value).toBe("fortaleza");
  });
  it("ignores old CEP responses", async () => {
    let resolveOld!: (value: unknown) => void;
    mock.mockImplementation((async (path: string) =>
      path === "/rh/postal/60175001"
        ? new Promise<unknown>((resolve) => {
            resolveOld = resolve;
          })
        : defaults(path)) as typeof api);
    await editor({ ...unit, street: "", neighborhood: "" });
    change("CEP", "60175001");
    await waitFor(() => expect(resolveOld).toBeTypeOf("function"));
    change("CEP", "60175002");
    await waitFor(() =>
      expect(field("Logradouro").value).toBe("Rua automática"),
    );
    await act(async () => {
      resolveOld({ ...postal, street: "Resultado antigo" });
    });
    expect(field("Logradouro").value).toBe("Rua automática");
  });
  it("does not query incomplete CEP or overwrite after municipality changes", async () => {
    let resolveOld!: (value: unknown) => void;
    mock.mockImplementation((async (path: string) =>
      path.startsWith("/rh/postal/")
        ? new Promise<unknown>((resolve) => {
            resolveOld = resolve;
          })
        : defaults(path)) as typeof api);
    await editor({ ...unit, street: "", neighborhood: "" });
    change("CEP", "601");
    expect(resolveOld).toBeUndefined();
    change("CEP", "60175001");
    await waitFor(() => expect(resolveOld).toBeTypeOf("function"));
    change("Município", "caucaia");
    await act(async () => {
      resolveOld(postal);
    });
    expect(field("Logradouro").value).toBe("");
    expect(field("Município").value).toBe("caucaia");
  });
  it("prevents duplicate saves while pending", async () => {
    let finish!: (value: unknown) => void;
    mock.mockImplementation((async (path: string, method: string) =>
      method === "PUT"
        ? new Promise<unknown>((resolve) => {
            finish = resolve;
          })
        : defaults(path)) as typeof api);
    await editor();
    const button = screen.getByText("Salvar unidade");
    fireEvent.click(button);
    fireEvent.click(button);
    expect(mock.mock.calls.filter((c) => c[1] === "PUT")).toHaveLength(1);
    await act(async () => finish({ id: "unit" }));
    await screen.findByText("Unidade salva com sucesso.");
  });
  it("opens the reusable editor from the existing unit list", async () => {
    render(<UnitPanel units={[unit]} reload={vi.fn()} />);
    fireEvent.click(screen.getByText("Editar Unidade Centro"));
    await screen.findByText("Editar unidade");
    expect(field("Nome da unidade").value).toBe("Unidade Centro");
  });

  it("looks up the original CEP again after a later change", async () => {
    await editor();
    change("CEP", "60175001");
    await screen.findByText(/CEP consultado/);
    change("CEP", "60175000");
    await waitFor(() => expect(mock).toHaveBeenCalledWith("/rh/postal/60175000"));
  });
});

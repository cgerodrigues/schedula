// @vitest-environment jsdom
import React from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { render, screen, fireEvent, cleanup } from "@testing-library/react";
import {
  Activation,
  passwordFieldsMatch,
  PasswordChange,
} from "./AccountAccess";
import { api } from "./api";
vi.mock("./api", () => ({ api: vi.fn() }));
afterEach(() => {
  cleanup();
  vi.resetAllMocks();
});
describe("Ativação e senha", () => {
  it("impede envio de confirmação divergente", () => {
    const f = new FormData();
    f.set("password", "a");
    f.set("confirmation", "b");
    expect(() => passwordFieldsMatch(f)).toThrow("As senhas não coincidem.");
  });
  it("mostra convite expirado sem formulário", async () => {
    vi.mocked(api).mockRejectedValue(
      new Error("Convite expirado. Solicite outro ao RH."),
    );
    render(<Activation token="fictitious" />);
    expect((await screen.findByRole("alert")).textContent).toContain(
      "expirado",
    );
    expect(
      screen.queryByLabelText("Nova senha (mínimo 12 caracteres)"),
    ).toBeNull();
  });
  it("ativa por POST e orienta login sem autenticação automática", async () => {
    vi.mocked(api).mockResolvedValue(undefined as never);
    render(<Activation token="fictitious" />);
    fireEvent.change(
      await screen.findByLabelText("Nova senha (mínimo 12 caracteres)"),
      { target: { value: "personal-test-password" } },
    );
    fireEvent.change(screen.getByLabelText("Confirme a nova senha"), {
      target: { value: "personal-test-password" },
    });
    fireEvent.click(screen.getByText("Ativar conta"));
    expect((await screen.findByRole("status")).textContent).toContain(
      "Conta ativada",
    );
    expect(api).toHaveBeenCalledWith("/auth/activation", "POST", {
      token: "fictitious",
      password: "personal-test-password",
      confirmation: "personal-test-password",
    });
    expect(api).not.toHaveBeenCalledWith(
      "/auth/login",
      expect.anything(),
      expect.anything(),
    );
  });
  it("troca a senha com a atual e solicita saída somente após sucesso", async () => {
    vi.mocked(api).mockResolvedValue(undefined as never);
    const changed = vi.fn();
    render(<PasswordChange onChanged={changed} />);
    fireEvent.change(screen.getByLabelText("Senha atual"), {
      target: { value: "current-test-password" },
    });
    fireEvent.change(
      screen.getByLabelText("Nova senha (mínimo 12 caracteres)"),
      { target: { value: "personal-test-password" } },
    );
    fireEvent.change(screen.getByLabelText("Confirme a nova senha"), {
      target: { value: "personal-test-password" },
    });
    fireEvent.click(screen.getByText("Alterar senha e sair"));
    await screen.findByRole("status");
    expect(changed).toHaveBeenCalledOnce();
  });
});

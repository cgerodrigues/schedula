// @vitest-environment jsdom
import React from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  render,
  screen,
  fireEvent,
  cleanup,
  act,
  waitFor,
} from "@testing-library/react";
import {
  Activation,
  passwordFieldsMatch,
  PasswordChange,
  passwordRequirements,
} from "./AccountAccess";
import { api, ApiError } from "./api";
vi.mock("./api", async (original) => ({ ...(await original<typeof import("./api")>()), api: vi.fn() }));
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
      new ApiError("Convite expirado. Solicite outro ao RH.",409),
    );
    render(<Activation token="fictitious" />);
    await screen.findByRole("heading", {name:"Convite indisponível"});
    expect(screen.queryByRole("alert")).toBeNull();
    expect(
      screen.queryByLabelText("Nova senha"),
    ).toBeNull();
  });
  it("ativa por POST e orienta login sem autenticação automática", async () => {
    vi.mocked(api).mockImplementation((async (path: string) =>
      path.endsWith("/check")
        ? { name: "Laís de teste", username: "laisteste" }
        : undefined) as typeof api);
    render(<Activation token="fictitious" />);
    fireEvent.change(
      await screen.findByLabelText("Nova senha"),
      { target: { value: "personal-test-password" } },
    );
    fireEvent.change(screen.getByLabelText("Confirmar senha"), {
      target: { value: "personal-test-password" },
    });
    fireEvent.click(screen.getByText("Ativar conta"));
    await screen.findByRole("heading", {name:"Conta ativada com sucesso!"});
    expect(screen.queryByText("Crie uma senha para acessar o Schedula.")).toBeNull();
    expect(screen.getByRole("button", {name:"Ir para o login"}).className).not.toContain("ghost");
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
      screen.getByLabelText("Nova senha"),
      { target: { value: "personal-test-password" } },
    );
    fireEvent.change(screen.getByLabelText("Confirmar senha"), {
      target: { value: "personal-test-password" },
    });
    fireEvent.click(screen.getByText("Alterar senha e sair"));
    await screen.findByRole("status");
    expect(changed).toHaveBeenCalledOnce();
  });
});

it("shows validated identity read-only and login only after server confirmation", async () => {
  let finish!: (value: unknown) => void;
  vi.mocked(api).mockImplementation((async (path: string) =>
    path.endsWith("/check")
      ? { name: "Nome validado", username: "usuariovalidado" }
      : new Promise<unknown>((resolve) => {
          finish = resolve;
        })) as typeof api);
  render(<Activation token="fictitious" />);
  await screen.findByText("Nome validado");
  expect(screen.getByText("usuariovalidado").tagName).toBe("DD");
  expect(screen.queryByText("Ir para o login")).toBeNull();
  const password = screen.getByLabelText(
    "Nova senha",
  ) as HTMLInputElement;
  const confirmation = screen.getByLabelText(
    "Confirmar senha",
  ) as HTMLInputElement;
  expect(password.type).toBe("password");
  fireEvent.click(screen.getByRole("button", {name:"Mostrar senha"}));
  expect(password.type).toBe("text");
  expect(confirmation.type).toBe("password");
  fireEvent.click(screen.getByRole("button", {name:"Mostrar senha repetida"}));
  expect(confirmation.type).toBe("text");
  fireEvent.change(password, { target: { value: "personal-test-password" } });
  fireEvent.change(confirmation, {
    target: { value: "personal-test-password" },
  });
  const submit = screen.getByText("Ativar conta");
  fireEvent.click(submit);
  fireEvent.click(submit);
  expect(
    vi.mocked(api).mock.calls.filter((c) => c[0] === "/auth/activation"),
  ).toHaveLength(1);
  expect(screen.queryByText("Ir para o login")).toBeNull();
  await act(async () => finish(undefined));
  await screen.findByText("Ir para o login");
  expect(
    screen.queryByLabelText("Nova senha"),
  ).toBeNull();
});
it("uses Unicode code points and UTF-8 bytes, blocking invalid activation locally", async () => {
  expect(passwordRequirements("😀".repeat(6)).minimum).toBe(false);
  expect(passwordRequirements("😀".repeat(18))).toEqual({
    minimum: true,
    bytes: true,
  });
  expect(passwordRequirements("é".repeat(37)).bytes).toBe(false);
  vi.mocked(api).mockResolvedValue({
    name: "Teste",
    username: "teste",
  } as never);
  render(<Activation token="fictitious" />);
  const password = await screen.findByLabelText(
    "Nova senha",
  );
  const confirmation = screen.getByLabelText("Confirmar senha");
  for (const value of ["curta", "é".repeat(37), "😀".repeat(6)]) {
    fireEvent.change(password, { target: { value } });
    fireEvent.change(confirmation, { target: { value } });
    expect(
      (screen.getByText("Ativar conta") as HTMLButtonElement).disabled,
    ).toBe(true);
  }
  fireEvent.change(password, { target: { value: "personal-test-password" } });
  fireEvent.change(confirmation, {
    target: { value: "different-test-password" },
  });
  expect((screen.getByText("Ativar conta") as HTMLButtonElement).disabled).toBe(
    true,
  );
  expect(api).toHaveBeenCalledTimes(1);
});
it("preserves password fields and activation context on submission failure", async () => {
  vi.mocked(api)
    .mockResolvedValueOnce({
      name: "Nome validado",
      username: "teste",
    } as never)
    .mockRejectedValueOnce(
      new TypeError("Network failed"),
    );
  render(<Activation token="fictitious" />);
  fireEvent.change(
    await screen.findByLabelText("Nova senha"),
    { target: { value: "personal-test-password" } },
  );
  fireEvent.change(screen.getByLabelText("Confirmar senha"), {
    target: { value: "personal-test-password" },
  });
  fireEvent.click(screen.getByText("Ativar conta"));
  await screen.findByRole("alert");
  expect(
    (
      screen.getByLabelText(
        "Nova senha",
      ) as HTMLInputElement
    ).value,
  ).toBe("personal-test-password");
  expect(screen.queryByText("Ir para o login")).toBeNull();
  expect(screen.getByText("Nome validado")).toBeTruthy();
});
it("does not expose stale identity when checking a different token", async () => {
  let finish!: (value: unknown) => void;
  vi.mocked(api)
    .mockImplementationOnce(
      () =>
        new Promise<unknown>((resolve) => {
          finish = resolve;
        }) as never,
    )
    .mockRejectedValueOnce(new Error("Convite inválido."));
  const { rerender } = render(<Activation token="old" />);
  rerender(<Activation token="new" />);
  await screen.findByRole("alert");
  await act(async () => finish({ name: "Identidade antiga", username: "old" }));
  expect(screen.queryByText("Identidade antiga")).toBeNull();
  expect(screen.queryByText("Ir para o login")).toBeNull();
});

it.each(["revogado", "expirado", "utilizado", "inválido"])("renders unavailable %s invitation without identity, form or dismissible notice", async reason => {
  vi.mocked(api).mockRejectedValue(new ApiError(reason,409));
  render(<Activation token="test"/>);
  await screen.findByRole("heading", {name:"Convite indisponível"});
  expect(screen.queryByRole("alert")).toBeNull();
  expect(screen.queryByText("Fechar aviso")).toBeNull();
  expect(document.querySelector("input, dl")).toBeNull();
  expect(screen.queryByText("Ir para o login")).toBeNull();
});
it("retries transient failure and preserves byte validation with a simple message", async () => {
  vi.mocked(api).mockRejectedValueOnce(new TypeError("network")).mockResolvedValueOnce({name:"Teste",username:"teste"} as never);
  render(<Activation token="test"/>);
  fireEvent.click(await screen.findByText("Tentar novamente"));
  const input = await screen.findByLabelText("Nova senha");
  expect(screen.queryByText(/UTF-8/)).toBeNull();
  fireEvent.change(input,{target:{value:"é".repeat(37)}});
  expect(screen.getByText("Senha muito longa. Escolha uma senha menor.")).toBeTruthy();
  fireEvent.change(input,{target:{value:"é".repeat(36)}});
  expect(screen.queryByText("Senha muito longa. Escolha uma senha menor.")).toBeNull();
});
it("clears identity and password when the invitation becomes unavailable during submission", async () => {
  vi.mocked(api).mockResolvedValueOnce({name:"Teste",username:"teste"} as never)
    .mockRejectedValue(new ApiError("revogado",409));
  render(<Activation token="test"/>);
  fireEvent.change(await screen.findByLabelText("Nova senha"),{target:{value:"personal-test-password"}});
  fireEvent.change(screen.getByLabelText("Confirmar senha"),{target:{value:"personal-test-password"}});
  fireEvent.click(screen.getByText("Ativar conta"));
  await screen.findByRole("heading",{name:"Convite indisponível"});
  expect(document.querySelector("input, dl")).toBeNull();
  expect(screen.queryByText("Ir para o login")).toBeNull();
});

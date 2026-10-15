// @vitest-environment jsdom
import React from "react";
import { describe, it, expect, vi } from "vitest";
import {
  render,
  screen,
  fireEvent,
  waitFor,
  cleanup,
} from "@testing-library/react";
import { afterEach } from "vitest";
import { Form, Field } from "./components";
afterEach(cleanup);
describe("Formulários integrados", () => {
  it("mantém os dados e informa falha do backend", async () => {
    render(
      <Form
        onSubmit={async () => {
          throw new Error("Vaga já reservada");
        }}
      >
        <Field label="Nome">
          <input name="name" defaultValue="Maria" />
        </Field>
      </Form>,
    );
    fireEvent.click(screen.getByText("Salvar"));
    expect(await screen.findByRole("alert")).toHaveProperty(
      "textContent",
      "Vaga já reservada",
    );
    expect((screen.getByLabelText("Nome") as HTMLInputElement).value).toBe(
      "Maria",
    );
  });
  it("bloqueia envio duplicado enquanto aguarda resposta", async () => {
    let done!: () => void;
    const save = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          done = resolve;
        }),
    );
    render(
      <Form onSubmit={save}>
        <input name="name" />
      </Form>,
    );
    fireEvent.click(screen.getByText("Salvar"));
    expect((screen.getByRole("button") as HTMLButtonElement).disabled).toBe(
      true,
    );
    expect(save).toHaveBeenCalledTimes(1);
    done();
    await waitFor(() =>
      expect(screen.getByRole("status").textContent).toBe("Salvo com sucesso."),
    );
  });
});

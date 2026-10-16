// @vitest-environment jsdom
import React, { useState } from "react";
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
} from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { SearchSelect } from "./SearchSelect";
import { Notification } from "./Notification";
import { displayPhone } from "./unitFields";
afterEach(() => {
  cleanup();
  vi.useRealTimers();
});
function Select() {
  const [value, setValue] = useState("1");
  return (
    <SearchSelect
      label="Município"
      value={value}
      onChange={setValue}
      options={[
        { id: "1", name: "Fortaleza" },
        { id: "2", name: "São Paulo" },
      ]}
    />
  );
}
it("searches without accents, navigates by keyboard and restores the selected label", () => {
  render(<Select />);
  const input = screen.getByRole("combobox");
  fireEvent.keyDown(input, { key: "ArrowDown" });
  fireEvent.keyDown(input, { key: "ArrowDown" });
  fireEvent.keyDown(input, { key: "Enter" });
  expect((input as HTMLInputElement).value).toBe("São Paulo");
  fireEvent.change(input, { target: { value: "sao" } });
  expect(screen.getAllByRole("option")).toHaveLength(1);
  fireEvent.keyDown(input, { key: "Escape" });
  expect(screen.queryByRole("listbox")).toBeNull();
  expect((input as HTMLInputElement).value).toBe("São Paulo");
  fireEvent.click(input);
  fireEvent.pointerDown(document.body);
  expect(screen.queryByRole("listbox")).toBeNull();
});
it("has loading, unavailable and no-results states", () => {
  const { rerender } = render(
    <SearchSelect
      label="Município"
      value=""
      options={[]}
      onChange={vi.fn()}
      busy
    />,
  );
  fireEvent.click(screen.getByRole("combobox"));
  expect(screen.getByText("Carregando municípios…")).toBeTruthy();
  rerender(
    <SearchSelect
      label="Município"
      value=""
      options={[]}
      onChange={vi.fn()}
      error="Catálogo indisponível"
    />,
  );
  expect(screen.getByRole("status").textContent).toBe("Catálogo indisponível");
  rerender(
    <SearchSelect label="Município" value="" options={[]} onChange={vi.fn()} />,
  );
  expect(screen.getByText("Nenhum resultado encontrado.")).toBeTruthy();
});
it("automatically dismisses success but leaves errors until closed", () => {
  vi.useFakeTimers();
  const dismiss = vi.fn();
  const { rerender } = render(
    <Notification message="Salvo" kind="success" onDismiss={dismiss} />,
  );
  act(() => vi.advanceTimersByTime(6000));
  expect(dismiss).toHaveBeenCalledTimes(1);
  rerender(<Notification message="Falha" onDismiss={dismiss} />);
  act(() => vi.advanceTimersByTime(10000));
  expect(dismiss).toHaveBeenCalledTimes(1);
  fireEvent.click(screen.getByLabelText("Fechar aviso"));
  expect(dismiss).toHaveBeenCalledTimes(2);
});
it("formats normalized and masked phones without damaging unusual historical values", () => {
  expect(displayPhone("8533334444")).toBe("(85) 3333-4444");
  expect(displayPhone("(85) 99999-9999")).toBe("(85) 99999-9999");
  expect(displayPhone("8533334444 ramal 2")).toBe("8533334444 ramal 2");
  expect(displayPhone("123")).toBe("123");
});

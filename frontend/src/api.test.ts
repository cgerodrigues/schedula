import { describe, it, expect } from "vitest";
import { inView, localDay } from "./api";
describe("Agenda no fuso institucional", () => {
  it("mantém a data de Fortaleza mesmo após meia-noite UTC", () => {
    expect(localDay("2030-01-08T01:00:00Z")).toBe("2030-01-07");
  });
  it("filtra semanas de segunda a domingo inclusive", () => {
    expect(inView("2030-01-07T09:00:00-03:00", "2030-01-10", "week")).toBe(
      true,
    );
    expect(inView("2030-01-13T23:00:00-03:00", "2030-01-10", "week")).toBe(
      true,
    );
    expect(inView("2030-01-14T09:00:00-03:00", "2030-01-10", "week")).toBe(
      false,
    );
  });
  it("separa dia e histórico completo", () => {
    expect(inView("2030-01-08T09:00:00-03:00", "2030-01-07", "day")).toBe(
      false,
    );
    expect(inView("2030-01-08T09:00:00-03:00", "2030-01-07", "all")).toBe(true);
  });
});

import { describe, it, expect } from "vitest";
import { dashboardMetrics } from "./dashboard";
const now = new Date("2030-01-08T01:00:00Z");
const row = (status: string, starts_at = "2030-01-07T14:00:00-03:00") => ({
  status,
  starts_at,
});
describe("Indicadores do defensor", () => {
  it("inclui realizados e ausências sem cancelados ou remarcados", () => {
    expect(
      dashboardMetrics(
        [
          row("CONFIRMADO"),
          row("REALIZADO"),
          row("NAO_COMPARECEU"),
          row("CANCELADO_CIDADAO"),
          row("CANCELADO_DEFENSOR"),
          row("REMARCADO"),
        ],
        now,
      ),
    ).toEqual({
      expected: 3,
      confirmed: 1,
      completed: 1,
      absent: 1,
      cancelled: 2,
    });
  });
  it("concluir não reduz os previstos", () => {
    expect(dashboardMetrics([row("CONFIRMADO")], now).expected).toBe(
      dashboardMetrics([row("REALIZADO")], now).expected,
    );
  });
  it("conta somente o novo atendimento de uma remarcação no mesmo dia", () => {
    expect(
      dashboardMetrics([row("REMARCADO"), row("CONFIRMADO")], now).expected,
    ).toBe(1);
  });
  it("remarcação para outro dia não entra em hoje", () => {
    expect(
      dashboardMetrics(
        [row("REMARCADO"), row("CONFIRMADO", "2030-01-08T10:00:00-03:00")],
        now,
      ).expected,
    ).toBe(0);
  });
  it("respeita Fortaleza na virada UTC", () => {
    expect(
      dashboardMetrics(
        [
          row("REALIZADO", "2030-01-08T01:30:00Z"),
          row("CONFIRMADO", "2030-01-08T03:00:00Z"),
          row("CONFIRMADO", "2030-01-07T02:59:00Z"),
        ],
        now,
      ),
    ).toEqual({
      expected: 1,
      confirmed: 0,
      completed: 1,
      absent: 0,
      cancelled: 0,
    });
  });
  it("retorna zeros sem registros", () => {
    expect(dashboardMetrics([], now)).toEqual({
      expected: 0,
      confirmed: 0,
      completed: 0,
      absent: 0,
      cancelled: 0,
    });
  });
});

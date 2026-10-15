import { localDay } from "./api";
export type AppointmentMetric = { starts_at: string; status: string };
/** All cards use the same institutional day. Rescheduled originals never count twice. */
export function dashboardMetrics(
  appointments: AppointmentMetric[],
  now = new Date(),
) {
  const day = localDay(now.toISOString());
  const today = appointments.filter((a) => localDay(a.starts_at) === day);
  const count = (status: string) =>
    today.filter((a) => a.status === status).length;
  const confirmed = count("CONFIRMADO");
  const completed = count("REALIZADO");
  const absent = count("NAO_COMPARECEU");
  return {
    expected: confirmed + completed + absent,
    confirmed,
    completed,
    absent,
    cancelled: count("CANCELADO_CIDADAO") + count("CANCELADO_DEFENSOR"),
  };
}

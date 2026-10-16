export function phoneMask(value: string) {
  const d = value.replace(/\D/g, "").slice(0, 11);
  if (!d) return "";
  if (d.length <= 2) return "(" + d;
  const end = d.length > 10 ? 7 : 6;
  return `(${d.slice(0, 2)}) ${d.slice(2, end)}${d.length > end ? "-" + d.slice(end) : ""}`;
}
export function cepMask(value: string) {
  const d = value.replace(/\D/g, "").slice(0, 8);
  return d.length > 5 ? d.slice(0, 5) + "-" + d.slice(5) : d;
}
export function areasOf(value: unknown): string[] {
  return typeof value === "string"
    ? value.split(",").filter(Boolean)
    : Array.isArray(value)
      ? value
      : [];
}
export function displayPhone(value: string = "") {
  return /^[\d\s()+.-]+$/.test(value) &&
    [10, 11].includes(value.replace(/\D/g, "").length)
    ? phoneMask(value)
    : value;
}

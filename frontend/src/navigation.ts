import { useEffect, useState } from "react";
export const unitsPath = "/rh/unidades";
export function navigate(
  path: string,
  state: Record<string, unknown> = {},
  replace = false,
) {
  window.history[replace ? "replaceState" : "pushState"](state, "", path);
  window.dispatchEvent(new Event("schedula:navigate"));
}
export function useLocation() {
  const read = () => ({
    path: window.location.pathname,
    search: window.location.search,
    state: window.history.state || {},
  });
  const [location, setLocation] = useState(read);
  useEffect(() => {
    const update = () => setLocation(read());
    window.addEventListener("popstate", update);
    window.addEventListener("schedula:navigate", update);
    return () => {
      window.removeEventListener("popstate", update);
      window.removeEventListener("schedula:navigate", update);
    };
  }, []);
  return location;
}

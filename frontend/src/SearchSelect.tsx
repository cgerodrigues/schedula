import { useEffect, useId, useRef, useState } from "react";
import { createPortal } from "react-dom";
const normalize = (s: string) =>
  s
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLocaleLowerCase("pt-BR");
export function SearchSelect({
  label,
  value,
  options,
  onChange,
  busy = false,
  disabled = false,
  error = "",
}: {
  label: string;
  value: string;
  options: { id: string; name: string }[];
  onChange: (value: string) => void;
  busy?: boolean;
  disabled?: boolean;
  error?: string;
}) {
  const id = useId(),
    input = useRef<HTMLInputElement>(null),
    menu = useRef<HTMLDivElement>(null);
  const [open, setOpen] = useState(false),
    [query, setQuery] = useState(""),
    [index, setIndex] = useState(0);
  const [position, setPosition] = useState({
    left: 0,
    top: 0,
    width: 0,
    maxHeight: 260,
  });
  const selected = options.find((o) => o.id === value)?.name || "";
  const choices = options.filter((o) =>
    normalize(o.name).includes(normalize(query)),
  );
  function close() {
    setOpen(false);
    setQuery("");
    setIndex(0);
  }
  function select(id: string) {
    onChange(id);
    close();
    input.current?.focus();
  }
  useEffect(() => {
    setQuery("");
    setIndex(0);
  }, [value]);
  useEffect(() => {
    if (!open) return;
    const positionMenu = () => {
      const r = input.current!.getBoundingClientRect();
      if (r.bottom < 0 || r.top > window.innerHeight) {
        close();
        return;
      }
      const below = window.innerHeight - r.bottom - 12;
      const height = Math.min(260, Math.max(below, r.top - 12));
      setPosition({
        left: Math.max(8, Math.min(r.left, window.innerWidth - r.width - 8)),
        top: below >= height ? r.bottom + 4 : Math.max(8, r.top - height - 4),
        width: Math.min(r.width, window.innerWidth - 16),
        maxHeight: height,
      });
    };
    const outside = (e: PointerEvent) => {
      if (
        !menu.current?.contains(e.target as Node) &&
        e.target !== input.current
      )
        close();
    };
    positionMenu();
    document.addEventListener("pointerdown", outside);
    window.addEventListener("resize", positionMenu);
    window.addEventListener("scroll", positionMenu, true);
    return () => {
      document.removeEventListener("pointerdown", outside);
      window.removeEventListener("resize", positionMenu);
      window.removeEventListener("scroll", positionMenu, true);
    };
  }, [open]);
  useEffect(() => {
    menu.current
      ?.querySelector(`[data-index="${index}"]`)
      ?.scrollIntoView?.({ block: "nearest" });
  }, [index]);
  return (
    <div className="field search-select">
      <label htmlFor={id}>{label}</label>
      <input
        ref={input}
        id={id}
        role="combobox"
        autoComplete="off"
        aria-autocomplete="list"
        aria-expanded={open}
        aria-controls={open ? id + "-list" : undefined}
        aria-activedescendant={
          open && choices[index] ? id + "-" + index : undefined
        }
        aria-describedby={error ? id + "-error" : undefined}
        aria-busy={busy}
        disabled={disabled}
        value={open ? query : selected}
        placeholder={busy ? "Carregando municípios…" : "Selecione ou pesquise"}
        onClick={() => {
          setOpen(true);
          setQuery("");
        }}
        onBlur={close}
        onChange={(e) => {
          setQuery(e.target.value);
          setOpen(true);
          setIndex(0);
        }}
        onKeyDown={(e) => {
          if (e.key === "Escape") {
            e.preventDefault();
            close();
          }
          if (e.key === "ArrowDown" || e.key === "ArrowUp") {
            e.preventDefault();
            setOpen(true);
            setIndex((i) =>
              !open
                ? 0
                : Math.max(
                    0,
                    Math.min(
                      choices.length - 1,
                      i + (e.key === "ArrowDown" ? 1 : -1),
                    ),
                  ),
            );
          }
          if (e.key === "Enter" && open) {
            e.preventDefault();
            if (choices[index]) select(choices[index].id);
          }
        }}
      />
      {error && (
        <span id={id + "-error"} className="hint">
          {error}
        </span>
      )}
      {open &&
        createPortal(
          <div
            ref={menu}
            id={id + "-list"}
            className="search-options"
            role="listbox"
            aria-label={label}
            style={position}
            onMouseDown={(e) => e.preventDefault()}
          >
            {busy && <div role="status">Carregando municípios…</div>}
            {!busy && !choices.length && (
              <div role="status">{error || "Nenhum resultado encontrado."}</div>
            )}
            {choices.map((o, i) => (
              <div
                role="option"
                key={o.id}
                id={id + "-" + i}
                data-index={i}
                aria-selected={o.id === value}
                className={i === index ? "highlighted" : ""}
                onMouseEnter={() => setIndex(i)}
                onClick={() => select(o.id)}
              >
                {o.name}
              </div>
            ))}
          </div>,
          document.body,
        )}
    </div>
  );
}

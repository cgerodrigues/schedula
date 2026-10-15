package io.github.cgerodrigues.schedula.institution;

import static io.github.cgerodrigues.schedula.shared.DomainException.require;

import java.text.Normalizer;
import java.util.*;

public final class Brazil {
  private Brazil() {}

  public record State(String code, String name) {}

  // Stable official UF identifiers; available even when external services are down.
  public static final List<State> STATES =
      List.of(
          new State("AC", "Acre"),
          new State("AL", "Alagoas"),
          new State("AP", "Amapá"),
          new State("AM", "Amazonas"),
          new State("BA", "Bahia"),
          new State("CE", "Ceará"),
          new State("DF", "Distrito Federal"),
          new State("ES", "Espírito Santo"),
          new State("GO", "Goiás"),
          new State("MA", "Maranhão"),
          new State("MT", "Mato Grosso"),
          new State("MS", "Mato Grosso do Sul"),
          new State("MG", "Minas Gerais"),
          new State("PA", "Pará"),
          new State("PB", "Paraíba"),
          new State("PR", "Paraná"),
          new State("PE", "Pernambuco"),
          new State("PI", "Piauí"),
          new State("RJ", "Rio de Janeiro"),
          new State("RN", "Rio Grande do Norte"),
          new State("RS", "Rio Grande do Sul"),
          new State("RO", "Rondônia"),
          new State("RR", "Roraima"),
          new State("SC", "Santa Catarina"),
          new State("SP", "São Paulo"),
          new State("SE", "Sergipe"),
          new State("TO", "Tocantins"));
  private static final Set<String> DDDS =
      Set.of(
          "11", "12", "13", "14", "15", "16", "17", "18", "19", "21", "22", "24", "27", "28", "31",
          "32", "33", "34", "35", "37", "38", "41", "42", "43", "44", "45", "46", "47", "48", "49",
          "51", "53", "54", "55", "61", "62", "63", "64", "65", "66", "67", "68", "69", "71", "73",
          "74", "75", "77", "79", "81", "82", "83", "84", "85", "86", "87", "88", "89", "91", "92",
          "93", "94", "95", "96", "97", "98", "99");

  public static String state(String uf) {
    require(STATES.stream().anyMatch(s -> s.code().equals(uf)), "Selecione uma UF válida");
    return uf;
  }

  public static String clean(String s) {
    return s == null ? "" : s.strip().replaceAll("\\s+", " ");
  }

  public static String key(String s) {
    return Normalizer.normalize(clean(s), Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "")
        .toLowerCase(Locale.ROOT);
  }

  public static String phone(String value) {
    require(value != null && value.matches("[0-9() .-]+"), "Telefone contém caracteres inválidos");
    String digits = value.replaceAll("\\D", "");
    require(
        (digits.length() == 10 || digits.length() == 11) && DDDS.contains(digits.substring(0, 2)),
        "Informe telefone com DDD válido e 10 ou 11 dígitos");
    require(
        digits.length() == 10
            ? digits.substring(2).matches("[2-5][0-9]{7}")
            : digits.substring(2).matches("9[0-9]{8}"),
        "Informe um telefone fixo ou celular válido");
    return digits;
  }
}

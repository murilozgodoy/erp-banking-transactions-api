package com.erpbanking.transactions.portal.seed;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Perfil do negócio extraído do evento {@code customer.onboarding_submitted}. Todos os campos de
 * {@code respostas} são opcionais: ausentes, vazios ou inválidos caem num padrão razoável por segmento.
 * Números podem chegar como {@link Number} ou {@link String} ("1.234,56", "R$ 50.000", "45%").
 */
public record SeedProfile(
        long customerId,
        String segmento,
        BigDecimal faturamentoMensal,
        double pctCartaoCredito,
        double parcelamentoMedio,
        double margemBrutaPct,
        BigDecimal folhaMensal,
        BigDecimal parcelaDividasMensal,
        boolean possuiEstoque,
        BigDecimal valorEstoque,
        BigDecimal ticketMedio,
        String sazonalidade,
        List<String> canais) {

    public static final Set<String> SEGMENTOS = Set.of(
            "varejo", "alimentacao", "servicos", "saude", "tecnologia", "industria", "agro", "construcao", "outro");

    static final BigDecimal DEFAULT_FATURAMENTO = new BigDecimal("100000.00");
    static final double DEFAULT_PCT_CARTAO = 50;

    private static final Set<String> SEGMENTOS_COM_ESTOQUE =
            Set.of("varejo", "alimentacao", "industria", "agro", "construcao");

    /** Monta o perfil a partir do payload (mapa snake_case). Nunca lança exceção. */
    @SuppressWarnings("unchecked")
    public static SeedProfile from(long customerId, Map<String, Object> event) {
        Map<String, Object> respostas = event != null && event.get("respostas") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        String segmento = segmento(event == null ? null : event.get("segmento"));

        BigDecimal faturamento = positive(decimal(respostas.get("faturamento_mensal")), DEFAULT_FATURAMENTO);
        double pctCartao = clamp(number(respostas.get("pct_cartao_credito"), defaultPctCartao(segmento)), 0, 100);
        double parcelamento = clamp(number(respostas.get("parcelamento_medio"), defaultParcelamento(segmento)), 1, 12);
        double margem = clamp(number(respostas.get("margem_bruta_pct"), defaultMargem(segmento)), 5, 90);
        BigDecimal folha = nonNegative(decimal(respostas.get("folha_mensal")),
                scale(faturamento.multiply(new BigDecimal("0.18"))));
        BigDecimal parcelaDividas = nonNegative(decimal(respostas.get("parcela_dividas_mensal")), BigDecimal.ZERO);
        Boolean estoqueFlag = bool(respostas.get("possui_estoque"));
        boolean possuiEstoque = estoqueFlag != null ? estoqueFlag : SEGMENTOS_COM_ESTOQUE.contains(segmento);
        BigDecimal valorEstoque = decimal(respostas.get("valor_estoque"));
        if (valorEstoque != null && valorEstoque.signum() <= 0) {
            valorEstoque = null;
        }
        BigDecimal ticket = decimal(respostas.get("ticket_medio"));
        if (ticket != null && ticket.signum() <= 0) {
            ticket = null;
        }
        return new SeedProfile(customerId, segmento, scale(faturamento), pctCartao, parcelamento, margem,
                scale(folha), scale(parcelaDividas), possuiEstoque, valorEstoque == null ? null : scale(valorEstoque),
                ticket == null ? null : scale(ticket), sazonalidade(respostas.get("sazonalidade")),
                strings(respostas.get("canais")));
    }

    static String segmento(Object raw) {
        if (raw instanceof String s) {
            String norm = s.trim().toLowerCase(Locale.ROOT);
            if (SEGMENTOS.contains(norm)) {
                return norm;
            }
        }
        return "outro";
    }

    /** Segmentos B2B (sem a pergunta no template) vendem mais por boleto/PIX. */
    static double defaultPctCartao(String segmento) {
        return switch (segmento) {
            case "tecnologia" -> 20;
            case "industria" -> 10;
            case "agro" -> 5;
            case "construcao" -> 25;
            default -> DEFAULT_PCT_CARTAO;
        };
    }

    /** "Baixa" | "Média" | "Alta" (com ou sem acento, qualquer caixa) → baixa | media | alta; padrão media. */
    static String sazonalidade(Object raw) {
        if (raw instanceof String s) {
            String norm = s.trim().toLowerCase(Locale.ROOT).replace('é', 'e');
            if (Set.of("baixa", "media", "alta").contains(norm)) {
                return norm;
            }
        }
        return "media";
    }

    static double defaultParcelamento(String segmento) {
        return switch (segmento) {
            case "varejo", "construcao" -> 3;
            case "alimentacao" -> 1;
            case "saude", "tecnologia" -> 2.5;
            default -> 2;
        };
    }

    static double defaultMargem(String segmento) {
        return switch (segmento) {
            case "tecnologia" -> 70;
            case "servicos", "saude" -> 55;
            case "alimentacao" -> 60;
            case "industria", "agro", "construcao" -> 30;
            default -> 40;
        };
    }

    /** Converte Number ou String (formato brasileiro ou ponto decimal) em BigDecimal; inválido = null. */
    static BigDecimal decimal(Object raw) {
        if (raw instanceof BigDecimal b) {
            return b;
        }
        if (raw instanceof Number n) {
            double d = n.doubleValue();
            return Double.isFinite(d) ? new BigDecimal(n.toString()) : null;
        }
        if (raw instanceof String s) {
            String clean = s.replace("R$", "").replace("%", "").replace(" ", "").replace(" ", "").trim();
            if (clean.isEmpty()) {
                return null;
            }
            if (clean.contains(",")) {
                clean = clean.replace(".", "").replace(',', '.');
            } else if (clean.chars().filter(c -> c == '.').count() > 1 || clean.matches("-?\\d{1,3}\\.\\d{3}")) {
                // Sem vírgula: "1.234.567" ou "3.000" são separadores de milhar (formato brasileiro).
                clean = clean.replace(".", "");
            }
            try {
                return new BigDecimal(clean);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    static double number(Object raw, double fallback) {
        BigDecimal d = decimal(raw);
        return d == null ? fallback : d.doubleValue();
    }

    static Boolean bool(Object raw) {
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof Number n) {
            return n.intValue() != 0;
        }
        if (raw instanceof String s) {
            String norm = s.trim().toLowerCase(Locale.ROOT);
            if (Set.of("true", "sim", "s", "yes", "1").contains(norm)) {
                return true;
            }
            if (Set.of("false", "nao", "não", "n", "no", "0").contains(norm)) {
                return false;
            }
        }
        return null;
    }

    static List<String> strings(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s && !s.isBlank()) {
                    out.add(s.trim());
                }
            }
        }
        return List.copyOf(out);
    }

    private static BigDecimal positive(BigDecimal value, BigDecimal fallback) {
        return value == null || value.signum() <= 0 ? fallback : value;
    }

    private static BigDecimal nonNegative(BigDecimal value, BigDecimal fallback) {
        return value == null || value.signum() < 0 ? fallback : value;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}

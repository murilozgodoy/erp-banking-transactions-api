package com.erpbanking.transactions.portal;

/**
 * Membro sem a permissão exigida pela rota: 403 {@code {"error":"SEM_PERMISSAO","permissao":"pagar"}} (e
 * {@code "motivo"} quando há um, ex.: {@code AUTOR} ao tentar aprovar o próprio pagamento).
 */
public class PermissionDeniedException extends RuntimeException {

    public static final String CODE = "SEM_PERMISSAO";

    private final String permissao;
    private final String motivo;

    public PermissionDeniedException(String permissao) {
        this(permissao, null);
    }

    public PermissionDeniedException(String permissao, String motivo) {
        super("Sem a permissão " + permissao + (motivo == null ? "" : " (" + motivo + ")"));
        this.permissao = permissao;
        this.motivo = motivo;
    }

    public String getPermissao() {
        return permissao;
    }

    public String getMotivo() {
        return motivo;
    }

    /** Corpo do 403, igual no GlobalExceptionHandler e no PortalExceptionHandler. */
    public java.util.Map<String, Object> body() {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("error", CODE);
        body.put("permissao", permissao);
        if (motivo != null) {
            body.put("motivo", motivo);
        }
        return body;
    }
}

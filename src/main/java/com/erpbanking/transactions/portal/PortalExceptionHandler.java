package com.erpbanking.transactions.portal;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.erpbanking.transactions.controller.MeCreditController;
import com.erpbanking.transactions.controller.MeInventoryController;
import com.erpbanking.transactions.controller.MePaymentsController;
import com.erpbanking.transactions.portal.PortalExceptions.BusinessRuleException;
import com.erpbanking.transactions.portal.PortalExceptions.EstoqueInsuficienteException;
import com.erpbanking.transactions.portal.PortalExceptions.FieldValidationException;
import com.erpbanking.transactions.portal.PortalExceptions.NotFoundException;
import com.erpbanking.transactions.portal.PortalExceptions.SaldoInsuficienteException;
import com.erpbanking.transactions.portal.PortalExceptions.ValidationException;
import com.fasterxml.jackson.databind.JsonMappingException;

/**
 * Erros das rotas de escrita do portal (pagamentos, crédito e movimentação de estoque). Fica separado do
 * GlobalExceptionHandler e tem precedência sobre ele só nos controllers listados.
 */
@RestControllerAdvice(assignableTypes = {MePaymentsController.class, MeCreditController.class,
        MeInventoryController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PortalExceptionHandler {

    @ExceptionHandler(SaldoInsuficienteException.class)
    public ResponseEntity<Map<String, Object>> saldoInsuficiente(SaldoInsuficienteException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.getCode());
        body.put("faltam", e.getFaltam());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    @ExceptionHandler(EstoqueInsuficienteException.class)
    public ResponseEntity<Map<String, Object>> estoqueInsuficiente(EstoqueInsuficienteException e) {
        Map<String, Object> body = body(e.getCode(), e.getMessage());
        body.put("disponivel", e.getDisponivel());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    @ExceptionHandler(FieldValidationException.class)
    public ResponseEntity<Map<String, Object>> fieldValidation(FieldValidationException e) {
        Map<String, Object> body = body(e.getCode(), "Corpo da requisição inválido");
        Map<String, Object> campos = new LinkedHashMap<>();
        campos.put(e.getCampo(), e.getMessage());
        body.put("campos", campos);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(PermissionDeniedException.class)
    public ResponseEntity<Map<String, Object>> permissionDenied(PermissionDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.body());
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<Map<String, Object>> businessRule(BusinessRuleException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<Map<String, Object>> validation(ValidationException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalidBody(MethodArgumentNotValidException e) {
        Map<String, Object> campos = new LinkedHashMap<>();
        // Estoque (rota nova) devolve o nome do campo como no JSON (custo_unitario); as demais mantêm o de antes.
        boolean snake = e.getParameter().getContainingClass() == MeInventoryController.class;
        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            campos.putIfAbsent(snake ? snakeCase(fe.getField()) : fe.getField(), fe.getDefaultMessage());
        }
        Map<String, Object> body = body("validation_failed", "Corpo da requisição inválido");
        body.put("campos", campos);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        Map<String, Object> body = body("validation_failed", "JSON inválido ou valor fora das opções aceitas");
        if (e.getCause() instanceof JsonMappingException jme && !jme.getPath().isEmpty()
                && jme.getPath().get(0).getFieldName() != null) {
            Map<String, Object> campos = new LinkedHashMap<>();
            campos.put(jme.getPath().get(0).getFieldName(), "valor inválido");
            body.put("campos", campos);
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    static String snakeCase(String field) {
        return field.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
    }

    private static Map<String, Object> body(String error, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("message", message);
        return body;
    }
}

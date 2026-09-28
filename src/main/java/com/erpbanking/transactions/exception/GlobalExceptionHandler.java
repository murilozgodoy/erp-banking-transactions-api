package com.erpbanking.transactions.exception;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiExceptions.AccountNotFoundException.class)
    public ResponseEntity<Map<String, String>> accountNotFound(ApiExceptions.AccountNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ApiExceptions.CreditRequestNotFoundException.class)
    public ResponseEntity<Map<String, String>> creditNotFound(ApiExceptions.CreditRequestNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ApiExceptions.InsufficientFundsException.class)
    public ResponseEntity<Map<String, String>> insufficient(ApiExceptions.InsufficientFundsException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ApiExceptions.AccountBlockedException.class)
    public ResponseEntity<Map<String, String>> blocked(ApiExceptions.AccountBlockedException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ApiExceptions.ScoreUnavailableException.class)
    public ResponseEntity<Map<String, String>> scoreDown(ApiExceptions.ScoreUnavailableException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> validation(MethodArgumentNotValidException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "validation_failed"));
    }
}

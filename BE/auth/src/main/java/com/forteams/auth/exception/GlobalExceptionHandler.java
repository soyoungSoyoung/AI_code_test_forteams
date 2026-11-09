package com.forteams.auth.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
  @ExceptionHandler(NicknameExhaustedException.class)
  public ResponseEntity<Map<String, Object>> handleNicknameExhausted(NicknameExhaustedException e) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", HttpStatus.SERVICE_UNAVAILABLE.value());
    body.put("error", HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase());
    body.put("message", e.getMessage());
    body.put("timestamp", LocalDateTime.now().toString());
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
  }
}

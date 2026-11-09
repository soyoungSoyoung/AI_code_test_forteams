package com.forteams.auth.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 [503 매핑] 전역 예외 핸들러

 왜 이걸 만들었는가:
  - 닉네임 고갈 시 AuthServiceImpl이 던지는 예외를 HTTP 상태의 503으로 변환해 프론트에 알리기 위해 필요함.
  - 컨트롤러마다 try-catch를 쓰는 대신, @RestControllerAdvice로 한 곳에서 통일하면
    컨틀롤러는 비즈니스 로직에만 집중할 수 있음.

 어떻게 동작하는가:
  - @RestcontrollerAdvice: 모든 @RestController의 예외를 가로챔.
  - @ExceptionHandler(NicknameExhaustedException.class):
    해당 예외 타입만 잡아 503 + JSON 본문으로 반환.
  - 다른 예외(예: 일반 IllegalStateException)는 이 핸들러에 걸리지 않아
    Spring 기본 동작(500)으로 처리됨 -> 고갈/시스템 오류 구분 유지.

 응답 본문 구조:
  { "status": 503, "error": "Service Unavailable",
    "message": "...", "timestamp": "..."}
  - 프론트는 status==503 또는 message로 "닉네임 소진" UI를 표시하면 됨.
*/
@RestControllerAdvice
public class GlobalExceptionHandler {

  /**
   닉네임 풀 고갈 -> 503 Service Unavailable

   503을 쓰는 이유:
    - 닉네임 고갈은 "서버 장애"가 아니라 "처리할 리소스가 일시적으로 없음"임.
    - 503은 클라이언트가 '잠시 후 다시 시도해도 해결될 수 있다"는 의미를 가지므로 이 상황에 적합.
    - 500(내부 서버 오류)은 관리자가 봐야 할 버그를 시사하므로, 고갈에는 어울리지 않음.
  */
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

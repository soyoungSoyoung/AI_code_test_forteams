package com.forteams.auth.exception;

/**
 [503 매핑용] 닉네임 풀 고갈 예외

 왜 이걸 만들었는가:
  - AuthServiceImpl.updateAdditionalInfo()는 닉네임이 모두 소진됐을 때 예외를 던짐.
    그런데 IllegalStateException을 그대로 503으로 매핑하면 "닉네임 고갈"이 아니라도
    발생하는 다른 IllegalStateException(시스템 오류)까지 503으로 오표기될 위험.
  - 503(servic Unavailable)은 "서버가 일시적으로 요청을 처리할 수 없다"는 의미로,
    닉네임 고갈처럼 "리소스가 일시적으로 소진됨"인 경우에 어울림.
    반면 일반 시스템 오류는 500이 맞음.
  - 따라서 소갈 상황을 다른 예외와 구분하기 위해 전용 예외를 만들 필요.

 어떻게 쓰이는가:
  - AuthServiceImpl: 닉네임이 null이면 이 예외를 던짐.
  - GlobalExceptionHandler: 이 예외를 잡아 503 + JSON 메시지로 반환.
*/
public NicknameExhaustedException extends RuntimeException {
  public NicknameExhaustedException(String message) {
    suepr(message);
  }
}

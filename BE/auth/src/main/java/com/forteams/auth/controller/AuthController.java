package com.forteams.auth.controller;

import com.forteams.auth.entity.MsUserEntity;
import com.forteams.auth.service.*;
import com.forteams.auth.service.token.CustomTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@Slf4j
@RequiredArgsConstructor
@Getter
@Setter
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final CustomOidcUserServiceImpl customOidcUserServiceImpl;
    private final CustomTokenService tokenService;
    private final AuthService authService;

    @GetMapping("/welcome")
    public ResponseEntity<?> welcomePage(OAuth2AuthenticationToken authentication) {
        if (authentication != null) {
            // 사용자 이름 가져오기.Principal의 이름은 기본적으로 "sub" 클레임에 매핑됨.
            String username = authentication.getPrincipal().getName(); //OAuth2 인증 토큰에서 제공되는 주체 이름 (subject name). 일반적으로 sub (subject) 클레임에 대응, 각 OAuth2 제공자마다 다를 수 있음.

            // OIDC 사용자 정보 가져오기
            Map<String, Object> userAttributes = authentication.getPrincipal().getAttributes();
            String email = (String) userAttributes.get("email"); //내 이메일
            String name = (String) userAttributes.get("name"); //내 이름

            //email이 ms_users 에 있는지 확인 후, 없으면 회원가입 완료! -> jwt와 accessToken 발급 -> 저장 후, 추가 정보 입력 페이지 리디렉션
//            if (!msUserService.isExistEmail(email)) { //없는 유저 - 회원가입 진행하기
//                MsUserEntity curSignedUpUser = msUserService.findOrCreateUser(email, name);
//            }
//            //있으면 회원가입 할 거 없음! -> jwt와 accessToken 발급 ->바로 챗봇 페이지로 리디렉션
//            else {
//                MsUserEntity loginnedUser = msUserService.findOrCreateUser(email, name);
//                //To do: jwt 만들어서 들고 다니기 구현~~ provider 확인~
//                return new ResponseEntity<>(loginnedUser, HttpStatus.OK);
//            }

            MsUserEntity loginnedUser = authService.findOrCreateUser(email, name);
            return new ResponseEntity<>(loginnedUser, HttpStatus.OK);
        }
        return new ResponseEntity<>("Authentication token not found!", HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // 정보 저장 버튼 클릭 시, 추가 정보 받기
    // 쿠키(jwt-> msUuid), 입력값: dept, redis: 닉네임 set에서 하나 가져오기
    @PostMapping("/info")
    public ResponseEntity<String> submitAdditionalInfo(HttpServletRequest request, HttpServletResponse response,  @RequestParam(name = "dept") String dept) {
        // 1. request -> jwt -> subject값 == msUuid 가져오기
        String msUuid = tokenService.getMsUuidFromTmpACCESS(request);
        if (msUuid == null) return new ResponseEntity<>("JWT token not found!", HttpStatus.UNAUTHORIZED);

        // 2. Service로 msUuid와 dept 넘기기
        authService.updateAdditionalInfo(msUuid, dept, response);
        return ResponseEntity.ok("회원가입 완료 msUuid: " + msUuid + ", dept: " + dept);
    }

    // // 로그아웃 요청을 처리하는 메서드
    // @PostMapping("/logout")
    // public ResponseEntity<String> logout(HttpServletRequest request, HttpServletResponse response) {
    //     // 1. 쿠키에서 JWT 토큰 제거 + 2. 세션 무효화 서비스에서 처리
    //     authService.logout(request, response);
    //     // 3. 로그아웃 성공 메시지 반환
    //     return ResponseEntity.ok("로그아웃 완료!!!");
    // }

    //꾸꾸) 로그아웃 요청을 처리하는 메서드
    @PostMapping("/logout")
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        //1. Access Token 쿠키 제거
        Cookie jwtCookie = new Cookie("ACCESS_TOKEN", null); //서비스,리포지토리 단에서 하긴 함
        jwtCookie.setMaxAge(0);
        jwtCookie.setPath("/"); //로그아웃 후 홈페이지 첫 화면으로 이동?
        response.addCookie(jwtCookie);
        
        //2. 세션 무효화
        HttpSession session = request.getSession(false);
        if(session != null) session.invalidate();

        //꾸꾸) ★3. Redis의 Refresh Token 삭제(누락됐던 부분 추가)
        String msUuid = /* 현재 인증 컨텍스트에서 추출 */;
        redisService.deleteData(msUuid);
    }

    /*
    Q.
    그럼 한번 로그인하면 사용자가 로그아웃할 거라고 먼저 말하지 않는 이상 RT(Refresh Token)은 살아있다.
    (예를 들어 네이버 로그인 계속 로그인 상태로 두기 체크하면 살아있는 거처럼)
    그런데 AT(Access Token)은 짧게 설정해서 AT 사용 가능 기간 동안은 그 AT로 본인임을 인증하지만
    (혹은 접근 가능하다는 걸 증명하지만)
    그 시간이 지나면 AT를 새로 발급받는데 RT가 살아있으니, 실제 사용자는 느끼지 못하도록 뒷단에서 RT를 기반으로
    AT를 새로 발급해주는 작업이 일어나고, 새로운 AT로 또 다시 자신임을 인증하면서 서비스를 사용한다.
    그런데 RT는 오랜 시간 지나면 소멸된다. 이 때는 어쩔 수 없이 로그인을 다시 해야 한다.

    A.
    1. "RT가 살아있다" - 조건이 하나 붙는다.
    ----------------------------------------------
    네이버의 "로그인 상태 유지" 체크박스가 정확히 그것임. 체크 안 하면 RT를 안 주거나 아주 짧게 줌.
    MS 쪽도 offline_acess scope를 요구해야 RT가 발급됨.
     - 이 코드에 offline_access가 있는 이유가 바로 이것

    2. "RT가 소멸" - 절대 만료 vs 슬라이딩 만료
    ----------------------------------------------
    RT의 수명이 어떻게 끝나는지에 대해 두 가지 패턴 있음.

    절대 만료(이 코드에서 설계한 것)
    * 로그인 시 14일 고정, 그 사이 AT를 아무리 갱신해도 RT 만료는 안 늘어남.
    * 14일 지나면 무조건 재로그인

    슬라이딩 만료(많은 서비스가 쓰는 방식)
    * AT를 RT로 갱신할 때마다 RT 유효기간도 연장
    * "최근 14일 안에 활동한 적 있으면 계속 살려주고, 14일간 방치하면 소멸"
    * 네이버 "계속 로그인"이 실제로 이렇게 동작함.

     - 이 코드에서는 RedisServiceImpl.extendExpiration()이 주석 처리 되어 있어 절대 만료 방식임.

    3. "뒷단에서 AT를 새로 발급" - 회전(Rotation) 여부
    ------------------------------------------------
    AT 갱신 시 RT를 그대로 재사용할 수도 있고, 매번 새 RT를 발급하고 구 RT를 폐기(Rotation)할 수도 있음.
    Rotation은 RT가 유출돼도 재사용 시점에 탐지할 수 있어서 권장 관행임. 이 코드는 Rotation 미구현 상태.

    전체 생명주기 (이 코드의 설계 의도)
    -------------------------------------------------
    MS 로그인 성공
     |- AT 발급 (3시간) -> 쿠키
     |- RT 발급 (14일) -> Redis

    매 3시간: AT만료 -> RT로 새 AT 발급 (사용자 무감지) <- 현재 미구현 
    14일 경과: RT 소멸 -> MS 재로그인
    로그아웃: AT 쿠키 삭제 + RT Redis 삭제 <- RT삭제 현재 미구현

    한 가지 주의: 이 코드는 아직 그 회로를 완성 못 함.
    -------------------------------------------------
    로그인 시 AT, RT 발급 : 구현됨
    RT로 AT 재발급: X (refreshAccessToken 주석, 엔드포인트 없음)
    RT로 슬라이딩 연장: X(extendExpiration 주석)
    로그아웃 시 RT 삭제: X(deleteData 주석)

    그래서 지금 이 서비스는 실제로 "3시간마다 MS 재로그인" 상태이고, 
    무감지 갱신은 구현되어야 할 동작임.

    한줄 요약: Rt는 재로그인 면제권이고, at는 그 권리를 3시간 단위로 소진하는 티켓, rt가 소멸하거나 로그아웃하면 권리 끝남
    한줄 요약: 로그아웃 시 Refresh Token은 남겨두면 안 됨. 삭제(무효화) 해야 함.

    Refresh Token의 정상적인 생명주기
    1. 발급 - 로그인 성공 시 Access Token과 함께 발급 (이 코드: generateRefreshToken, Redis에 <msUuid -> refreshJwt> 저장, 14일).
    2. 사용 - Access Token이 만료되면, Refresh Token으로 재로그인 없이 새 Acess Token을 발급 (이 코드: refreshAccessToken은 주석 상태라 미구현)
    3. 회전(Rotation) - 좋은 관행은 매 사용마다 새 Refresh Token을 발급하고 기존 것을 무효화하는 것, 도난 시 피해 제한.
    4. 폐기 - 로그아웃 시 반드시 삭제.
    */
}

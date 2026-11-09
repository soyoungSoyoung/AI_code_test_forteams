package com.forteams.auth.service;

import com.forteams.auth.entity.MsUserEntity;
import com.forteams.auth.entity.UserEntity;
import com.forteams.auth.provider.JwtProvider;
import com.forteams.auth.repository.MsUserRepository;
import com.forteams.auth.repository.UserRepository;
import com.forteams.auth.service.nickname.NicknameServiceImpl;
import com.forteams.auth.service.token.RedisService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {
    private final MsUserRepository msUserRepository;
    private final UserRepository userRepository;
    private final NicknameServiceImpl nicknameService;
    private final JwtProvider jwtProvider;
    // Redis에서 CustomOAuth2User를 저장하기 위한 service
    private final RedisService redisService;

    @Override
    public MsUserEntity findOrCreateUser(String microsoftId, String userName) {
        Optional<MsUserEntity> optionalUser = msUserRepository.findByMicrosoftId(microsoftId);

        return optionalUser.orElseGet(() -> {
            MsUserEntity newUser = new MsUserEntity();
            newUser.setMicrosoftId(microsoftId);
            newUser.setUserName(userName);
            return msUserRepository.save(newUser);
        });
    }

    @Override
    public void updateAdditionalInfo(String msUuid, String userDept, HttpServletResponse response) {
        MsUserEntity msUserEntity = msUserRepository.findByMsUuid(msUuid);
        if (msUserEntity != null) {

            // 새 UserEntity 객체 생성 및 msUserEntity와 연결
            UserEntity newUserEntity = new UserEntity();
            newUserEntity.setMsUserEntity(msUserEntity);
            newUserEntity.setUserRole("ROLE_USER");
            newUserEntity.setUserDept(userDept);
            // newUserEntity.setUserNickname(nicknameService.assignNickname()); //redis에서 꺼내오기
            /**
             [닉네임 되돌리기] -> 이 부분은 프로젝트의 특성상, 고갈될 일이 없을 것이라 판단하여 구현해두지 않았으나 안정성을 위해 수정함
             [3단계 수정] 닉네임 고갈(null) 가드

             왜 이걸 해야 하는가:
              - assignNickname()은 Redis 풀이 비어있으면 null을 반환.
              - 기존 코드는 그 null을 그대로 setUserNickname(null)로 저장했음.
                -> 닉네임 없는 회원이 DB에 생성되고, 하류(챗봇 등)에서 NPE/이상 동작 일어날 수 있음.

             어떻게 바꿔야 하나:
              - null을 확인하여 즉시 예외를 던짐.
                트랜잭션이 롤백되어 UserEntity 저장 자체가 취소됨(반쪽 상태 방지).
              - 호출 측(AuthController)은 이 예외를 503으로 변환해 (ToDo: 503반환 관련 코드 수정)
                프론트에 "닉네임이 모두 소진됨"을 알릴 수 있음.
            */
            String nickname = nicknameService.assignNickname();
            if(nickname == null) {
                throw new IllegalStateException("사용 가능한 닉네임이 없습니다. 관리자에게 문의하세요.");
            }
            newUserEntity.setUserNickname(nickname);

            userRepository.save(newUserEntity);

            //jwt 빚어서 쿠키 굽기
            String accessJwt = jwtProvider.generateAccessToken(msUuid); //  =======> accessToken을 안 쓰는데 뭐냐악...
            Cookie cookie = new Cookie("ACCESS_TOKEN", accessJwt); // 쿠키 생성
            cookie.setHttpOnly(false); // 자바스크립트 접근 허용
            cookie.setSecure(false); // HTTPS 통신에서만 쿠키 전송이 아니라 다 허용
            cookie.setPath("/"); // 쿠키의 경로 설정
            cookie.setMaxAge(7 * 24 * 60 * 60); // 쿠키 만료 시간 설정 (예: 1주일)
            response.addCookie(cookie); // 응답에 쿠키 추가

            // <msUuid, refreshToken>로 map만들어서 redis에 넣기 -----------------------------------------------------
            String refreshJwt = jwtProvider.generateRefreshToken(msUuid);
            redisService.saveData(msUuid, refreshJwt, 14);
            Map<String, Object> redisData = new HashMap<>();
//        OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
            redisData.put(msUuid, refreshJwt);
        }
    }

    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        // 1. 쿠키에서 JWT 토큰 제거
        Cookie jwtCookie = new Cookie("ACCESS_TOKEN", null);
        jwtCookie.setHttpOnly(false);
        jwtCookie.setSecure(false); // HTTPS를 사용하는 경우 true로 설정
        jwtCookie.setMaxAge(0);
        jwtCookie.setPath("/");
        response.addCookie(jwtCookie);

        // 2. 세션 무효화
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }
}

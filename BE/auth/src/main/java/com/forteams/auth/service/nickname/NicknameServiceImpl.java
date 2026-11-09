package com.forteams.auth.service.nickname;

import com.forteams.auth.entity.NicknameEntity;
import com.forteams.auth.repository.NicknameRepository;
import jakarta.annotation.PostConstruct;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class NicknameServiceImpl implements NicknameService {
    private final NicknameRepository nicknameRepository;
    private final StringRedisTemplate redisTemplate;
    private static final String NICKNAME_SET_KEY = "available_nicknames";

    /**
     [닉네임 되돌리기]
     [2단계 수정] 시작 시 Redis 풀과 DB를 항상 전체 대조

     왜 이걸 해야 하는가:
      - [1단계] push-back은 "DB 저장 실패"는 막지만 "JVM 크래시"는 못 막음.
        SPOP은 성공한 뒤 프로세스가 죽으면 catch가 실행되지 않아, 닉네임이 유실됨.
      - Redis는 재시작 후에도 이전 데이터가 유지됨(지속성이 있음).
        즉 유실된 닉네임은 재시작 후에도 풀에 돌아오지 않음.
      - 기존 코드는 size==0(Set이 완전히 비어있을 때)에만 채웠으므로,
        일부만 유실된 경우엔 영구 복구가 되지 않음.
     
     어떻게 바꿔야 하는가:
      - size==0 조건을 제거, 매 시작 시 DB의 미할당 닉네임 전체를 SADD해야 함.
      - SADD는 멱등(idempotent)이라 풀에 이미 있는 닉네임을 다시 넣어도 해롭지 않음.
        따라서 "빠진 것만 골라 넣는" 대조 로직 없이 무조건 전체 SADD해도 효과는 같음.

     한계:
      - 이전 세션에서 유실된 닉네임만 복귀됨.
        현재 세션 중 발생한 누수는 [1단계] push-back이 담당.
    */
    @PostConstruct
    public void init() {
        SetOperations<String, String> setOps = redisTemplate.opsForSet();
        // if (setOps.size(NICKNAME_SET_KEY) == 0) { //비어있든 아니든 수행
            List<NicknameEntity> nicknames = nicknameRepository.findAll();
            for (NicknameEntity nickname : nicknames) {
                if (!nickname.isAssigned()) {
                    setOps.add(NICKNAME_SET_KEY, nickname.getNickname());
                }
            }
        // }
    }

    /**
     [닉네임 되돌리기]
         [1단계 수정] 닉네임 부여 시 push-back 보상(원천 차단)

         왜 이걸 해야 하는가:
          - SPOP(Redis에서 닉네임 꺼내기)은 "비가역"임. 일단 꺼내면 풀에 다시 돌아가지 않음.
          - 반면 DB 저장(sava)은 트랜잭션에 속하므로 실패하면 롤백됨.
          - 이 둘 사이에 틈: SPOP은 성공했는데 DB save가 실패하면,
            그 닉네임은 "Redis에도 없고, DB에도 미할당"인 유령 상태가 되어 영구 누수됨.
          - Spring @Transactional은 DB 트랜잭션만 묶고 Redis 연산은 보호하지 않음.

         어떻게 바꿔야 하나:
          - DB 저장 구간(findById/save)을 try-catch로 감쌈.
          - 실패 시 catch에서 SADD로 해당 닉네임을 Redis 풀에 되돌려놓음(보상).
          - 그리고 예외를 다시 던짐(rethrow). 이렇게 하면 트랜잭션이 롤백되어
            DB의 assigned=true 변경도 되돌아감. 즉 DB는 미할당으로, Redis는 풀로 복귀함.
          - 보상(SADD)은 DB 트랜잭션 밖이라 rethrow/롤백에도 그대로 유지됨.

         한계:
          - JVM 크래시(프로세스 사망)는 catch가 못 막음. 그 구멍은 [2단계] init() 전체 대조가 메움.
    */
    @Transactional
    public String assignNickname() {
        String nickname = redisTemplate.opsForSet().pop(NICKNAME_SET_KEY);
        // if (nickname != null) {
        //     NicknameEntity nick = nicknameRepository.findById(nickname).orElseThrow();
        //     nick.setAssigned(true);
        //     nicknameRepository.save(nick);
        // }
        
        if(nickname != null) {
            try {
                NicknameEntity nick = nicknameRepository.findById(nickname).orElseThrow();
                nick.setAssigned(true);
                nicknameRepository.save(nick);
            } catch (Exception e) {
                // DB저장 실패 -> Redis에서 이미 꺼낸 닉네임을 풀에 되돌려놓기
                // (SADD는 멱등이라 중복 추가 걱정 없음)
                redisTemplate.opsForSet().add(NICKNAME_SET_KEY, nickname); //보상: 되돌려놓기
                // 예외 rethrow: 트랜잭션 롤백으로 DB 변경도 되돌림.
                throw e;
            }
        }
        return nickname;
        /*
        - 트랜잭션 롤백 시에도 보상이 실행되려면 catch 후 재throw(트랜잭션이 롤백됨)
            + 보상은 DB 트랜잭션 밖이라 그대로 유지되는 구조가 됨.
        - 크래시(JVM 사망)는 이 보상으로 막을 수 없음 - 그 구멍은 2순위가 메움.
        */
    }

    /**
     * 회원 탈퇴할 때,
     * 닉네임을 Redis의 Set에 다시 추가하고, 할당 상태를 업데이트하는 메소드
     * @param nickname 반환할 닉네임
     */
    @Transactional
    public void returnNicknameToPool(String nickname) {
        NicknameEntity nicknameEntity = nicknameRepository.findById(nickname)
                .orElseThrow(() -> new IllegalArgumentException("Invalid nickname: " + nickname));
        nicknameEntity.setAssigned(false);
        nicknameRepository.save(nicknameEntity);
        redisTemplate.opsForSet().add(NICKNAME_SET_KEY, nickname);
    }
    
}

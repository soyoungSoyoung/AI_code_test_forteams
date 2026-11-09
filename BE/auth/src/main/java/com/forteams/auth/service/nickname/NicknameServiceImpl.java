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

    @PostConstruct
    public void init() {
        SetOperations<String, String> setOps = redisTemplate.opsForSet();
        if (setOps.size(NICKNAME_SET_KEY) == 0) {
            List<NicknameEntity> nicknames = nicknameRepository.findAll();
            for (NicknameEntity nickname : nicknames) {
                if (!nickname.isAssigned()) {
                    setOps.add(NICKNAME_SET_KEY, nickname.getNickname());
                }
            }
        }
    }

    @Transactional
    public String assignNickname() {
        String nickname = redisTemplate.opsForSet().pop(NICKNAME_SET_KEY);
        // if (nickname != null) {
        //     NicknameEntity nick = nicknameRepository.findById(nickname).orElseThrow();
        //     nick.setAssigned(true);
        //     nicknameRepository.save(nick);
        // }
        /*닉네임 누수를 막는 로직 추가 1. 
          (Redis에서 뽑았던 닉네임을, 직원에게 배정 못하게 되면, set에 다시 돌려놓는 로직 필요)
          (Transaction은 일반 DB에만 적용되고, Redis엔 적용이 안 돼서 다시 넣어주는 액션 필요)
          (push-back이라고 함)
        */
        if(nickname != null) {
            try {
                NicknameEntity nick = nicknameRepository.findById(nickname).orElseThrow();
                nick.setAssigned(true);
                nicknameRepository.save(nick);
            } catch (Exception e) {
                redisTemplate.opsForSet().add(NICKNAME_SET_KEY, nickname); //보상: 되돌려놓기
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

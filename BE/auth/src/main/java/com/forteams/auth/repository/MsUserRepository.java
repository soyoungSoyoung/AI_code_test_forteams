package com.forteams.auth.repository;

import com.forteams.auth.entity.MsUserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
// public interface MsUserRepository extends JpaRepository<MsUserEntity, Long> {
public interface MsUserRepository extends JpaRepository<MsUserEntity, String> { //PK가 Long -> String으로 타입 변경

    MsUserEntity findByMsUuid(String msUuid);

    Optional<MsUserEntity> findByMicrosoftId(String microsoftId);
}

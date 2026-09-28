package com.myplatform.backend.youtubeopinion;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface YtVideoParticipantRepository extends JpaRepository<YtVideoParticipant, Long> {

    List<YtVideoParticipant> findByVideoId(String videoId);

    List<YtVideoParticipant> findByVideoIdIn(Collection<String> videoIds);
}

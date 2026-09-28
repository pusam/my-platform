package com.myplatform.backend.youtubeopinion;

import java.text.Normalizer;
import java.util.Locale;

/**
 * 이름 비교 키 — 공백·보이지 않는 문자·전각/반각·대소문자 차이를 지운다. 순수 함수.
 *
 * <p>발언자(출연자 명단)와 종목명(종목 마스터)을 <b>정확히 같은지</b> 볼 때만 쓴다. 비슷한 이름을 같다고 보는
 * 유사도 판정은 하지 않는다 — "하이닉스"를 "SK하이닉스"로 붙이는 일은 사람이 검토에서 한다.
 */
public final class NameKeys {

    private NameKeys() {}

    public static String key(String s) {
        if (s == null) return null;
        String n = Normalizer.normalize(s, Normalizer.Form.NFKC)
                .replaceAll("[\\u200B-\\u200D\\uFEFF]", "")
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
        return n.isEmpty() ? null : n;
    }

    /** 원문 대조용 — 공백과 흔한 문장부호를 뺀다(자막마다 띄어쓰기·부호가 달라서). */
    public static String compact(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFKC)
                .replaceAll("[\\s\\u200B-\\u200D\\uFEFF.,!?…·\"'“”‘’~\\-:：]", "")
                .toLowerCase(Locale.ROOT);
    }
}

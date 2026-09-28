package com.myplatform.backend.youtubeopinion;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 발언 속 종목 표기 → 종목 마스터 코드. 순수 함수(조회는 주입).
 *
 * <p>정확히 같은 이름(공백·대소문자 무시)이 <b>하나</b>일 때만 확정한다. 여럿이면 AMBIGUOUS, 없으면 UNMATCHED
 * (자막 오인식·약칭 — "하이닉스" 같은)로 두고 사람이 검토한다. 모델이 코드를 줬는데 그 코드의 이름이 발언 표기와
 * 다르면 CODE_MISMATCH — 어느 쪽이 맞는지 모르므로 추측하지 않는다.
 */
public final class StockMentionResolver {

    private StockMentionResolver() {}

    private static final Pattern CODE = Pattern.compile("^[0-9A-Z]{6}$");

    public record StockRef(String code, String name) {}

    public interface Lookup {
        Optional<StockRef> byCode(String code);

        /** 이름 후보(부분 일치 포함 가능) — 정확 일치 판정은 여기서 한다. */
        List<StockRef> byName(String name);
    }

    public record Result(String stockCode, YtOpinion.MappingStatus status) {
        public boolean verified() { return status == YtOpinion.MappingStatus.VERIFIED; }
    }

    public static Result resolve(String rawName, String modelCode, Lookup lookup) {
        String nameKey = NameKeys.key(rawName);
        String code = modelCode != null ? modelCode.trim().toUpperCase() : null;
        if ((code == null || code.isEmpty()) && rawName != null && CODE.matcher(rawName.trim()).matches()) {
            code = rawName.trim();
        }
        if (code != null && !code.isEmpty()) {
            if (!CODE.matcher(code).matches()) return new Result(null, YtOpinion.MappingStatus.CODE_MISMATCH);
            Optional<StockRef> ref = lookup.byCode(code);
            if (ref.isEmpty()) return new Result(null, YtOpinion.MappingStatus.CODE_MISMATCH);
            boolean nameIsCode = rawName != null && rawName.trim().equals(code);
            if (nameIsCode || NameKeys.key(ref.get().name()).equals(nameKey)) {
                return new Result(ref.get().code(), YtOpinion.MappingStatus.VERIFIED);
            }
            return new Result(null, YtOpinion.MappingStatus.CODE_MISMATCH);
        }
        if (nameKey == null) return new Result(null, YtOpinion.MappingStatus.UNMATCHED);
        Map<String, StockRef> exact = new LinkedHashMap<>();
        for (StockRef r : lookup.byName(rawName.trim())) {
            if (nameKey.equals(NameKeys.key(r.name()))) exact.putIfAbsent(r.code(), r);
        }
        if (exact.size() == 1) return new Result(exact.keySet().iterator().next(), YtOpinion.MappingStatus.VERIFIED);
        return new Result(null, exact.isEmpty() ? YtOpinion.MappingStatus.UNMATCHED : YtOpinion.MappingStatus.AMBIGUOUS);
    }
}

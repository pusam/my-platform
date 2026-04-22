package com.myplatform.backend.util;

public final class PasswordPolicy {

    private static final int MIN_LENGTH = 12;
    private static final int MAX_LENGTH = 128;

    private PasswordPolicy() {}

    public static String validate(String password, String username, String email) {
        if (password == null || password.isEmpty()) {
            return "비밀번호를 입력해주세요.";
        }
        if (password.length() < MIN_LENGTH) {
            return "비밀번호는 최소 " + MIN_LENGTH + "자 이상이어야 합니다.";
        }
        if (password.length() > MAX_LENGTH) {
            return "비밀번호는 최대 " + MAX_LENGTH + "자까지 가능합니다.";
        }
        if (!password.matches(".*[a-z].*")) {
            return "비밀번호에 소문자를 포함해야 합니다.";
        }
        if (!password.matches(".*[A-Z].*")) {
            return "비밀번호에 대문자를 포함해야 합니다.";
        }
        if (!password.matches(".*\\d.*")) {
            return "비밀번호에 숫자를 포함해야 합니다.";
        }
        if (!password.matches(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?].*")) {
            return "비밀번호에 특수문자(!@#$%^&* 등)를 포함해야 합니다.";
        }
        String lower = password.toLowerCase();
        if (username != null && !username.isBlank()
                && lower.contains(username.toLowerCase())) {
            return "비밀번호에 아이디를 포함할 수 없습니다.";
        }
        if (email != null && email.contains("@")) {
            String local = email.substring(0, email.indexOf('@')).toLowerCase();
            if (!local.isBlank() && lower.contains(local)) {
                return "비밀번호에 이메일 아이디를 포함할 수 없습니다.";
            }
        }
        return null;
    }
}

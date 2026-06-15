package com.pbm.command.dto.request;

/**
 * 로그인 페이지 폼 메타데이터 요청 DTO.
 *
 * 역할: extension이 로그인 페이지에서 아이디/비밀번호 필드의 존재와 입력 여부,
 *       그리고 다시 찾을 수 있는 selector 정보를 backend에 전달한다.
 * 보안: 실제 아이디/비밀번호 값은 절대 포함하지 않는다.
 */
public record LoginFormRequest(
        boolean detected,
        boolean usernameFilled,
        boolean passwordFilled,
        String usernameSelector,
        String passwordSelector,
        String loginButtonSelector,
        String usernameLabel,
        String passwordLabel,
        String loginButtonLabel
) {
}

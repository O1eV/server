package com.ove.market.collector.kiwoom;

/**
 * 키움 API 호출 실패. 오류 코드(공식 오류표의 return_code)가 있으면 사람이 읽기 쉬운 설명으로 바꿔 담는다.
 * 메시지에는 App Key·Secret·토큰 같은 비밀 값을 절대 넣지 않는다.
 */
public class KiwoomException extends RuntimeException {

    private final Integer code;

    public KiwoomException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    /** 키움이 준 오류 코드. HTTP 오류처럼 코드가 없으면 null */
    public Integer getCode() {
        return code;
    }

    /** 토큰 자체가 문제라서 다시 발급받아야 하는 오류인지 */
    public boolean isTokenProblem() {
        return code != null && (code == 8005 || code == 8010 || code == 8031 || code == 8103);
    }

    /**
     * 공식 오류표(kiwoom_api_spec.json의 error_codes)를 바탕으로 한 안내. 서버가 준 return_msg는 참고로만 덧붙인다.
     */
    public static KiwoomException of(int code, String serverMessage) {
        String guide = switch (code) {
            case 8001, 8002 -> "App Key/Secret Key 검증에 실패했습니다. KIWOOM_APP_KEY, KIWOOM_APP_SECRET 값을 확인하세요.";
            case 8020 -> "App Key 또는 Secret Key가 비어 있습니다. KIWOOM_APP_KEY, KIWOOM_APP_SECRET을 설정하세요.";
            case 8005 -> "토큰이 유효하지 않습니다. 토큰을 다시 발급받아야 합니다.";
            case 8010 -> "토큰을 발급받은 IP와 요청한 IP가 다릅니다. VPN·공유기·네트워크 변경 여부를 확인하고, "
                    + "같은 IP에서 토큰을 다시 발급받으세요.";
            case 8030 -> "투자구분(실전/모의)이 달라서 이 App Key를 사용할 수 없습니다. 키와 접속 도메인(실전/모의)을 맞추세요.";
            case 8031 -> "투자구분(실전/모의)이 달라서 이 토큰을 사용할 수 없습니다. 키와 접속 도메인(실전/모의)을 맞추세요.";
            case 8040, 8050, 8103 -> "단말기 또는 토큰 인증에 실패했습니다.";
            case 8104 -> "모의투자에서 지원하지 않는 API입니다.";
            case 8200 -> "법인 고객은 이 API를 지원하지 않습니다.";
            case 1700, 1701, 1702 -> "허용된 API 호출 한도를 초과했습니다. 잠시 후 다시 시도합니다.";
            case 1901, 1902, 1903 -> "종목 정보를 찾을 수 없습니다. 종목 코드를 확인하세요.";
            default -> "키움 API 오류입니다.";
        };
        String detail = serverMessage == null || serverMessage.isBlank() ? "" : " (서버 메시지: " + serverMessage + ")";
        return new KiwoomException(code, "[" + code + "] " + guide + detail);
    }
}

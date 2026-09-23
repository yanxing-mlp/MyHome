package com.familyhome.file.biz.service;

import com.familyhome.common.enums.DataScope;
import com.familyhome.common.exception.BizException;
import com.familyhome.common.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 视频<b>播放票据</b>的签发与校验。
 *
 * <p><b>为什么需要它</b>：{@code <video src>} 由浏览器直接发 GET，<b>带不上 {@code Authorization} 头</b>，
 * 所以播放接口不能像别的接口那样靠拦截器验登录令牌。可视频（尤其私人视频）又必须鉴权、还必须支持
 * HTTP Range（否则不能 seek / 快进）。这两件事的交集就是业界通行的<b>预签名 URL</b>：
 * 列表接口（本身要登录）为每支视频现签一枚<b>短时、只绑这一支视频</b>的票据放进 {@code playUrl}，
 * 播放接口只验这枚票据的签名和有效期，验过就吐字节流。
 *
 * <p><b>它和登录令牌（{@code SessionToken}）是两回事，刻意不复用同一枚</b>：登录令牌是"有效期内的身份凭证"，
 * 一旦落进 nginx access log 就等于把身份摊出去（用户明令禁止令牌进日志/URL）。播放票据只授予
 * "读这一支视频、这一段时间"，泄漏了也调不动任何别的接口、很快过期，进 URL 是可接受的。
 *
 * <p><b>格式</b>：{@code v1.<fileObjectId>.<scope>.<ownerId>.<exp秒>.<base64url(HMAC-SHA256(前四段))>}，
 * 与 {@code SessionToken} 同一手法（JDK 自带 HmacSHA256、定长时间比较、URL 安全无填充 base64）。
 * 密钥<b>复用 {@code fh.auth.token-secret}</b>，不新增配置项——它已经是"只有服务端知道"的那把 HMAC 钥匙。
 */
@Slf4j
@Component
public class VideoPlayTicket {

    /** 票据有效期：6 小时。够看完一部长片（含中途 seek），又短到泄漏很快失效。过期后重新拉一次列表即可换新票据。 */
    public static final long TTL_SECONDS = 6L * 60 * 60;

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String VERSION = "v1";
    private static final char SEP = '.';

    private final SecretKeySpec key;

    public VideoPlayTicket(@Value("${fh.auth.token-secret:}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("fh.auth.token-secret 未配置，无法签发视频播放票据");
        }
        this.key = new SecretKeySpec(secret.trim().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
    }

    /** 校验通过后的票据内容：绑定到具体某支视频的某个分区。 */
    public record Claim(long videoId, DataScope scope, long ownerId) {
    }

    /** 为一支视频签一枚现在起 {@link #TTL_SECONDS} 秒内有效的播放票据。 */
    public String issue(long videoId, DataScope scope, long ownerId) {
        long exp = Instant.now().getEpochSecond() + TTL_SECONDS;
        String payload = VERSION + SEP + videoId + SEP + scope.name() + SEP + ownerId + SEP + exp;
        return payload + SEP + sign(payload);
    }

    /**
     * 校验票据。任何不通过（空 / 格式烂 / 签名不对 / 过期）都抛 {@code USER_FORBIDDEN}（403），
     * 不细分原因——对播放器来说都是同一件事：这枚链接不能用了，刷新列表换一枚。
     *
     * @param ticket    查询参数里的票据原文
     * @param expectId  路径上的视频 id，必须与票据里绑定的 id 一致（防止拿 A 的票据播 B）
     */
    public Claim verify(String ticket, long expectId) {
        if (ticket == null || ticket.isBlank()) {
            throw forbidden();
        }
        String trimmed = ticket.trim();
        int lastSep = trimmed.lastIndexOf(SEP);
        if (lastSep <= 0 || lastSep == trimmed.length() - 1) {
            throw forbidden();
        }
        String payload = trimmed.substring(0, lastSep);
        String givenSig = trimmed.substring(lastSep + 1);
        byte[] expected = sign(payload).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, givenSig.getBytes(StandardCharsets.UTF_8))) {
            throw forbidden();
        }
        String[] parts = payload.split("\\.");
        if (parts.length != 5 || !VERSION.equals(parts[0])) {
            throw forbidden();
        }
        try {
            long videoId = Long.parseLong(parts[1]);
            DataScope scope = DataScope.valueOf(parts[2]);
            long ownerId = Long.parseLong(parts[3]);
            long exp = Long.parseLong(parts[4]);
            if (videoId != expectId || exp < Instant.now().getEpochSecond()) {
                throw forbidden();
            }
            return new Claim(videoId, scope, ownerId);
        } catch (IllegalArgumentException e) {
            throw forbidden();
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(key);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("视频播放票据签名失败", e);
        }
    }

    private static BizException forbidden() {
        return BizException.of(ErrorCode.USER_FORBIDDEN, "播放链接已失效，请刷新后重试");
    }
}

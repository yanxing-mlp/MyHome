package com.familyhome.file.biz.controller.c;

import com.familyhome.common.enums.DataScope;
import com.familyhome.common.result.Result;
import com.familyhome.file.biz.model.vo.admin.VideoVO;
import com.familyhome.file.biz.model.vo.client.VideoClientVO;
import com.familyhome.file.biz.service.VideoService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 视频域 C 端接口：C 端只"看"视频，就<b>列表 + 播放流</b>两条，没有上传/删除（那些是 B 端"视频管理"的事）。
 *
 * <p>与相册 C 端同一手法——路径与返回形状按 C 端裁剪，查询/分区/票据口径仍与 B 端共用同一个
 * {@link VideoService}，不是转发层、也没有第二份实现。
 *
 * <p>分区口径与 B 端一致：{@code scope} 默认 PUBLIC（家庭视频，全家可见），PRIVATE 只返回当前账号自己的
 * 私人视频（属主由服务端按登录态判定，ADMIN 无例外，跨属主裸 id 一律查不到）。
 *
 * <p>{@code /{id}/stream} 与 B 端那条一样，是唯一不验登录令牌的一条：{@code <video src>} 由浏览器直接发 GET、
 * 带不上 {@code Authorization} 头，所以靠查询参数里的短时签名票据鉴权（原因见 {@code VideoPlayTicket}）；
 * 拦截器对没有 Bearer 头的请求按匿名放行，这一条才能走到 controller 由票据把关。
 */
@Validated
@RestController
@RequestMapping("/api/c/video")
@RequiredArgsConstructor
public class VideoCController {

    private final VideoService videoService;

    /** 列表：最近上传在前，每行带一枚指向 C 端播放流的短时签名票据；裁成 C 端只读视图。 */
    @GetMapping
    public Result<List<VideoClientVO>> list(
            @RequestParam(name = "scope", defaultValue = "PUBLIC") DataScope scope) {
        List<VideoClientVO> list = videoService.listForClient(scope).stream()
                .map(VideoCController::toClientVO)
                .toList();
        return Result.ok(list);
    }

    /** 播放流：支持 HTTP Range（可 seek / 快进），靠 {@code ticket} 短时签名鉴权，与 B 端共用服务。 */
    @GetMapping("/{id}/stream")
    public ResponseEntity<Resource> stream(
            @PathVariable Long id,
            @RequestParam("ticket") String ticket) {
        return videoService.stream(id, ticket);
    }

    private static VideoClientVO toClientVO(VideoVO vo) {
        VideoClientVO client = new VideoClientVO();
        client.setId(vo.getId());
        client.setName(vo.getName());
        client.setFileType(vo.getFileType());
        client.setFileSize(vo.getFileSize());
        // playUrl 已由 listForClient 签成 C 端路径，这里原样带出，不改写。
        client.setPlayUrl(vo.getPlayUrl());
        return client;
    }
}

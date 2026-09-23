package com.familyhome.file.biz.controller.b;

import com.familyhome.common.enums.DataScope;
import com.familyhome.common.result.Result;
import com.familyhome.file.biz.model.vo.admin.VideoVO;
import com.familyhome.file.biz.service.VideoService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 视频管理 B 端接口，供"视频管理"页（公共视频 / 个人视频）使用。
 *
 * <p>scope 查询参数默认 PUBLIC；列表/上传/删除都要求登录（{@code DataPartition.forRequest} 里 {@code requireUserId}），
 * 私人视频只落在当前账号分区，ADMIN 无例外，跨分区裸 id 一律 404——与文档、密码本同一口径。
 *
 * <p>{@code /{id}/stream} 是唯一不验登录令牌的一条：它靠查询参数里的短时签名票据鉴权，
 * 因为 {@code <video src>} 由浏览器直接发 GET、带不上 {@code Authorization} 头（原因见 {@code VideoPlayTicket}）。
 * 拦截器对没有 Bearer 头的请求按匿名放行，所以这一条能走到 controller 由票据把关。
 */
@Validated
@RestController
@RequestMapping("/api/b/video")
@RequiredArgsConstructor
public class VideoController {

    private final VideoService videoService;

    /** 列表，最近上传在前，每行带一枚现签的播放票据。 */
    @GetMapping
    public Result<List<VideoVO>> list(
            @RequestParam(name = "scope", defaultValue = "PUBLIC") DataScope scope) {
        return Result.ok(videoService.list(scope));
    }

    /**
     * 上传一支视频。multipart/form-data：{@code file} 视频本体。
     * 类型（扩展名 + mime）由服务端按文件名解析并校验（非视频 415），前端不传、也不能传。
     */
    @PostMapping
    public Result<VideoVO> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(name = "scope", defaultValue = "PUBLIC") DataScope scope) {
        return Result.ok(videoService.upload(file, scope));
    }

    /** 删除（软删记录 + 物理删文件）。 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(
            @PathVariable Long id,
            @RequestParam(name = "scope", defaultValue = "PUBLIC") DataScope scope) {
        videoService.delete(id, scope);
        return Result.ok();
    }

    /** 播放流：支持 HTTP Range（可 seek / 快进），靠 {@code ticket} 短时签名鉴权。 */
    @GetMapping("/{id}/stream")
    public ResponseEntity<Resource> stream(
            @PathVariable Long id,
            @RequestParam("ticket") String ticket) {
        return videoService.stream(id, ticket);
    }
}

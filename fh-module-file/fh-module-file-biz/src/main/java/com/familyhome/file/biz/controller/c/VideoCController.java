package com.familyhome.file.biz.controller.c;

import com.familyhome.common.enums.DataScope;
import com.familyhome.common.result.Result;
import com.familyhome.file.biz.model.vo.admin.VideoVO;
import com.familyhome.file.biz.model.vo.client.VideoClientVO;
import com.familyhome.file.biz.model.vo.client.VideoGroupClientVO;
import com.familyhome.file.biz.service.FileGroupService;
import com.familyhome.file.biz.service.VideoService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 视频域 C 端接口：C 端能"看"也能"传"视频——<b>列表 + 分组 + 上传 + 播放流</b>，但<b>不删除</b>
 * （删除仍只在 B 端"视频管理"，与相册 C 端"能传不能删"同一口径）。
 *
 * <p>与相册 C 端同一手法——路径与返回形状按 C 端裁剪，上传/查询/分区/票据口径仍与 B 端共用同一个
 * {@link VideoService}（上传直接调 {@link VideoService#upload}，流式落盘、分区、添加人、分组绑定全是同一套），
 * 不是转发层、也没有第二份实现。
 *
 * <p>分区口径与 B 端一致：{@code scope} 默认 PUBLIC（家庭视频，全家可见），PRIVATE 只落在当前账号自己的
 * 私人视频（属主由服务端按登录态判定，ADMIN 无例外，跨属主裸 id 一律查不到）。上传的分组可选、留空即未分组，
 * 未分组的视频只在 C 端「全部视频」里能看见。
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
    private final FileGroupService fileGroupService;

    /**
     * 视频分组卡片列表（两级展示的第一级）：C 端只读，只给名字 + 数量，不含内部字段。
     *
     * <p>分组没有上下架，所以这里就是当前分区的全部视频分组；点进某组再走 {@code GET /api/c/video?groupId=}。
     */
    @GetMapping("/groups")
    public Result<List<VideoGroupClientVO>> groups(
            @RequestParam(name = "scope", defaultValue = "PUBLIC") DataScope scope) {
        List<VideoGroupClientVO> list = fileGroupService
                .list(FileGroupService.BIZ_VIDEO, null, scope).stream()
                .map(g -> {
                    VideoGroupClientVO vo = new VideoGroupClientVO();
                    vo.setGroupId(g.getId());
                    vo.setName(g.getName());
                    vo.setVideoCount(g.getFileCount());
                    return vo;
                })
                .toList();
        return Result.ok(list);
    }

    /** 列表：最近上传在前，每行带一枚指向 C 端播放流的短时签名票据；裁成 C 端只读视图。groupId 不传 = 全部视频。 */
    @GetMapping
    public Result<List<VideoClientVO>> list(
            @RequestParam(required = false) Long groupId,
            @RequestParam(name = "scope", defaultValue = "PUBLIC") DataScope scope) {
        List<VideoClientVO> list = videoService.listForClient(scope, groupId).stream()
                .map(VideoCController::toClientVO)
                .toList();
        return Result.ok(list);
    }

    /**
     * 上传一支视频（C 端"传"入口，与相册 C 端上传同一口径）。multipart/form-data：{@code file} 视频本体，
     * {@code groupIds} 初始所属分组（可选，留空=未分组，只在「全部视频」里可见）。
     *
     * <p>直接复用 B 端那条 {@link VideoService#upload}：流式落盘、类型校验（非视频 415）、分区/属主/添加人、
     * 分组绑定全是同一套实现，C 端不另写一份。返回新视频的 id 即可——h5 上传成功后重拉列表，不需要播放地址。
     */
    @PostMapping
    public Result<Long> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(name = "groupIds", required = false) List<Long> groupIds,
            @RequestParam(name = "scope", defaultValue = "PUBLIC") DataScope scope) {
        VideoVO vo = videoService.upload(file, groupIds, scope);
        return Result.ok(vo.getId());
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

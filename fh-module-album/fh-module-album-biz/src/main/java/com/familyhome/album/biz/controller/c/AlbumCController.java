package com.familyhome.album.biz.controller.c;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.familyhome.album.api.dto.AlbumCityDTO;
import com.familyhome.album.api.dto.AlbumImageBindRequest;
import com.familyhome.album.biz.model.vo.admin.AlbumGroupVO;
import com.familyhome.album.biz.model.vo.admin.AlbumImageVO;
import com.familyhome.album.biz.model.vo.client.AlbumGroupCoverVO;
import com.familyhome.album.biz.model.vo.client.AlbumGroupOptionVO;
import com.familyhome.album.biz.model.vo.client.AlbumImageBriefVO;
import com.familyhome.album.biz.service.AlbumCityService;
import com.familyhome.album.biz.service.AlbumGroupService;
import com.familyhome.album.biz.service.AlbumImageService;
import com.familyhome.common.enums.AlbumScope;
import com.familyhome.common.enums.ContentStatus;
import com.familyhome.common.result.PageResult;
import com.familyhome.common.result.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端相册接口：全部按 query scope 分区，默认 FAMILY，PERSONAL 只允许当前账号。
 * 读写分组均须上架，图片只读在架；裸 ID 越界统一 404，业务校验与 B 端共用服务。
 */
@Validated
@RestController
@RequestMapping("/api/c/album")
@RequiredArgsConstructor
public class AlbumCController {

    private final AlbumGroupService groupService;
    private final AlbumImageService imageService;
    private final AlbumCityService cityService;

    /** 上架分组封面，空组不展示；只有家庭档追加无分组图片的「其他」卡。 */
    @GetMapping("/groups/covers")
    public Result<List<AlbumGroupCoverVO>> listCovers(
            @RequestParam(defaultValue = "FAMILY") AlbumScope scope) {
        return Result.ok(groupService.listCovers(scope));
    }

    /** 上传候选含上架空组；个人只列自己的，C 端不提供新增分组。 */
    @GetMapping("/groups/options")
    public Result<List<AlbumGroupOptionVO>> listGroupOptions(
            @RequestParam(defaultValue = "FAMILY") AlbumScope scope) {
        List<AlbumGroupOptionVO> options = groupService.list(null, scope).stream()
                .filter(group -> group.getStatus() == ContentStatus.ON_SHELF)
                .map(AlbumCController::toOptionVO).toList();
        return Result.ok(options);
    }

    /** 本分区城市候选及在架图片数。 */
    @GetMapping("/cities")
    public Result<List<AlbumCityDTO>> listCities(@RequestParam(defaultValue = "FAMILY") AlbumScope scope) {
        return Result.ok(cityService.listCities(scope));
    }

    /** 必须指定本分区的上架分组，或明确选择家庭 ungrouped；不得无条件浏览图片。 */
    @GetMapping("/images")
    public Result<PageResult<AlbumImageBriefVO>> pageImages(
            @RequestParam(required = false) Long groupId,
            @RequestParam(defaultValue = "false") boolean ungrouped,
            @RequestParam(defaultValue = "FAMILY") AlbumScope scope,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码从 1 开始") long pageNo,
            @RequestParam(defaultValue = "9") @Min(1) @Max(value = 100, message = "每页最多 100 条") int pageSize) {
        Page<AlbumImageVO> page = imageService.pageForMp(
                groupId, ungrouped, null, ContentStatus.ON_SHELF, pageNo, pageSize, scope, true);
        List<AlbumImageBriefVO> list = page.getRecords().stream().map(AlbumCController::toBriefVO).toList();
        return Result.ok(PageResult.of(list, page.getTotal(), page.getCurrent(), (int) page.getSize()));
    }

    /** 批量绑定只接受本分区已有图片或本人新上传文件，所有 ID 校验完成后再写入。 */
    @PostMapping("/groups/{groupId}/images")
    public Result<AlbumGroupService.BatchBindResult> bindImages(
            @PathVariable Long groupId,
            @RequestBody @Valid BindImagesRequest request,
            @RequestParam(defaultValue = "FAMILY") AlbumScope scope) {
        return Result.ok(groupService.bindImages(groupId, request.getItems(), scope, true));
    }

    private static AlbumGroupOptionVO toOptionVO(AlbumGroupVO group) {
        AlbumGroupOptionVO vo = new AlbumGroupOptionVO();
        vo.setId(group.getId());
        vo.setName(group.getName());
        return vo;
    }

    private static AlbumImageBriefVO toBriefVO(AlbumImageVO image) {
        AlbumImageBriefVO vo = new AlbumImageBriefVO();
        vo.setId(image.getId());
        vo.setFileId(image.getFileId());
        vo.setUrl(image.getUrl());
        vo.setThumbUrl(image.getThumbUrl());
        return vo;
    }

    @Data
    public static class BindImagesRequest {
        @Valid
        private List<AlbumImageBindRequest> items;
    }
}

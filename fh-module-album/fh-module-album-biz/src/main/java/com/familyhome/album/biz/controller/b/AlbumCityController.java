package com.familyhome.album.biz.controller.b;

import com.familyhome.album.api.dto.AlbumCityDTO;
import com.familyhome.album.biz.service.AlbumCityService;
import com.familyhome.common.enums.AlbumScope;
import com.familyhome.common.result.Result;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** B 端分区城市分布与手动重算；默认家庭，个人按当前账号隔离。 */
@Validated
@RestController
@RequestMapping("/api/b/album")
@RequiredArgsConstructor
public class AlbumCityController {

    private final AlbumCityService cityService;

    @GetMapping("/cities")
    public Result<List<AlbumCityDTO>> listCities(@RequestParam(defaultValue = "FAMILY") AlbumScope scope) {
        return Result.ok(cityService.listCities(scope));
    }

    /** 只重算请求分区，不影响其他相册或其他账号。 */
    @PostMapping("/cities/recalculate")
    public Result<Void> recalculateCities(@RequestParam(defaultValue = "FAMILY") AlbumScope scope) {
        cityService.recalculateCities(scope);
        return Result.ok();
    }
}

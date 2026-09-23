package com.familyhome.album.biz.service;

import com.familyhome.album.api.dto.AlbumCityDTO;
import com.familyhome.album.biz.dao.AlbumCityMapper;
import com.familyhome.album.biz.dao.AlbumImageMapper;
import com.familyhome.album.biz.entity.AlbumCityDO;
import com.familyhome.album.biz.entity.AlbumImageDO;
import com.familyhome.common.enums.AlbumScope;
import com.familyhome.common.enums.ContentStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 城市统计按 scope + owner 隔离，只统计本分区在架图片，分组上下架不改变图片统计。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlbumCityService {

    private final AlbumCityMapper cityMapper;
    private final AlbumImageMapper imageMapper;

    public List<AlbumCityDTO> listCities(AlbumScope scope) {
        AlbumPartition partition = AlbumPartition.forRequest(scope);
        return cityMapper.selectList(partition.cities().orderByDesc(AlbumCityDO::getCount))
                .stream().map(city -> {
                    AlbumCityDTO dto = new AlbumCityDTO();
                    dto.setCity(city.getCity());
                    dto.setCount(city.getCount() != null ? city.getCount().longValue() : 0L);
                    return dto;
                }).toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void recalculateCities(AlbumScope scope) {
        recalculate(AlbumPartition.forRequest(scope));
    }

    /** 写入链路在原事务末尾重算受影响分区；绝不清空其他分区的统计。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void recalculate(AlbumPartition partition) {
        cityMapper.delete(partition.cities());
        List<AlbumImageDO> images = imageMapper.selectList(partition.images()
                .eq(AlbumImageDO::getStatus, ContentStatus.ON_SHELF)
                .isNotNull(AlbumImageDO::getCity)
                .ne(AlbumImageDO::getCity, ""));
        Map<String, Long> counts = images.stream()
                .filter(image -> !image.getCity().isBlank())
                .collect(Collectors.groupingBy(AlbumImageDO::getCity, Collectors.counting()));
        LocalDateTime now = LocalDateTime.now();
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            AlbumCityDO city = new AlbumCityDO();
            city.setScope(partition.scope());
            city.setOwnerId(partition.ownerId());
            city.setCity(entry.getKey());
            city.setCount(entry.getValue().intValue());
            city.setCreateTime(now);
            city.setUpdateTime(now);
            cityMapper.insert(city);
        }
        log.info("重算城市统计: partition={}, cities={}, images={}", partition, counts.size(), images.size());
    }
}

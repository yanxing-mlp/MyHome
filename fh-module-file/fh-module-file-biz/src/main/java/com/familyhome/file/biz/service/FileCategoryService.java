package com.familyhome.file.biz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.familyhome.common.context.DataPartition;
import com.familyhome.common.exception.BizException;
import com.familyhome.common.exception.ErrorCode;
import com.familyhome.file.biz.dao.FileCategoryMapper;
import com.familyhome.file.biz.entity.FileCategoryDO;
import com.familyhome.file.biz.model.vo.admin.FileCategoryVO;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文件分类字典服务（B 端"文件管理"下拉框）。
 *
 * <p>只提供"列 + 新建"两个动作：一期入口是下拉框里的内联新建（与菜谱分类同一套交互），
 * 没有独立管理页，所以改名和删除接口刻意不做——真要删的那刻再补，不提前铺。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileCategoryService {

    private final FileCategoryMapper categoryMapper;

    /** 当前分区的分类，按 id 升序（新建的排在后面，下拉框里顺序稳定） */
    public List<FileCategoryVO> list(DataPartition partition) {
        return categoryMapper.selectList(partitionQuery(partition).orderByAsc(FileCategoryDO::getId)).stream()
                .map(c -> new FileCategoryVO(c.getId(), c.getName()))
                .toList();
    }

    /** 仅当前分区的 id → 名称，不能通过文档行上的 categoryId 泄漏其他分区名称。 */
    public Map<Long, String> nameMap(DataPartition partition) {
        return categoryMapper.selectList(partitionQuery(partition)).stream()
                .collect(Collectors.toMap(FileCategoryDO::getId, FileCategoryDO::getName));
    }

    /** 同分区内去首尾空格后判重，数据库唯一索引兜底并发冲突，返回 409。 */
    @Transactional
    public Long create(String name, DataPartition partition) {
        LambdaQueryWrapper<FileCategoryDO> wrapper = partitionQuery(partition);
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "请输入分类名称");
        }
        wrapper.apply("TRIM(name) = {0}", trimmed);
        if (categoryMapper.selectCount(wrapper) > 0) {
            throw BizException.of(ErrorCode.FILE_CATEGORY_NAME_DUPLICATED, "已经有叫「" + trimmed + "」的分类了");
        }
        FileCategoryDO category = new FileCategoryDO();
        category.setName(trimmed);
        category.setScope(partition.scope());
        category.setOwnerId(partition.ownerId());
        categoryMapper.insert(category);
        log.info("新增文件分类: id={}, name={}, scope={}, ownerId={}",
                category.getId(), trimmed, partition.scope(), partition.ownerId());
        return category.getId();
    }

    /** 跨分区/账号与不存在统一 404，文档上传必须在写盘前调用。 */
    public FileCategoryDO require(Long id, DataPartition partition) {
        LambdaQueryWrapper<FileCategoryDO> wrapper = partitionQuery(partition);
        FileCategoryDO category = id == null ? null
                : categoryMapper.selectOne(wrapper.eq(FileCategoryDO::getId, id));
        if (category == null || !partition.contains(category.getScope(), category.getOwnerId())) {
            throw BizException.of(ErrorCode.FILE_CATEGORY_NOT_FOUND, "分类不存在，请重新选择");
        }
        return category;
    }

    private LambdaQueryWrapper<FileCategoryDO> partitionQuery(DataPartition partition) {
        // 内部调用也不接受手工构造的其他账号分区，且 PUBLIC 请求同样必须登录。
        if (!partition.equals(DataPartition.forRequest(partition.scope()))) {
            throw BizException.of(ErrorCode.FILE_CATEGORY_NOT_FOUND, "分类不存在，请重新选择");
        }
        return new LambdaQueryWrapper<FileCategoryDO>()
                .eq(FileCategoryDO::getScope, partition.scope())
                .eq(FileCategoryDO::getOwnerId, partition.ownerId());
    }
}

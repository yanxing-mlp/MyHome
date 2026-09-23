package com.familyhome.recipe.biz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.familyhome.common.enums.ContentStatus;
import com.familyhome.common.exception.BizException;
import com.familyhome.common.exception.ErrorCode;
import com.familyhome.recipe.api.dto.CreatePracticeGroupRequest;
import com.familyhome.recipe.api.dto.PracticeGroupDTO;
import com.familyhome.recipe.api.dto.PracticeOptionDTO;
import com.familyhome.recipe.api.dto.PracticeOptionItemRequest;
import com.familyhome.recipe.api.dto.UpdatePracticeGroupRequest;
import com.familyhome.recipe.biz.entity.RecipeDO;
import com.familyhome.recipe.biz.entity.RecipePracticeGroupDO;
import com.familyhome.recipe.biz.entity.RecipePracticeOptionDO;
import com.familyhome.recipe.biz.entity.RecipePracticeRelDO;
import com.familyhome.recipe.biz.mapper.RecipeMapper;
import com.familyhome.recipe.biz.mapper.RecipePracticeGroupMapper;
import com.familyhome.recipe.biz.mapper.RecipePracticeOptionMapper;
import com.familyhome.recipe.biz.mapper.RecipePracticeRelMapper;
import com.familyhome.recipe.biz.service.RecipePracticeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 做法字典服务实现。分组硬删除 + 级联删选项和解绑菜品关联（口径同分类字典）。
 *
 * <p>分组和选项都没有排序字段，一律按 id（录入顺序）返回；选项只跟着分组存，
 * 编辑弹窗一次提交组名 + 全部选项，服务端按差量改（保住已有选项 id，见 {@link #saveOptions}）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecipePracticeServiceImpl implements RecipePracticeService {

    private final RecipePracticeGroupMapper groupMapper;
    private final RecipePracticeOptionMapper optionMapper;
    private final RecipePracticeRelMapper relMapper;
    private final RecipeMapper recipeMapper;

    @Override
    public List<PracticeGroupDTO> listAll() {
        List<RecipePracticeGroupDO> groups = groupMapper.selectList(
                new LambdaQueryWrapper<RecipePracticeGroupDO>().orderByAsc(RecipePracticeGroupDO::getId));
        if (groups.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> groupIds = groups.stream().map(RecipePracticeGroupDO::getId).toList();
        Map<Long, List<PracticeOptionDTO>> optionMap = optionMapper.selectList(
                        new LambdaQueryWrapper<RecipePracticeOptionDO>()
                                .in(RecipePracticeOptionDO::getGroupId, groupIds)
                                .orderByAsc(RecipePracticeOptionDO::getId))
                .stream()
                .map(RecipePracticeServiceImpl::toOptionDTO)
                .collect(Collectors.groupingBy(PracticeOptionDTO::getGroupId));
        Map<Long, Long> recipeCountMap = countLiveRecipesByGroup();

        return groups.stream().map(group -> {
            PracticeGroupDTO dto = new PracticeGroupDTO();
            dto.setId(group.getId());
            dto.setName(group.getName());
            dto.setRecipeCount(recipeCountMap.getOrDefault(group.getId(), 0L).intValue());
            dto.setOptions(optionMap.getOrDefault(group.getId(), Collections.emptyList()));
            return dto;
        }).toList();
    }

    @Override
    @Transactional
    public Long createGroup(CreatePracticeGroupRequest request) {
        RecipePracticeGroupDO group = new RecipePracticeGroupDO();
        group.setName(validateGroupName(request.getName(), null));
        groupMapper.insert(group);
        log.info("新增做法分组: id={}, name={}", group.getId(), group.getName());
        return group.getId();
    }

    @Override
    @Transactional
    public void updateGroup(Long id, UpdatePracticeGroupRequest request) {
        // 必须在任何选项读取前锁住分组；selectById 是快照读，RR 下等待锁后仍可能读到旧选项。
        RecipePracticeGroupDO group = groupMapper.selectOne(new LambdaQueryWrapper<RecipePracticeGroupDO>()
                .eq(RecipePracticeGroupDO::getId, id)
                .last("FOR UPDATE"));
        if (group == null) {
            throw BizException.notFound(null, "做法分组不存在");
        }
        group.setName(validateGroupName(request.getName(), id));
        groupMapper.updateById(group);

        // 口径同菜谱的做法分组关联：null=这次不动选项，空数组=把选项清空
        int optionCount = request.getOptions() == null ? -1 : request.getOptions().size();
        if (request.getOptions() != null) {
            saveOptions(id, request.getOptions());
        }
        log.info("更新做法分组: id={}, name={}, 选项数={}", id, group.getName(), optionCount);
    }

    @Override
    @Transactional
    public void deleteGroup(Long id) {
        // 与编辑保持相同的加锁顺序，避免级联删除和选项覆盖交错。
        RecipePracticeGroupDO group = groupMapper.selectOne(new LambdaQueryWrapper<RecipePracticeGroupDO>()
                .eq(RecipePracticeGroupDO::getId, id)
                .last("FOR UPDATE"));
        if (group == null) {
            throw BizException.notFound(null, "做法分组不存在");
        }
        int options = optionMapper.delete(new LambdaQueryWrapper<RecipePracticeOptionDO>()
                .eq(RecipePracticeOptionDO::getGroupId, id));
        int rels = relMapper.delete(new LambdaQueryWrapper<RecipePracticeRelDO>()
                .eq(RecipePracticeRelDO::getGroupId, id));
        groupMapper.deleteById(id);
        log.info("删除做法分组: id={}, name={}, 级联删除选项={} 解绑菜品={}",
                id, group.getName(), options, rels);
    }

    /**
     * 按差量覆盖组内选项：带 id 的改名字，不带 id 的新增，这次没列出的删掉。
     *
     * <p>不做成"全删重插"是因为购物车和订单快照的做法 JSON 存的就是 optionId，
     * id 一变，用户已经选好的做法就查不到了（C 端对查不到的 optionId 直接忽略，不会报错但会静默丢掉选择）。
     * 被删掉的选项同样按"查不到就忽略"处理，不回填历史购物车/订单。
     */
    private void saveOptions(Long groupId, List<PracticeOptionItemRequest> options) {
        // 调用方已持有组行锁；选项也使用当前读，避免复用事务里已有的 RR 快照。
        Map<Long, RecipePracticeOptionDO> existing = optionMapper.selectList(
                        new LambdaQueryWrapper<RecipePracticeOptionDO>()
                                .eq(RecipePracticeOptionDO::getGroupId, groupId)
                                .last("FOR UPDATE"))
                .stream()
                .collect(Collectors.toMap(RecipePracticeOptionDO::getId, option -> option));

        Set<Long> keptIds = new HashSet<>();
        List<String> finalNames = new ArrayList<>(options.size());
        // 先完整验证提交内容，再删除或改名；不使用 Java 字符串集合模拟数据库判重。
        for (PracticeOptionItemRequest item : options) {
            if (item == null) {
                throw BizException.of(ErrorCode.BAD_REQUEST, "做法选项不能为空");
            }
            finalNames.add(trimName(item.getName(), "选项名称不能为空"));
            if (item.getId() != null) {
                if (!keptIds.add(item.getId())) {
                    throw BizException.of(ErrorCode.BAD_REQUEST, "做法选项ID不能重复");
                }
                // 外组或不存在的 id 均不能参与本组覆盖。
                if (!existing.containsKey(item.getId())) {
                    throw BizException.notFound(null, "做法选项不存在");
                }
            }
        }

        // 先释放未保留的名称，允许删除旧选项并新增同名选项。
        for (Long optionId : existing.keySet()) {
            if (!keptIds.contains(optionId)) {
                optionMapper.deleteById(optionId);
            }
        }

        // 所有待改名的保留项先移到临时名称，支持 A/B 交换和多项循环改名，id 不变。
        for (int i = 0; i < options.size(); i++) {
            RecipePracticeOptionDO target = existing.get(options.get(i).getId());
            if (target != null && !finalNames.get(i).equals(target.getName())) {
                target.setName(nextTemporaryName(groupId, finalNames));
                optionMapper.updateById(target);
            }
        }

        for (int i = 0; i < options.size(); i++) {
            PracticeOptionItemRequest item = options.get(i);
            String name = finalNames.get(i);
            // 仅在本组按数据库排序规则比较，并排除自身；最终唯一索引兜底，异常使整笔事务回滚。
            if (optionNameExists(groupId, name, item.getId())) {
                throw BizException.of(ErrorCode.RECIPE_PRACTICE_OPTION_NAME_DUPLICATED, "同一做法分组内的选项名称不能重复");
            }
            if (item.getId() == null) {
                RecipePracticeOptionDO option = new RecipePracticeOptionDO();
                option.setGroupId(groupId);
                option.setName(name);
                optionMapper.insert(option);
            } else {
                RecipePracticeOptionDO target = existing.get(item.getId());
                if (!name.equals(target.getName())) {
                    target.setName(name);
                    optionMapper.updateById(target);
                }
            }
        }
    }

    private String validateGroupName(String name, Long excludeId) {
        name = trimName(name, "分组名称不能为空");
        if (groupMapper.selectCount(new LambdaQueryWrapper<RecipePracticeGroupDO>()
                .apply("TRIM(name) = {0}", name.trim())
                .ne(excludeId != null, RecipePracticeGroupDO::getId, excludeId)) > 0) {
            throw BizException.of(ErrorCode.RECIPE_PRACTICE_GROUP_NAME_DUPLICATED, "做法分组名称已存在");
        }
        return name;
    }

    private String trimName(String name, String message) {
        name = name == null ? "" : name.trim();
        if (!StringUtils.hasText(name)) {
            throw BizException.of(ErrorCode.BAD_REQUEST, message);
        }
        return name;
    }

    private boolean optionNameExists(Long groupId, String name, Long excludeId) {
        return optionMapper.selectOne(new LambdaQueryWrapper<RecipePracticeOptionDO>()
                .select(RecipePracticeOptionDO::getId)
                .eq(RecipePracticeOptionDO::getGroupId, groupId)
                .apply("TRIM(name) = {0}", name.trim())
                .ne(excludeId != null, RecipePracticeOptionDO::getId, excludeId)
                .last("LIMIT 1 FOR UPDATE")) != null;
    }

    private String nextTemporaryName(Long groupId, List<String> finalNames) {
        String name;
        do {
            // 32 个 ASCII 字符（32 字节），不超过选项名称列长度。
            name = UUID.randomUUID().toString().replace("-", "");
        } while (optionNameExists(groupId, name, null) || optionMapper.matchesAnyName(name, finalNames));
        return name;
    }

    /**
     * 每个做法分组关联的菜品数量。
     *
     * <p>删菜是软删（只把 recipe.status 改成 DELETED，关联行留着），所以要按未删除的菜品 id 过滤，
     * 否则这一列会把已经删掉的菜也数进去。下架的菜照样算——菜还在，只是暂时不卖。
     */
    private Map<Long, Long> countLiveRecipesByGroup() {
        List<Long> liveRecipeIds = recipeMapper.selectList(new LambdaQueryWrapper<RecipeDO>()
                        .select(RecipeDO::getId)
                        .ne(RecipeDO::getStatus, ContentStatus.DELETED.name()))
                .stream()
                .map(RecipeDO::getId)
                .toList();
        if (liveRecipeIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return relMapper.selectList(new LambdaQueryWrapper<RecipePracticeRelDO>()
                        .in(RecipePracticeRelDO::getRecipeId, liveRecipeIds))
                .stream()
                .collect(Collectors.groupingBy(RecipePracticeRelDO::getGroupId, Collectors.counting()));
    }

    private static PracticeOptionDTO toOptionDTO(RecipePracticeOptionDO option) {
        PracticeOptionDTO dto = new PracticeOptionDTO();
        dto.setId(option.getId());
        dto.setGroupId(option.getGroupId());
        dto.setName(option.getName());
        return dto;
    }
}

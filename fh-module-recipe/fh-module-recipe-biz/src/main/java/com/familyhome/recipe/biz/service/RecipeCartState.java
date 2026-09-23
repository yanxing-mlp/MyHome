package com.familyhome.recipe.biz.service;

import com.familyhome.common.exception.BizException;
import com.familyhome.recipe.biz.entity.RecipeCartCheckoutDO;
import com.familyhome.recipe.biz.mapper.RecipeCartStateMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * 家庭共享购物车状态管理：所有读写车、下单与加菜先锁固定行，再做一致性读。
 *
 * <p>必须加入调用方事务，锁持有至主单、明细、清车、回执和版本全部提交。
 * 锁顺序固定为车 state -> 订单；完成、取消、删单只锁订单，不反向取车锁。
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class RecipeCartState {

    private final RecipeCartStateMapper stateMapper;

    /** 固定行由 V317 初始化；即使空车也锁这行，不临时插行或依赖 JVM 锁。 */
    public Long lock() {
        Long version = stateMapper.lockVersion();
        if (version == null) {
            throw new IllegalStateException("购物车状态未初始化，请检查 V317 迁移");
        }
        return version;
    }

    public void checkVersion(Long requestedVersion, Long currentVersion) {
        if (!Objects.equals(requestedVersion, currentVersion)) {
            throw BizException.of(null, "购物车已变化，请刷新后重试");
        }
    }

    /** 调用方已持有车锁；每次成功改车或消费只推进一次。 */
    public Long advance(Long currentVersion) {
        Long nextVersion = Math.addExact(currentVersion, 1L);
        if (stateMapper.advance(currentVersion) != 1) {
            throw new IllegalStateException("购物车版本推进失败");
        }
        return nextVersion;
    }

    /**
     * 先查回执再校验当前版本；同操作同目标返回原订单 ID，订单是否仍存在由调用方锁主单检查。
     * targetOrderId 为 null 表示 create，非空表示 append；不按账号区分消费回执。
     */
    public Long findCheckoutOrderId(Long version, Long targetOrderId) {
        RecipeCartCheckoutDO checkout = stateMapper.selectCheckout(version);
        if (checkout == null) {
            return null;
        }
        if (!Objects.equals(checkout.getTargetOrderId(), targetOrderId)) {
            throw BizException.of(null, "这份购物车已提交到订单 #" + checkout.getOrderId() + "，请刷新后重试");
        }
        return checkout.getOrderId();
    }

    /** 回执有意永久保留，订单删除也不清理，旧请求不得借删单重新消费。 */
    public void recordCheckout(Long version, Long orderId, Long targetOrderId) {
        stateMapper.insertCheckout(version, orderId, targetOrderId);
    }
}

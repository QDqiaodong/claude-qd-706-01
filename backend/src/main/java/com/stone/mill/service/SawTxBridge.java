package com.stone.mill.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.stone.mill.entity.SawBatch;
import com.stone.mill.entity.SawOutput;
import com.stone.mill.repository.SawBatchRepository;

/**
 * 模块八独立事务桥：外层事务因唯一键冲突被标记 rollback-only 后（断网重试撞 client_token、
 * 跨批次重复扫码撞 live_slab_id），仍需在全新事务里把首次结果 / 先到归属读出来回带，
 * 因此这些读取必须挂 REQUIRES_NEW——失败方看到的是已提交的确定结果，而不是静默覆盖。
 */
@Component
public class SawTxBridge {

    private final SawBatchRepository sawRepository;

    public SawTxBridge(SawBatchRepository sawRepository) {
        this.sawRepository = sawRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public SawOutput findOutputByToken(String token) {
        return sawRepository.findOutputByToken(token);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public SawOutput findLiveOutputBySlab(Long slabId) {
        return sawRepository.findLiveOutputBySlab(slabId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public SawBatch findBatch(Long id) {
        return sawRepository.findById(id).orElse(null);
    }
}

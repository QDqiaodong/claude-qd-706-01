package com.stone.mill.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 模块八：锯切批次差异待核。
 *
 * <p>交接时由「计划 vs 实产」自动识别，现场记录不丢弃；负责人逐项核清（必须填原因）：
 * MISSING_NO 缺号 / DUP_NO 重复号 / SIZE_TOL 尺寸超差 / COUNT_MISMATCH 产出数量不符 /
 * SAW_LOSS_UNEXPLAINED 未解释锯耗。
 *
 * <p>{@code openKey} 生成列：OPEN 时为「批次|类型|顺序|大板」，配合唯一索引既防止重算重复挂差异，
 * 又让已核清（RESOLVED → NULL）的历史可以保留；只要还有 OPEN 差异，批次就不能收口。
 */
@Data
@Entity
@Table(name = "mill_saw_diff")
public class SawDiff {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    /** MISSING_NO / DUP_NO / SIZE_TOL / COUNT_MISMATCH / SAW_LOSS_UNEXPLAINED */
    @Column(name = "diff_type", nullable = false, length = 25)
    private String diffType;

    @Column(name = "seq_no")
    private Integer seqNo;

    @Column(name = "slab_id")
    private Long slabId;

    @Column(name = "slab_code", length = 30)
    private String slabCode;

    @Column(name = "detail", nullable = false, length = 500)
    private String detail;

    /** OPEN 待核 / RESOLVED 已核清（重算不复活） */
    @Column(name = "status", nullable = false, length = 15)
    private String status;

    /** CONFIRM 确认实际 / REASSIGN 更正归属 / VOID 作废误登 / FIX 补录或改尺 */
    @Column(name = "resolution", length = 20)
    private String resolution;

    @Column(name = "resolve_reason", length = 500)
    private String resolveReason;

    @Column(name = "resolved_by", length = 30)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** 数据库生成列：OPEN 差异的去重键，应用只读不写 */
    @Column(name = "open_key", insertable = false, updatable = false)
    private String openKey;
}

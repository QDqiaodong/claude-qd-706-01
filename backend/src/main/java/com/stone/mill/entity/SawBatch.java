package com.stone.mill.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;

/**
 * 模块八：锯切批次。
 *
 * <p>班组开锯时建立并冻结当时的工单 / 荒料 / 锯切方式 / 班组 / 计划口径；
 * 排板顺序冻结在 {@link SawPlan}，逐片产出在 {@link SawOutput}。
 *
 * <p>生命周期：OPEN 开锯登记中 → WAIT_CHECK 已交接、差异待核 → CLOSED 已收口（谱系固化）。
 * 收口必须在单事务内完成差异复核 + 计数固化 + 工单完成数推进；{@link Version} 乐观锁与
 * 收口时的行锁共同保证两人同时收口 / 重复点击 / 中途失败只有一个有效结果。
 */
@Data
@Entity
@Table(name = "mill_saw_batch")
public class SawBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_no", nullable = false, length = 30)
    private String batchNo;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "block_id", nullable = false)
    private Long blockId;

    /** FRAME 框架锯 / DISC 圆盘锯 / WIRE 绳锯 / COMBO 组合锯（开锯冻结） */
    @Column(name = "saw_type", nullable = false, length = 20)
    private String sawType;

    @Column(name = "team", length = 20)
    private String team;

    // ---------------- 开锯瞬间的冻结快照（基础资料后续变化不改写） ----------------

    @Column(name = "snap_order_no", nullable = false, length = 30)
    private String snapOrderNo;

    @Column(name = "snap_block_code", nullable = false, length = 30)
    private String snapBlockCode;

    @Column(name = "snap_plan_count", nullable = false)
    private Integer snapPlanCount;

    /** 本批冻结的排板顺序条数 */
    @Column(name = "plan_count", nullable = false)
    private Integer planCount;

    /** 尺寸超差阈值（cm）：实际长宽与计划任一偏差超出即差异 */
    @Column(name = "size_tolerance_cm", nullable = false)
    private Integer sizeToleranceCm;

    /** OPEN / WAIT_CHECK / CLOSED */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "handed_over_by", length = 30)
    private String handedOverBy;

    @Column(name = "handed_over_at")
    private LocalDateTime handedOverAt;

    // ---------------- 收口时一次性固化（未收口前不写、不参与工单完成数） ----------------

    @Column(name = "closed_finished")
    private Integer closedFinished;

    @Column(name = "closed_remnant")
    private Integer closedRemnant;

    @Column(name = "closed_loss")
    private Integer closedLoss;

    @Column(name = "closed_void")
    private Integer closedVoid;

    @Column(name = "closed_by", length = 30)
    private String closedBy;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "closed_remark", length = 500)
    private String closedRemark;

    /** 未核清差异条数：只有 0 才允许收口 */
    @Column(name = "open_diff_count", nullable = false)
    private Integer openDiffCount;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

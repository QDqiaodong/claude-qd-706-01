package com.stone.mill.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 模块八：锯切批次逐片产出登记。
 *
 * <p>三道数据库层防线（不靠应用层先查后插）：
 * <ul>
 *   <li>{@code uk_output_token}：{@code clientToken} 全局唯一——现场断网重试 / 重复扫码
 *       同 token 只产生一片，重复提交返回首次结果（幂等）；</li>
 *   <li>{@code uk_output_live_slab}：生成列 {@code liveSlabId} 全局唯一——未作废且挂着
 *       大板的产出只能属于一个批次，跨批次占用先到先得，后到方收到 409 与当前归属；</li>
 *   <li>{@code idx_output_source}：{@code sourceOutputId} 记录返切来源，被后续批次引用的
 *       产出作废时应用层拒绝（谱系链不可断）。</li>
 * </ul>
 * <p>产出类型：FINISHED 成品 / REMNANT 可返切余料 / SAW_LOSS 锯耗。作废只置位不物理删除。
 */
@Data
@Entity
@Table(name = "mill_saw_output")
public class SawOutput {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    /** PDA 扫码幂等令牌（设备号+流水），全局唯一 */
    @Column(name = "client_token", nullable = false, length = 60)
    private String clientToken;

    @Column(name = "slab_id")
    private Long slabId;

    @Column(name = "slab_code", length = 30)
    private String slabCode;

    /** FINISHED / REMNANT / SAW_LOSS */
    @Column(name = "output_type", nullable = false, length = 15)
    private String outputType;

    /** 对应排板顺序号（计划外产出可空） */
    @Column(name = "seq_no")
    private Integer seqNo;

    @Column(name = "actual_length_cm")
    private Integer actualLengthCm;

    @Column(name = "actual_width_cm")
    private Integer actualWidthCm;

    @Column(name = "thickness_cm", precision = 5, scale = 1)
    private BigDecimal thicknessCm;

    /** 返切来源大板 / 余料 */
    @Column(name = "source_slab_id")
    private Long sourceSlabId;

    /** 返切来源产出（被引用后不得作废） */
    @Column(name = "source_output_id")
    private Long sourceOutputId;

    @Column(name = "operator", length = 30)
    private String operator;

    @Column(name = "produced_at")
    private LocalDateTime producedAt;

    /** 现场备注：锯耗必填损耗原因，留空即判「未解释锯耗」差异 */
    @Column(name = "note", length = 300)
    private String note;

    /** LIVE 有效 / VOID 已作废 */
    @Column(name = "status", nullable = false, length = 15)
    private String status;

    @Column(name = "void_reason", length = 500)
    private String voidReason;

    @Column(name = "void_by", length = 30)
    private String voidBy;

    @Column(name = "void_at")
    private LocalDateTime voidAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** 数据库生成列：LIVE 且有 slabId 时 = slabId，否则 NULL；应用只读不写 */
    @Column(name = "live_slab_id", insertable = false, updatable = false)
    private Long liveSlabId;
}
